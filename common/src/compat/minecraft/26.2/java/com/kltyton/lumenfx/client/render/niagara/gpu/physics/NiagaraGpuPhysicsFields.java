package com.kltyton.lumenfx.client.render.niagara.gpu.physics;

import com.kltyton.lumenfx.api.client.physics.PhysicsFieldGpuData;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldGpuData.Buffer;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.Clipmap;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Shares the active Chaos GPU publication between Niagara emitters and systems. */
public final class NiagaraGpuPhysicsFields implements AutoCloseable {
    private final EnumMap<Buffer, GpuBuffer> buffers = new EnumMap<>(Buffer.class);
    private @Nullable PhysicsFieldGpuData publication;
    private @Nullable GpuBuffer generatedClipmap;
    private @Nullable Clipmap lastGeometry;
    private float lastTime;

    @Nullable GpuBuffer buffer(PhysicsFieldGpuData data, Buffer kind, ComputeRuntime runtime) {
        select(data);
        int bytes = data.bytes(kind);
        if (bytes == 0) return null;
        return buffers.computeIfAbsent(kind, ignored -> upload(data, kind, bytes, runtime));
    }

    GpuBuffer clipmap(CommandEncoder encoder, NiagaraGpuPrograms programs, NiagaraPhysicsField field, PhysicsFieldGpuData data) {
        select(data);
        Clipmap geometry = Objects.requireNonNull(field.clipmap());
        if (generatedClipmap != null && geometry.equals(lastGeometry) && lastTime == field.worldSeconds()) return generatedClipmap;
        var runtime = programs.runtime();
        long resolution = geometry.resolution();
        long cells = Math.multiplyExact(Math.multiplyExact(resolution, resolution), resolution);
        long bytes = Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(cells, data.levels()), data.attributes()), Float.BYTES);
        if (bytes > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Chaos clipmap exceeds the device storage limit: " + bytes);
        if (generatedClipmap == null || generatedClipmap.size() != bytes) {
            var previous = generatedClipmap;
            generatedClipmap = RenderSystem.getDevice().createBuffer(() -> "Niagara.PhysicsField.GeneratedClipmap",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes);
            if (previous != null) RenderSystem.queueFencedTask(previous::close);
        }
        lastGeometry = null;
        NiagaraPhysicsClipmapBuild.run(encoder, programs, field, data, generatedClipmap,
                Objects.requireNonNull(buffer(data, Buffer.PARAMETERS, runtime)),
                Objects.requireNonNull(buffer(data, Buffer.NODES, runtime)),
                Objects.requireNonNull(buffer(data, Buffer.TARGETS, runtime)));
        lastGeometry = geometry;
        lastTime = field.worldSeconds();
        return generatedClipmap;
    }

    private void select(PhysicsFieldGpuData data) {
        if (publication != data) {
            close();
            publication = data;
        }
    }

    private static GpuBuffer upload(PhysicsFieldGpuData source, Buffer kind, int bytes, ComputeRuntime runtime) {
        if (bytes > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Chaos field buffer exceeds the storage buffer limit: " + bytes);
        var data = MemoryUtil.memAlloc(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            source.write(kind, data);
            data.flip();
            return RenderSystem.getDevice().createBuffer(() -> "Niagara.PhysicsField." + kind,
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    @Override public void close() {
        buffers.values().forEach(buffer -> RenderSystem.queueFencedTask(buffer::close));
        buffers.clear();
        var clipmap = generatedClipmap;
        if (clipmap != null) RenderSystem.queueFencedTask(clipmap::close);
        generatedClipmap = null;
        lastGeometry = null;
        publication = null;
    }
}
