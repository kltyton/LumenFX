package com.kltyton.lumenfx.client.render.niagara;

import com.kltyton.lumenfx.effect.render.SpriteCutout;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;

/** Immutable cutout UVs and reusable four/eight-vertex triangle indices. */
public final class SpriteCutoutBuffers implements AutoCloseable {
    private final GpuBuffer coordinates, indices, info;

    public SpriteCutoutBuffers(CommandEncoder encoder, SpriteCutout cutout) {
        RenderSystem.assertOnRenderThread();
        GpuBuffer positions = null, triangles = null, parameters = null;
        try {
            float[] values = cutout.coordinates();
            long positionBytes = Math.multiplyExact((long)values.length, Float.BYTES);
            positions = RenderSystem.getDevice().createBuffer(() -> "Niagara sprite cutout UVs",
                    GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, positionBytes);
            GpuBufferSlice positionUpload;
            try (var mapped = encoder.transientMemory().allocateGpuMapped(positionBytes, 4, GpuBuffer.USAGE_COPY_SRC)) {
                mapped.data().order(ByteOrder.nativeOrder()).asFloatBuffer().put(values);
                positionUpload = mapped.slice();
            }
            encoder.copyToBuffer(positionUpload, positions.slice());
            int[] indexValues = cutout.verticesPerFrame() == 4 ? new int[]{0, 2, 3, 0, 1, 2}
                    : new int[]{0, 1, 2, 0, 2, 3, 0, 3, 4, 0, 4, 5, 0, 5, 6, 0, 6, 7};
            long indexBytes = (long)indexValues.length * Integer.BYTES;
            triangles = RenderSystem.getDevice().createBuffer(() -> "Niagara sprite cutout indices",
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, indexBytes);
            GpuBufferSlice indexUpload;
            try (var mapped = encoder.transientMemory().allocateGpuMapped(indexBytes, 4, GpuBuffer.USAGE_COPY_SRC)) {
                mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer().put(indexValues);
                indexUpload = mapped.slice();
            }
            encoder.copyToBuffer(indexUpload, triangles.slice());
            parameters = RenderSystem.getDevice().createBuffer(() -> "Niagara sprite cutout parameters",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
            GpuBufferSlice parameterUpload;
            try (var mapped = encoder.transientMemory().allocateGpuMapped(16, 4, GpuBuffer.USAGE_COPY_SRC)) {
                mapped.data().order(ByteOrder.nativeOrder()).putInt(cutout.verticesPerFrame()).putInt(cutout.frames()).putInt(0).putInt(0);
                parameterUpload = mapped.slice();
            }
            encoder.copyToBuffer(parameterUpload, parameters.slice());
            coordinates = positions; indices = triangles; info = parameters;
        } catch (RuntimeException | Error error) {
            if (parameters != null) parameters.close();
            if (triangles != null) triangles.close();
            if (positions != null) positions.close();
            throw error;
        }
    }

    public GpuBuffer coordinates() { return coordinates; }
    public GpuBuffer indices() { return indices; }
    public GpuBuffer info() { return info; }

    @Override public void close() { info.close(); indices.close(); coordinates.close(); }
}
