package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraNaniteShaders;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/** Decodes only the resident triangles eligible for the current raster pass. */
final class NaniteShadingGeometry implements AutoCloseable {
    private static final int WORKGROUP = 64;
    private static final int TRIANGLES_PER_CLUSTER = 128;
    private static final int VECTORS_PER_TRIANGLE = 27;

    record Output(GpuBufferSlice lookup, GpuBufferSlice primitives, GpuBufferSlice vertices) { }

    private final ComputeRuntime runtime;
    private final NaniteComputeProgram prepare;

    NaniteShadingGeometry(ComputeRuntime runtime, PreparedNiagaraNaniteShaders shaders) {
        RenderSystem.assertOnRenderThread();
        this.runtime = runtime;
        prepare = new NaniteComputeProgram(runtime, shaders.shadingGeometry(), WORKGROUP);
    }

    Output prepare(CommandEncoder encoder, NaniteGpuPages pages, NaniteHierarchyCulling.Instances instances,
                   NaniteHierarchyCulling.Output visible, NaniteRasterBins.Output bins, boolean reverseWinding) {
        RenderSystem.assertOnRenderThread();
        int blocks = Math.ceilDiv(bins.triangleCapacity(), WORKGROUP);
        int columns = Math.min(blocks, runtime.maxWorkGroups(0));
        int rows = Math.ceilDiv(blocks, columns);
        if (rows > runtime.maxWorkGroups(1)) throw new IllegalArgumentException("Nanite shading dispatch exceeds the device limits");
        int width = Math.multiplyExact(columns, WORKGROUP);

        var lookup = allocate(encoder, (long)visible.clusterCapacity() * TRIANGLES_PER_CLUSTER, Integer.BYTES);
        var primitives = allocate(encoder, bins.triangleCapacity(), 16);
        var vertices = allocate(encoder, (long)bins.triangleCapacity() * VECTORS_PER_TRIANGLE, 16);
        var buffers = Map.of(
                "NaniteClusterPages", pages.pages().slice(),
                "NaniteVisibleClusters", visible.clusters(),
                "NaniteBinTriangles", bins.triangles(),
                "NaniteRasterCommands", bins.commands(),
                "NaniteAssemblyRows", pages.assembly().slice(),
                "MeshSceneTransforms", instances.transforms(),
                "NanitePrimitiveLookup", lookup,
                "NaniteShadingPrimitives", primitives,
                "NaniteShadingVertices", vertices);
        var uniforms = Map.of("NaniteShadingStreamingSlots", scalar(pages.streamingSlots()),
                "NaniteShadingDispatchWidth", scalar(width), "NaniteShadingReverseWinding", scalar(reverseWinding ? 1 : 0));
        prepare.dispatch(encoder, buffers, uniforms, width, rows);
        return new Output(lookup, primitives, vertices);
    }

    private GpuBufferSlice allocate(CommandEncoder encoder, long texels, int stride) {
        long bytes = Math.multiplyExact(texels, stride);
        if (texels > runtime.maxTexelBufferElements() || bytes > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Nanite material data exceeds the device limits");
        return encoder.transientMemory().allocateGpu(bytes, Math.max(16, runtime.storageBufferAlignment()),
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER));
    }

    private static byte[] scalar(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.nativeOrder()).putInt(value).array();
    }

    @Override public void close() { prepare.close(); }
}
