package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuStageSchedule;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraParticleReadTypes;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Source emitter buffers and reflected attribute offsets for one GPU particle reader. */
public final class NiagaraGpuParticleRead {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    public record Source(NiagaraDefinition.Emitter emitter, @Nullable NiagaraGpuParticleBuffers particles, boolean hasGpuContext) {
        public Source(NiagaraDefinition.Emitter emitter, @Nullable NiagaraGpuParticleBuffers particles) {
            this(emitter, particles, emitter.simulation() instanceof NiagaraDefinition.GpuSimulation);
        }
    }

    private final String symbol, owner;
    private String target;
    private record AttributeRequest(int slot, String name, String type) { }
    private record AttributeTable(byte[] indices, byte[] compressed, int acquireTagRegister) { }
    private final List<AttributeRequest> requests;
    private final int attributeSlots;
    private final AttributeTable unboundAttributes;
    private AttributeTable boundAttributes;
    private AttributeTable cachedAttributes;
    private NiagaraDefinition.@Nullable Emitter cachedEmitter;
    private final Function<String, Source> sources;
    private final GpuBufferSlice dummy;
    private final NiagaraGpuReaderSnapshot snapshot;
    private @Nullable Source source;
    private @Nullable NiagaraGpuDataBuffer data;
    private @Nullable GpuBuffer idTable, freeIds;
    private int countOffset = -1, spawned, tag;
    private boolean reportedMissingGpuContext;

    NiagaraGpuParticleRead(String symbol, String owner, JsonObject definition, Function<String, Source> sources,
                           GpuBufferSlice dummy, NiagaraGpuReaderSnapshot snapshot) {
        this.symbol = symbol; this.owner = owner; this.target = definition.get("emitter").getAsString();
        JsonArray functions = definition.getAsJsonArray("functions");
        var requested = new ArrayList<AttributeRequest>();
        for (int i = 0; i < functions.size(); i++) {
            JsonObject function = functions.get(i).getAsJsonObject();
            String type = valueType(function.get("DefinitionName").getAsString());
            String name = attributeSpecifier(function);
            if (!type.isEmpty() && name != null && !SourceNames.equal(name, "None"))
                requested.add(new AttributeRequest(i, name, type));
        }
        requests = List.copyOf(requested);
        attributeSlots = Math.max(4, (functions.size() + 3) / 4 * 4);
        int[] absent = new int[attributeSlots];
        Arrays.fill(absent, -1);
        unboundAttributes = new AttributeTable(integers(absent), integers(new int[attributeSlots]), -1);
        boundAttributes = cachedAttributes = unboundAttributes;
        this.sources = sources; this.dummy = dummy; this.snapshot = snapshot;
    }

    void target(String value) {
        if (!target.equals(value)) {
            cachedEmitter = null;
            reportedMissingGpuContext = false;
        }
        target = value;
    }

    boolean readsSelf() { return SourceNames.equal(owner, target); }

    NiagaraGpuSimulation.@Nullable InterfaceIteration iteration() {
        Source current = target.isBlank() || SourceNames.equal(target, "None") ? null : sources.apply(target);
        if (current == null || !current.hasGpuContext())
            return new NiagaraGpuSimulation.InterfaceIteration(new NiagaraGpuDispatchGrid.Extent(1, 1, 1), null, -1);
        if (current.particles() == null)
            return new NiagaraGpuSimulation.InterfaceIteration(new NiagaraGpuDispatchGrid.Extent(1, 1, 1), null, -1);
        NiagaraGpuParticleBuffers particles = current.particles();
        int count = particles.readerCurrentInstances();
        return new NiagaraGpuSimulation.InterfaceIteration(new NiagaraGpuDispatchGrid.Extent(count, 1, 1),
                null, particles.readerCurrentCountOffset());
    }

    void prepare(NiagaraGpuStageSchedule.Invocation stage) {
        source = target.isBlank() || SourceNames.equal(target, "None") ? null : sources.apply(target);
        boolean self = source != null && SourceNames.equal(owner, source.emitter().sourceName());
        boolean context = source != null && source.hasGpuContext();
        if (source != null && !context && !reportedMissingGpuContext) {
            LOGGER.error("GPU particle reader cannot find a stateful GPU context for emitter '{}'", target);
            reportedMissingGpuContext = true;
        } else if (context) reportedMissingGpuContext = false;
        NiagaraGpuParticleBuffers particles = context ? source.particles() : null;
        data = null; idTable = freeIds = null; countOffset = -1; spawned = tag = 0;
        boundAttributes = unboundAttributes;
        if (particles != null) {
            boolean inPlace = self && stage.access() == NiagaraGpuStageSchedule.ParticleAccess.PARTIAL;
            data = inPlace ? snapshot.data() : particles.readerCurrentData();
            idTable = inPlace ? snapshot.idTable() : particles.readerIdTable();
            freeIds = particles.readerFreeIds();
            countOffset = inPlace ? snapshot.countOffset() : particles.readerCurrentCountOffset();
            spawned = particles.readerSpawnedInstances(self);
            tag = particles.readerAcquireTag(self);
        }
        if (source != null && data != null) {
            NiagaraDefinition.Emitter emitter = source.emitter();
            if (cachedEmitter != emitter) {
                cachedAttributes = attributes(emitter);
                cachedEmitter = emitter;
            }
            boundAttributes = cachedAttributes;
        }
    }

