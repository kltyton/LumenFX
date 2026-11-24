package com.kltyton.lumenfx.client.render.niagara.gpu.channel;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelReaders.Member;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelGpuSnapshot;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Channel;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Variable;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Binds the current data channel publication to the source shader's variadic fields. */
public final class NiagaraGpuDataChannelReadBindings implements AutoCloseable {
    private record Storage(Binding binding, @Nullable Member member) { }
    private static final class Binding {
        final String key, original;
        final List<Variable> requested;
        @Nullable Channel schema;
        @Nullable GpuBuffer offsets;
        NiagaraGpuDataChannelReaders.@Nullable Reader reader;
        @Nullable NiagaraDataChannelGpuSnapshot snapshot;
        @Nullable Selected selected;
        Binding(String key, String original, List<Variable> requested) { this.key = key; this.original = original; this.requested = requested; }
    }
    private record Selected(NiagaraGpuDataChannelReaders.Reader reader, NiagaraDataChannelGpuSnapshot snapshot,
                            @Nullable NiagaraGpuDataChannelColumns worldColumns, boolean worldFrame) {
        boolean hasDataSource() { return worldFrame ? worldColumns != null : snapshot.bound(); }
        int stride() { return worldFrame ? worldColumns == null ? 0 : worldColumns.capacity() : snapshot.rows(); }
    }

    private final NiagaraGpuPrograms programs;
    private final Map<String, Binding> bindings;
    private final Map<String, Storage> storage;

    public NiagaraGpuDataChannelReadBindings(NiagaraGpuPrograms programs, JsonObject program) {
        this.programs = programs;
        var symbols = new HashMap<String, Binding>();
        var resources = new HashMap<String, Storage>();
        for (var value : program.getAsJsonArray("interfaces")) {
            var data = value.getAsJsonObject();
            if (!data.get("class").getAsString().equals("NiagaraDataInterfaceDataChannelRead") || !data.has("shader_symbol")) continue;
            String symbol = data.get("shader_symbol").getAsString();
            var variables = NiagaraDataChannelDefinition.variables(data.getAsJsonArray("data_channel_gpu_parameters"));
            var binding = new Binding(SourceNames.canonical(data.get("compile_name").getAsString()), data.get("interface_id").getAsString(), variables);
            if (symbols.putIfAbsent(symbol, binding) != null) throw new IllegalArgumentException("Duplicate Niagara data channel symbol " + symbol);
            resources.put(symbol + "_ParamOffsetTable", new Storage(binding, null));
            for (Member member : Member.values()) resources.put(symbol + "_" + member.shaderName, new Storage(binding, member));
        }
        bindings = Map.copyOf(symbols);
        storage = Map.copyOf(resources);
    }

    public void beginTick(@Nullable NiagaraGpuDataChannelReaders registry, Map<String, String> selections,
                          Map<String, NiagaraDataChannelGpuSnapshot> snapshots) {
        if (bindings.isEmpty()) return;
        var readers = Objects.requireNonNull(registry, "Niagara GPU data channel reader registry is unavailable");
        for (var binding : bindings.values()) {
            String identity = selections.getOrDefault(binding.key, binding.original);
            var snapshot = Objects.requireNonNull(snapshots.get(identity), "Data Channel Reader was not published for the GPU step: " + identity);
            binding.reader = readers.reader(identity);
            binding.snapshot = snapshot;
        }
    }

    public void beginStage(String symbol, NiagaraGpuDataChannelWorlds.StageAccess access) {
        var binding = Objects.requireNonNull(bindings.get(symbol));
        var snapshot = Objects.requireNonNull(binding.snapshot);
        var columns = access.acquire(snapshot.world(), snapshot.address(), snapshot.currentFrame(), false);
        // A CPU publication can close after the preceding GPU frame has already run.
        // Its captured rows still back this step's previous-frame spawn indices.
        boolean useCapturedPrevious = !snapshot.currentFrame() && columns == null && snapshot.rows() != 0;
        binding.selected = new Selected(Objects.requireNonNull(binding.reader), snapshot, columns,
                !useCapturedPrevious && NiagaraGpuDataChannelWorlds.hasFrame(snapshot.world(), snapshot.address()));
    }

    public void bind(CommandEncoder encoder, ShaderProgram shader, ComputeBindings.Builder resources) {
        if (storage.isEmpty()) return;
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer target) {
            var binding = storage.get(target.name());
            if (binding == null) continue;
            var selected = Objects.requireNonNull(binding.binding().selected);
            var snapshot = selected.snapshot();
            var member = binding.member();
            var buffer = member == null ? offsets(encoder, binding.binding(), snapshot, target.name())
                    : bindMember(encoder, selected, member);
            resources.storage(target.name(), buffer.slice(), target.access());
        }
    }

    private GpuBuffer bindMember(CommandEncoder encoder, Selected selected, Member member) {
        if (!selected.worldFrame() || member == Member.SPAWNS || member == Member.CONSUME)
            return selected.reader().bind(encoder, member, selected.snapshot());
        var columns = selected.worldColumns();
        if (columns == null) return programs.dummyBuffer();
        return switch (member) {
            case FLOATS -> columns.floats();
            case INTEGERS -> columns.integers();
            case COUNT -> columns.count();
            default -> throw new IllegalStateException("Unknown Niagara data channel column binding");
        };
    }

    private GpuBuffer offsets(CommandEncoder encoder, Binding binding, NiagaraDataChannelGpuSnapshot snapshot, String name) {
        var buffer = binding.offsets;
        if (buffer != null && Objects.equals(binding.schema, snapshot.schema())) return buffer;
        int[] words = snapshot.parameterOffsets(binding.requested);
        var next = NiagaraGpuDataChannelReaders.upload(programs, encoder, buffer, name, (long)words.length * Integer.BYTES,
                bytes -> { for (int word : words) bytes.putInt(word); });
        binding.schema = snapshot.schema();
        binding.offsets = next;
        return next;
    }

    public @Nullable byte[] value(String name) {
        int member = name.lastIndexOf('.') + 1, separator = name.lastIndexOf('_');
        if (separator <= member || bindings.isEmpty()) return null;
        var binding = bindings.get(name.substring(member, separator));
        if (binding == null || binding.selected == null) return null;
        var selected = binding.selected;
        var snapshot = selected.snapshot();
        int value = switch (name.substring(separator + 1)) {
            case "ParameterOffsetTableIndex" -> snapshot.bound() ? 0 : -1;
            case "InstanceCountOffset", "ConsumeInstanceCountOffset" -> selected.hasDataSource() ? 0 : -1;
            case "FloatStride", "Int32Stride", "BufferSize" -> selected.stride();
            case "NDCElementCountAtSpawn" -> snapshot.elementCountAtSpawn();
            case "NDCEmitterCount" -> snapshot.emitterCount();
            default -> Integer.MIN_VALUE;
        };
        return value == Integer.MIN_VALUE ? null : ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    public void endStage() { for (var binding : bindings.values()) binding.selected = null; }

    public void clearSnapshots() {
        endStage();
        for (var binding : bindings.values()) {
            binding.reader = null;
            binding.snapshot = null;
        }
    }

    @Override public void close() {
        clearSnapshots();
        for (var binding : bindings.values()) {
            if (binding.offsets != null) binding.offsets.close();
            binding.offsets = null;
        }
    }
}
