package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraMeshInfo;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Generation-owned static mesh-information buffers shared by emitter instances. */
public final class NiagaraGpuMeshInfo implements AutoCloseable {
    private final Map<String, GpuBuffer> buffers;
    private final Map<String, byte[]> values;
    private final Set<String> names;
    private boolean closed;

    NiagaraGpuMeshInfo(String name, PreparedNiagaraMeshInfo prepared, ComputeRuntime runtime) {
        RenderSystem.assertOnRenderThread();
        var uploaded = new LinkedHashMap<String, GpuBuffer>();
        var constants = new LinkedHashMap<String, byte[]>();
        var bindings = new java.util.LinkedHashSet<String>();
        try {
            prepared.interfaces().forEach((symbol, info) -> {
                constants.put(symbol + "_bSubImageBlend", words(info.subImageBlend() ? 1 : 0));
                constants.put(symbol + "_SubImageSize", words(Float.floatToRawIntBits(info.columns()), Float.floatToRawIntBits(info.rows())));
                constants.put(symbol + "_NumMeshes", words(info.meshCount()));
                String binding = symbol + "_MeshDataBuffer";
                bindings.add(binding);
                float[] data = info.bounds();
                if (data.length > 0) uploaded.put(binding, upload(name + "." + symbol, data, runtime));
            });
            buffers = Map.copyOf(uploaded); values = Map.copyOf(constants); names = Set.copyOf(bindings);
        } catch (RuntimeException | Error error) {
            uploaded.values().forEach(GpuBuffer::close);
            throw error;
        }
    }

    public @Nullable byte[] value(String name) {
        if (closed) throw new IllegalStateException("Niagara GPU mesh information is closed");
        byte[] result = values.get(name);
        return result == null ? null : result.clone();
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder bindings, GpuBufferSlice dummy) {
        bind(shader, bindings, dummy, Map.of());
    }
    public void bind(ShaderProgram shader, ComputeBindings.Builder bindings, GpuBufferSlice dummy,
                     Map<String, GpuBufferSlice> overrides) {
        if (closed) throw new IllegalStateException("Niagara GPU mesh information is closed");
        for (var entry : shader.bindings()) {
            if (!(entry instanceof ShaderBinding.StorageBuffer storage) || !names.contains(storage.name())) continue;
            GpuBuffer buffer = buffers.get(storage.name());
            GpuBufferSlice selected = overrides.get(storage.name());
            bindings.storage(storage.name(), selected != null ? selected : buffer == null ? dummy : buffer.slice(), storage.access());
        }
    }

    static GpuBuffer upload(String name, float[] values, ComputeRuntime runtime) {
        long bytes = Math.multiplyExact((long)values.length, Float.BYTES);
        if (bytes > runtime.maxStorageBufferBytes()) throw new IllegalArgumentException("Niagara mesh information exceeds the device buffer limit: " + name);
        ByteBuffer data = MemoryUtil.memAlloc(Math.toIntExact(bytes)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            for (float value : values) data.putFloat(value);
            data.flip();
            return RenderSystem.getDevice().createBuffer(() -> name, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
        } finally { MemoryUtil.memFree(data); }
    }

    private static byte[] words(int... words) {
        ByteBuffer data = ByteBuffer.allocate(Math.multiplyExact(words.length, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : words) data.putInt(word);
        return data.array();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        buffers.values().forEach(GpuBuffer::close);
    }
}
