package com.kltyton.lumenfx.client.render.niagara.gpu.channel;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelGpuSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelState.GpuWriter;
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

/** Binds append buffers for GPU-visible data and the independent CPU or game publication. */
public final class NiagaraGpuDataChannelWriteBindings implements AutoCloseable {
    private record Resource(Binding binding, String member) { }
    private static final class Binding {
        final String key, original;
        final List<Variable> parameters;
        @Nullable Selected selected;
        @Nullable Channel schema;
        @Nullable GpuBuffer offsets;
        @Nullable NiagaraGpuDataChannelColumns gpu, cpu;
        Binding(String key, String original, List<Variable> parameters) {
            this.key = key; this.original = original; this.parameters = parameters;
        }
    }
    private record Selected(String identity, GpuWriter writer) { }

    private final NiagaraGpuPrograms programs;
    private final Map<String, Binding> bindings;
    private final Map<String, Resource> resources;
    private @Nullable NiagaraGpuDataChannelWriters active;

    public NiagaraGpuDataChannelWriteBindings(NiagaraGpuPrograms programs, JsonObject program) {
        this.programs = programs;
        var symbols = new HashMap<String, Binding>();
        var storage = new HashMap<String, Resource>();
        for (var element : program.getAsJsonArray("interfaces")) {
            var data = element.getAsJsonObject();
            if (!data.get("class").getAsString().equals("NiagaraDataInterfaceDataChannelWrite") || !data.has("shader_symbol")) continue;
            String symbol = data.get("shader_symbol").getAsString();
            var binding = new Binding(SourceNames.canonical(data.get("compile_name").getAsString()), data.get("interface_id").getAsString(),
                    NiagaraDataChannelDefinition.variables(data.getAsJsonArray("data_channel_gpu_parameters")));
            if (symbols.putIfAbsent(symbol, binding) != null) throw new IllegalArgumentException("Duplicate Niagara data channel symbol " + symbol);
            for (String member : List.of("ParamOffsetTable", "GPUBufferFloat", "GPUBufferInt32", "CPUBufferFloat", "CPUBufferInt32",
                    "NDCGpuCount", "NDCCpuCount")) storage.put(symbol + "_" + member, new Resource(binding, member));
        }
        bindings = Map.copyOf(symbols);
        resources = Map.copyOf(storage);
    }

    public void beginTick(@Nullable NiagaraGpuDataChannelWriters registry, Map<String, String> selections, Map<String, GpuWriter> snapshots) {
        if (bindings.isEmpty()) return;
        active = Objects.requireNonNull(registry, "Niagara GPU data channel writer registry is unavailable");
        for (var binding : bindings.values()) {
            String identity = selections.getOrDefault(binding.key, binding.original);
            var writer = Objects.requireNonNull(snapshots.get(identity), "Data Channel Writer was not published for the GPU step: " + identity);
            registry.reserve(writer);
            binding.selected = new Selected(identity, writer);
        }
    }

    public void beginStage(String symbol, NiagaraGpuDataChannelWorlds.StageAccess access) {
        var binding = Objects.requireNonNull(bindings.get(symbol));
        var selected = Objects.requireNonNull(binding.selected);
        var writer = selected.writer();
        binding.cpu = Objects.requireNonNull(active).cpuColumns(selected.identity(), writer);
        binding.gpu = writer.definition().publishGpu() ? access.acquire(writer.world(), writer.address(), true, true) : null;
    }

    public void bind(CommandEncoder encoder, ShaderProgram shader, ComputeBindings.Builder targets) {
        if (resources.isEmpty()) return;
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer storage) {
            var resource = resources.get(storage.name());
            if (resource == null) continue;
            var binding = resource.binding();
            var selected = Objects.requireNonNull(binding.selected);
            var writer = selected.writer();
            var schema = writer.definition().channel();
            String member = resource.member();
            var buffer = switch (member) {
                case "ParamOffsetTable" -> offsets(encoder, binding, schema, storage.name());
                case "GPUBufferFloat" -> binding.gpu == null ? programs.dummyBuffer() : binding.gpu.floats();
                case "GPUBufferInt32" -> binding.gpu == null ? programs.dummyBuffer() : binding.gpu.integers();
                case "NDCGpuCount" -> binding.gpu == null ? programs.dummyBuffer() : binding.gpu.count();
                case "CPUBufferFloat" -> binding.cpu == null ? programs.dummyBuffer() : binding.cpu.floats();
                case "CPUBufferInt32" -> binding.cpu == null ? programs.dummyBuffer() : binding.cpu.integers();
                case "NDCCpuCount" -> binding.cpu == null ? programs.dummyBuffer() : binding.cpu.count();
                default -> throw new IllegalStateException("Unknown Niagara GPU data channel writer resource " + member);
            };
            targets.storage(storage.name(), buffer.slice(), storage.access());
        }
    }

    private GpuBuffer offsets(CommandEncoder encoder, Binding binding, @Nullable Channel schema, String name) {
        if (schema == null) return programs.dummyBuffer();
        var previous = binding.offsets;
        if (previous != null && schema.equals(binding.schema)) return previous;
        int[] words = NiagaraDataChannelGpuSnapshot.empty(schema).parameterOffsets(binding.parameters);
        var next = NiagaraGpuDataChannelReaders.upload(programs, encoder, previous, name, (long)words.length * Integer.BYTES,
                bytes -> { for (int word : words) bytes.putInt(word); });
        binding.schema = schema;
        binding.offsets = next;
        return next;
    }

    public @Nullable byte[] value(String name) {
        int member = name.lastIndexOf('.') + 1, separator = name.lastIndexOf('_');
        if (separator <= member || bindings.isEmpty()) return null;
        var binding = bindings.get(name.substring(member, separator));
        if (binding == null || binding.selected == null) return null;
        int gpu = binding.gpu == null ? -1 : binding.gpu.capacity();
        int cpu = binding.cpu == null ? -1 : binding.cpu.capacity();
        int value = switch (name.substring(separator + 1)) {
            case "ParameterOffsetTableIndex" -> gpu >= 0 || cpu >= 0 ? 0 : -1;
            case "FloatStride", "Int32Stride" -> Math.max(0, gpu);
            case "CPUFloatStride", "CPUInt32Stride" -> Math.max(0, cpu);
            case "GPUBufferSize" -> gpu;
            case "CPUBufferSize" -> cpu;
            case "GPUInstanceCountOffset" -> gpu < 0 ? -1 : 0;
            case "CPUInstanceCountOffset" -> cpu < 0 ? -1 : 1;
            default -> Integer.MIN_VALUE;
        };
        return value == Integer.MIN_VALUE ? null : ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    public void endStage() { for (var binding : bindings.values()) { binding.gpu = null; binding.cpu = null; } }
    public void clearSnapshots() {
        endStage();
        for (var binding : bindings.values()) binding.selected = null;
        active = null;
    }

    @Override public void close() {
        clearSnapshots();
        for (var binding : bindings.values()) {
            if (binding.offsets != null) binding.offsets.close();
            binding.offsets = null;
        }
    }
}
