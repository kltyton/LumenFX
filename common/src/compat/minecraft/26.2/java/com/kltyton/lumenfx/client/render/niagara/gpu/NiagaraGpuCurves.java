package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraCurves;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** GPU LUT resources and default constants for the emitter's serialized curve interfaces. */
public final class NiagaraGpuCurves implements AutoCloseable {
    private final Map<String, GpuBuffer> buffers;
    private final Map<String, byte[]> parameters;
    private final Set<String> bindingNames;
    private boolean closed;

    public NiagaraGpuCurves(String name, PreparedNiagaraCurves prepared, ComputeRuntime runtime) {
        RenderSystem.assertOnRenderThread();
        Map<String, GpuBuffer> uploaded = new LinkedHashMap<>();
        Map<String, byte[]> values = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        try {
            names.add("StaticInputFloat");
            int[] staticWords = prepared.staticWords();
            if (staticWords.length > 0)
                uploaded.put("StaticInputFloat", upload(name + ".StaticFloat", staticWords, runtime));
            for (var binding : prepared.bindings()) {
                names.add(binding.unique() ? binding.symbol() + "_CurveLUT" : "StaticInputFloat");
                var curve = binding.defaultCurve();
                if (curve == null) continue;
                String symbol = binding.symbol();
                values.put(symbol + "_MinTime", word(curve.minimumBits()));
                values.put(symbol + "_MaxTime", word(curve.maximumBits()));
                values.put(symbol + "_InvTimeRange", word(curve.inverseRangeBits()));
                values.put(symbol + "_CurveLUTNumMinusOne", word(curve.samplesMinusOne()));
                values.put(symbol + "_LUTOffset", word(binding.staticOffset()));
                if (binding.unique())
                    uploaded.put(symbol + "_CurveLUT", upload(name + "." + symbol, curve.words(), runtime));
            }
            buffers = Map.copyOf(uploaded);
            parameters = Map.copyOf(values);
            bindingNames = Set.copyOf(names);
        } catch (RuntimeException | Error error) {
            uploaded.values().forEach(GpuBuffer::close);
            throw error;
        }
    }

    public @Nullable byte[] value(String name) {
        if (closed) throw new IllegalStateException("Niagara GPU curves are closed");
        byte[] value = parameters.get(name);
        return value == null ? null : value.clone();
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder bindings, Map<String, GpuBufferSlice> overrides,
                     GpuBufferSlice dummyInput) {
        if (closed) throw new IllegalStateException("Niagara GPU curves are closed");
        for (ShaderBinding entry : shader.bindings()) {
            if (!(entry instanceof ShaderBinding.StorageBuffer storage)) continue;
            if (!bindingNames.contains(storage.name())) continue;
            GpuBufferSlice override = overrides.get(storage.name());
            GpuBuffer buffer = buffers.get(storage.name());
            if (override != null) bindings.storage(storage.name(), override, storage.access());
            else if (buffer != null) bindings.storage(storage.name(), buffer.slice(), storage.access());
            else if (storage.name().equals("StaticInputFloat")) bindings.storage(storage.name(), dummyInput, storage.access());
        }
    }

    static GpuBuffer upload(String name, int[] words, ComputeRuntime runtime) {
        long bytes = Math.multiplyExact((long) words.length, Integer.BYTES);
        if (bytes == 0 || bytes > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara curve buffer exceeds the device limit: " + name);
        ByteBuffer data = MemoryUtil.memAlloc(Math.toIntExact(bytes)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            for (int word : words) data.putInt(word);
            data.flip();
            return RenderSystem.getDevice().createBuffer(() -> name,
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        buffers.values().forEach(GpuBuffer::close);
    }
}
