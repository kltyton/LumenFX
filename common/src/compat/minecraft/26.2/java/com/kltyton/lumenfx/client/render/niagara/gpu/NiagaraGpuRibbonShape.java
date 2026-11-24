package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.kltyton.lumenfx.effect.render.RibbonShapeGeometry;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/** Slice vertices and triangle edges for Niagara's plane, multi-plane, tube and custom ribbons. */
public final class NiagaraGpuRibbonShape implements AutoCloseable {
    public record Vertex(float x, float y, float normalX, float normalY, float textureV) { }
    private final GpuBuffer vertices, triangleVertices;
    private final int vertexCount, edgeCount, trianglesPerSegment, shapeBits, shapeMask;
    private final boolean flipNormalToView;
    private boolean closed;

    private NiagaraGpuRibbonShape(String name, List<Vertex> slice, List<Integer> edges, boolean flipNormalToView) {
        RenderSystem.assertOnRenderThread();
        vertexCount = slice.size(); edgeCount = edges.size(); trianglesPerSegment = edgeCount;
        shapeBits = bits(vertexCount); shapeMask = mask(shapeBits); this.flipNormalToView = flipNormalToView;
        GpuBuffer vertexBuffer = null, edgeBuffer = null;
        ByteBuffer vertexData = null, edgeData = null;
        try {
            vertexData = MemoryUtil.memAlloc(Math.multiplyExact(vertexCount, 5 * Float.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
            edgeData = MemoryUtil.memAlloc(Math.multiplyExact(edgeCount, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
            for (Vertex v : slice) vertexData.putFloat(v.x()).putFloat(v.y()).putFloat(v.normalX()).putFloat(v.normalY()).putFloat(v.textureV());
            for (int edge : edges) edgeData.putInt(edge);
            vertexData.flip(); edgeData.flip();
            vertexBuffer = RenderSystem.getDevice().createBuffer(() -> name + ".RibbonSlice",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER), vertexData);
            edgeBuffer = RenderSystem.getDevice().createBuffer(() -> name + ".RibbonEdges",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER), edgeData);
            vertices = vertexBuffer; triangleVertices = edgeBuffer;
        } catch (RuntimeException | Error error) {
            if (edgeBuffer != null) edgeBuffer.close();
            if (vertexBuffer != null) vertexBuffer.close();
            throw error;
        } finally {
            if (edgeData != null) MemoryUtil.memFree(edgeData);
            if (vertexData != null) MemoryUtil.memFree(vertexData);
        }
    }

    public static NiagaraGpuRibbonShape fromSettings(String name, RibbonSettings.Shape shape) {
        RibbonShapeGeometry geometry = RibbonShapeGeometry.from(shape);
        List<Vertex> vertices = geometry.vertices().stream()
                .map(v -> new Vertex(v.x(), v.y(), v.normalX(), v.normalY(), v.textureV())).toList();
        return new NiagaraGpuRibbonShape(name, vertices, geometry.edges(), geometry.flipNormalToView());
    }

    public NiagaraGpuRibbonIndices.Shape indexShape(int maxSegments, int maxSubSegments) {
        if (closed || maxSegments < 1 || maxSubSegments < 1) throw new IllegalArgumentException("Invalid ribbon shape bounds");
        int segmentBits = bits(maxSegments), subBits = bits(maxSubSegments);
        if (segmentBits + subBits + shapeBits > 32) throw new IllegalArgumentException("Ribbon index fields exceed 32 bits");
        return new NiagaraGpuRibbonIndices.Shape(triangleVertices, edgeCount, trianglesPerSegment, vertexCount,
                shapeBits, shapeMask, subBits + shapeBits, mask(segmentBits), shapeBits, mask(subBits));
    }

    public GpuBuffer vertices() { if (closed) throw new IllegalStateException("Ribbon shape is closed"); return vertices; }
    public boolean flipNormalToView() { return flipNormalToView; }
    private static int bits(int range) { return range <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(range - 1); }
    private static int mask(int bits) { return bits == 32 ? -1 : bits == 0 ? 0 : (1 << bits) - 1; }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true; triangleVertices.close(); vertices.close();
    }
}
