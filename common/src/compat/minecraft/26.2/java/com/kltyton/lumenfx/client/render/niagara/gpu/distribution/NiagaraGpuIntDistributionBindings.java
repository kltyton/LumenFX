package com.kltyton.lumenfx.client.render.niagara.gpu.distribution;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution.NiagaraIntDistribution;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Binds the original read-only distribution byte-address table and Length for each queued simulation step. */
public final class NiagaraGpuIntDistributionBindings implements AutoCloseable {
    private static final String TABLE = "_BuiltTableData";
    private static final String LENGTH = "_Length";
    private record Binding(String name, String defaultIdentity) { }
    private final ComputeRuntime runtime;
    private final Map<String, Binding> symbols;
    private final Map<String, NiagaraIntDistribution> active = new HashMap<>();
    private final Map<NiagaraIntDistribution, GpuBuffer> uploads = new IdentityHashMap<>();

    public NiagaraGpuIntDistributionBindings(ComputeRuntime runtime, JsonObject program) {
        this.runtime = runtime;
        var bindings = new HashMap<String, Binding>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var value = item.getAsJsonObject();
            if (!value.has("int_distribution") || !value.has("shader_symbol")) continue;
            bindings.put(value.get("shader_symbol").getAsString(),
                    new Binding(SourceNames.canonical(value.get("compile_name").getAsString()), value.get("interface_id").getAsString()));
        }
        symbols = Map.copyOf(bindings);
    }

    public void beginTick(Map<String, String> selections, Map<String, NiagaraIntDistribution> snapshots) {
        if (symbols.isEmpty()) return;
        var used = new HashSet<NiagaraIntDistribution>();
        symbols.forEach((symbol, binding) -> {
            String id = selections.getOrDefault(binding.name(), binding.defaultIdentity());
            var table = Objects.requireNonNull(snapshots.get(id), "Missing Niagara integer distribution step " + id);
            active.put(symbol, table);
            used.add(table);
        });
        uploads.entrySet().removeIf(entry -> {
            if (used.contains(entry.getKey())) return false;
            RenderSystem.queueFencedTask(entry.getValue()::close);
            return true;
        });
    }

    public @Nullable byte[] value(String qualified) {
        if (!qualified.endsWith(LENGTH)) return null;
        String name = qualified.substring(qualified.lastIndexOf('.') + 1, qualified.length() - LENGTH.length());
        var table = active.get(name);
        return table == null ? null : ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(table.length()).array();
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (active.isEmpty()) return;
        for (var binding : shader.bindings()) if (binding instanceof ShaderBinding.StorageBuffer storage) {
            String name = storage.name();
            if (!name.endsWith(TABLE)) continue;
            var table = active.get(name.substring(0, name.length() - TABLE.length()));
            if (table != null) resources.storage(name, uploads.computeIfAbsent(table, this::upload).slice(), storage.access());
        }
    }

    private GpuBuffer upload(NiagaraIntDistribution table) {
        int bytes = table.byteSize();
        if (bytes > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara integer distribution exceeds the storage buffer limit: " + bytes);
        var data = MemoryUtil.memAlloc(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            table.write(data);
            data.flip();
            return RenderSystem.getDevice().createBuffer(() -> "Niagara.IntDistribution", ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    public void clearSnapshots() { active.clear(); }

    @Override public void close() {
        active.clear();
        for (var buffer : uploads.values()) RenderSystem.queueFencedTask(buffer::close);
        uploads.clear();
    }
}