    private AttributeTable attributes(NiagaraDefinition.Emitter emitter) {
        if (requests.isEmpty()) {
            var id = NiagaraParticleReadTypes.gpu(emitter.attributes(), "ID", "ID");
            return new AttributeTable(unboundAttributes.indices(), unboundAttributes.compressed(), id == null ? -1 : id.component() + 1);
        }
        int[] offsets = new int[attributeSlots], halves = new int[attributeSlots];
        Arrays.fill(offsets, -1);
        for (AttributeRequest request : requests) {
            var attribute = NiagaraParticleReadTypes.gpu(emitter.attributes(), request.name(), request.type());
            if (attribute == null) {
                LOGGER.error("Niagara GPU particle reader cannot read attribute '{}' as '{}' from emitter '{}'",
                        request.name(), request.type(), emitter.sourceName());
                continue;
            }
            offsets[request.slot()] = attribute.component();
            halves[request.slot()] = attribute.type() == 2 ? 1 : 0;
        }
        var id = NiagaraParticleReadTypes.gpu(emitter.attributes(), "ID", "ID");
        return new AttributeTable(integers(offsets), integers(halves), id == null ? -1 : id.component() + 1);
    }

    @Nullable byte[] value(String qualified) {
        if (symbol.isEmpty()) return null;
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        if (!name.startsWith(symbol + "_")) return null;
        return switch (name.substring(symbol.length() + 1)) {
            case "IsLocalSpace" -> integer(source != null && source.emitter().localSpace() ? 1 : 0);
            case "NumSpawnedParticles" -> integer(spawned);
            case "SpawnedParticlesAcquireTag" -> integer(tag);
            case "InstanceCountOffset" -> integer(countOffset);
            case "ParticleStrideFloat", "ParticleStrideInt", "ParticleStrideHalf" -> integer(data == null ? 0 : data.stride());
            case "AcquireTagRegisterIndex" -> integer(boundAttributes.acquireTagRegister());
            case "AttributeIndices" -> boundAttributes.indices();
            case "AttributeCompressed" -> boundAttributes.compressed();
            default -> null;
        };
    }

    void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (symbol.isEmpty()) return;
        for (var binding : shader.bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage) || !storage.name().startsWith(symbol + "_")) continue;
            String suffix = storage.name().substring(symbol.length() + 1);
            GpuBuffer buffer = switch (suffix) {
                case "SpawnedIDsBuffer" -> freeIds;
                case "IDToIndexTable" -> idTable;
                case "InputFloatBuffer" -> data == null ? null : data.floats();
                case "InputIntBuffer" -> data == null ? null : data.integers();
                case "InputHalfBuffer" -> data == null ? null : data.halves();
                default -> null;
            };
            if (suffix.equals("SpawnedIDsBuffer") || suffix.equals("IDToIndexTable") || suffix.equals("InputFloatBuffer")
                    || suffix.equals("InputIntBuffer") || suffix.equals("InputHalfBuffer"))
                resources.storage(storage.name(), buffer == null ? dummy : buffer.slice(), storage.access());
        }
    }

    private static String valueType(String function) {
        String suffix = function.endsWith(" By ID") ? " By ID" : function.endsWith(" By Index") ? " By Index" : "";
        return function.startsWith("Get ") && !suffix.isEmpty() ? function.substring(4, function.length() - suffix.length()) : "";
    }

    private static @Nullable String attributeSpecifier(JsonObject function) {
        JsonArray specifiers = function.getAsJsonArray("Specifiers");
        if (specifiers == null) return null;
        for (var item : specifiers) {
            JsonObject pair = item.getAsJsonObject();
            if (!pair.has("Item1") || !pair.has("Item2"))
                throw new IllegalArgumentException("Invalid Niagara GPU generated function specifier");
            if (SourceNames.equal(pair.get("Item1").getAsString(), "Attribute"))
                return pair.get("Item2").getAsString();
        }
        return null;
    }

    private static byte[] integer(int value) { return integers(new int[] {value}); }
    private static byte[] integers(int[] values) {
        ByteBuffer out = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) out.putInt(value);
        return out.array();
    }
}
