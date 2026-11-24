package com.kltyton.lumenfx.client.render.niagara.gpu.cache;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraSimCacheReader.Bound;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraSimCacheReader.Operation;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Binds the cache selected by a queued simulation step to Niagara's generated GPU reader functions. */
public final class NiagaraGpuCacheReaderBindings implements AutoCloseable {
    private record Attribute(Operation operation, String name) { }
    private static final class Binding {
        final String key, original;
        final List<Attribute> functions;
        Bound selected = Bound.EMPTY;
        Bound offsetSource = Bound.EMPTY;
        @Nullable NiagaraSimulationCache uploaded;
        @Nullable GpuBuffer buffer;
        @Nullable byte[] offsets;
        Binding(String key, String original, List<Attribute> functions) {
            this.key = key; this.original = original; this.functions = List.copyOf(functions);
        }
    }
    private final NiagaraGpuPrograms programs;
    private final Map<String, Binding> symbols, storage;

    public NiagaraGpuCacheReaderBindings(NiagaraGpuPrograms programs, JsonObject source) {
        this.programs = programs;
        var all = new HashMap<String, Binding>();
        var buffers = new HashMap<String, Binding>();
        for (var element : source.getAsJsonArray("interfaces")) {
            var data = element.getAsJsonObject();
            if (!data.has("shader_symbol") || !data.get("class").getAsString().equals("NiagaraDataInterfaceSimCacheReader")) continue;
            String symbol = data.get("shader_symbol").getAsString();
            var functions = new ArrayList<Attribute>();
            for (var function : data.getAsJsonArray("sim_cache_gpu_functions")) {
                var value = function.getAsJsonObject();
                functions.add(new Attribute(Operation.valueOf(value.get("name").getAsString()), value.get("attribute").getAsString()));
            }
            var binding = new Binding(SourceNames.canonical(data.get("compile_name").getAsString()), data.get("interface_id").getAsString(), functions);
            if (all.putIfAbsent(symbol, binding) != null) throw new IllegalArgumentException("Duplicate Niagara cache reader shader symbol " + symbol);
            buffers.put(symbol + "_CacheData", binding);
        }
        symbols = Map.copyOf(all);
        storage = Map.copyOf(buffers);
    }

    public void beginTick(Map<String, String> selections, Map<String, Bound> snapshots) {
        for (var binding : symbols.values()) {
            String id = selections.getOrDefault(binding.key, binding.original);
            binding.selected = Objects.requireNonNull(snapshots.get(id), "GPU cache reader was not published: " + id);
        }
    }

    public @Nullable byte[] value(String name) {
        int member = name.lastIndexOf('.') + 1, separator = name.lastIndexOf('_');
        if (separator <= member) return null;
        var binding = symbols.get(name.substring(member, separator));
        if (binding == null) return null;
        var selected = binding.selected;
        var cache = selected.cache();
        return switch (name.substring(separator + 1)) {
            case "NumFrames" -> word(cache == null ? 0 : cache.frames().size());
            case "NumEmitters" -> word(cache == null ? 0 : cache.emitterLayouts().size());
            case "EmitterIndex" -> word(selected.emitter());
            case "ComponentOffsets" -> offsets(binding);
            default -> null;
        };
    }

    private static byte[] offsets(Binding binding) {
        if (binding.offsets != null && binding.offsetSource == binding.selected) return binding.offsets;
        var data = ByteBuffer.allocate(Math.ceilDiv(Math.max(binding.functions.size(), 1), 4) * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (var function : binding.functions)
            data.putInt(function.name().isEmpty() ? -1 : binding.selected.column(function.operation(), function.name()));
        binding.offsets = data.array();
        binding.offsetSource = binding.selected;
        return binding.offsets;
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer buffer) {
            var binding = storage.get(buffer.name());
            if (binding == null) continue;
            resources.storage(buffer.name(), upload(binding, buffer.name()).slice(), buffer.access());
        }
    }

    private GpuBuffer upload(Binding binding, String name) {
        var cache = binding.selected.cache();
        if (cache == null || cache.emitterLayouts().isEmpty()) return programs.dummyBuffer();
        if (binding.buffer != null && binding.uploaded == cache) return binding.buffer;
        long size = NiagaraGpuCacheData.bytes(cache);
        if (size > programs.runtime().maxStorageBufferBytes() || size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Niagara simulation cache exceeds the GPU storage capacity: " + name);
        var bytes = MemoryUtil.memAlloc((int)size).order(ByteOrder.nativeOrder());
        try {
            NiagaraGpuCacheData.write(bytes, cache);
            var replacement = RenderSystem.getDevice().createBuffer(() -> name, ComputeUsage.storageBuffer(0), bytes);
            if (binding.buffer != null) binding.buffer.close();
            binding.buffer = replacement;
            binding.uploaded = cache;
            return replacement;
        } finally { MemoryUtil.memFree(bytes); }
    }

    private static byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    public void clearSnapshots() { for (var binding : symbols.values()) binding.selected = Bound.EMPTY; }
    @Override public void close() {
        for (var binding : symbols.values()) {
            if (binding.buffer != null) binding.buffer.close();
            binding.buffer = null;
            binding.uploaded = null;
        }
    }
}
