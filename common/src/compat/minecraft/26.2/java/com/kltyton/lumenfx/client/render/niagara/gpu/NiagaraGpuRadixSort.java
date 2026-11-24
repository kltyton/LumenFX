package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Unreal's four-stage radix sort for GPU particle keys and values. */
public final class NiagaraGpuRadixSort implements AutoCloseable {
    private static final int THREADS = 128;
    private static final int TILE_SIZE = THREADS * 8;
    private static final int MAX_GROUPS = 64;
    private static final int OFFSET_COUNT = 16 * MAX_GROUPS;
    private final NiagaraGpuPrograms programs;
    private final String name;
    private final GpuBuffer[] keys = new GpuBuffer[2];
    private final GpuBuffer[] values = new GpuBuffer[2];
    private final GpuBuffer[] offsets = new GpuBuffer[2];
    private int capacity;
    private int count;
    private boolean closed;

    public NiagaraGpuRadixSort(String name, NiagaraGpuPrograms programs) {
        RenderSystem.assertOnRenderThread();
        this.name = name;
        this.programs = programs;
        try {
            for (int i = 0; i < 2; i++) {
                String label = name + ".RadixOffsets" + i;
                offsets[i] = RenderSystem.getDevice().createBuffer(() -> label,
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST),
                        (long) OFFSET_COUNT * Integer.BYTES);
            }
        } catch (RuntimeException | Error error) {
            for (GpuBuffer buffer : offsets) if (buffer != null) buffer.close();
            throw error;
        }
    }

    public void prepare(int particleCount) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU radix sort is closed");
        if (particleCount < 0) throw new IllegalArgumentException("Negative Niagara sort count");
        count = particleCount;
        int required = Math.max(particleCount, 1);
        if (required <= capacity) return;
        long bytes = Math.multiplyExact((long) required, Integer.BYTES);
        long maximum = Math.min(programs.runtime().maxStorageBufferBytes(),
                programs.runtime().maxTexelBufferElements() * Integer.BYTES);
        if (bytes > maximum) throw new IllegalArgumentException("Niagara GPU sort buffer exceeds device limit");
        GpuBuffer[] newKeys = new GpuBuffer[2], newValues = new GpuBuffer[2];
        try {
            for (int i = 0; i < 2; i++) {
                String keyLabel = name + ".RadixKeys" + i, valueLabel = name + ".RadixIndices" + i;
                newKeys[i] = RenderSystem.getDevice().createBuffer(() -> keyLabel,
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST), bytes);
                newValues[i] = RenderSystem.getDevice().createBuffer(() -> valueLabel,
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST
                                | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER), bytes);
            }
        } catch (RuntimeException | Error error) {
            for (GpuBuffer buffer : newKeys) if (buffer != null) buffer.close();
            for (GpuBuffer buffer : newValues) if (buffer != null) buffer.close();
            throw error;
        }
        for (int i = 0; i < 2; i++) {
            if (keys[i] != null) keys[i].close();
            if (values[i] != null) values[i].close();
            keys[i] = newKeys[i];
            values[i] = newValues[i];
        }
        capacity = required;
    }

    public GpuBuffer inputKeys() { requirePrepared(); return keys[0]; }
    public GpuBuffer inputIndices() { requirePrepared(); return values[0]; }

    public GpuBuffer sort(CommandEncoder encoder, int keyMask) {
        RenderSystem.assertOnRenderThread();
        requirePrepared();
        if (count <= 1 || keyMask == 0) return values[0];
        int tiles = count / TILE_SIZE;
        int groups = Math.max(1, Math.min(MAX_GROUPS, tiles));
        int tilesPerGroup = tiles / groups, extraTiles = tiles % groups, extraKeys = count % TILE_SIZE;
        if (groups > programs.runtime().maxWorkGroups(0))
            throw new IllegalArgumentException("Niagara radix dispatch exceeds device limit");
        int current = 0;
        for (int shift = 0; shift < Integer.SIZE; shift += 4) {
            if ((keyMask & (0xf << shift)) == 0) continue;
            dispatch(encoder, "clear", current, shift, groups, tilesPerGroup, extraTiles, extraKeys, 1, THREADS);
            dispatch(encoder, "upsweep", current, shift, groups, tilesPerGroup, extraTiles, extraKeys, groups, THREADS);
            dispatch(encoder, "spine", current, shift, groups, tilesPerGroup, extraTiles, extraKeys, 1, 64);
            dispatch(encoder, "downsweep", current, shift, groups, tilesPerGroup, extraTiles, extraKeys, groups, THREADS);
            current ^= 1;
        }
        return values[current];
    }

    private void dispatch(CommandEncoder encoder, String pass, int current, int shift, int groupCount,
                          int tilesPerGroup, int extraTiles, int extraKeys, int groups, int threads) {
        var shader = programs.radix(pass);
        ComputeBindings.Builder bindings = ComputeBindings.builder();
        for (ShaderBinding binding : shader.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBuffer buffer = switch (storage.name()) {
                case "InKeys" -> keys[current];
                case "InValues" -> values[current];
                case "InOffsets" -> offsets[pass.equals("spine") ? 0 : 1];
                case "OutOffsets" -> offsets[pass.equals("spine") ? 1 : 0];
                case "OutKeys" -> keys[current ^ 1];
                case "OutValues" -> values[current ^ 1];
                default -> throw new IllegalArgumentException("Unknown Niagara radix buffer " + storage.name());
            };
            bindings.storage(storage.name(), buffer.slice(), storage.access());
        }
        shader.dispatch(encoder, bindings, name -> switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "RadixShift" -> word(shift);
            case "TilesPerGroup" -> word(tilesPerGroup);
            case "ExtraTileCount" -> word(extraTiles);
            case "ExtraKeyCount" -> word(extraKeys);
            case "GroupCount" -> word(groupCount);
            default -> null;
        }, Math.multiplyExact(groups, threads), 1, 1);
    }

    private static byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private void requirePrepared() {
        if (closed || keys[0] == null) throw new IllegalStateException("Niagara GPU radix sort has not been prepared");
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        for (GpuBuffer buffer : keys) if (buffer != null) buffer.close();
        for (GpuBuffer buffer : values) if (buffer != null) buffer.close();
        for (GpuBuffer buffer : offsets) if (buffer != null) buffer.close();
    }
}
