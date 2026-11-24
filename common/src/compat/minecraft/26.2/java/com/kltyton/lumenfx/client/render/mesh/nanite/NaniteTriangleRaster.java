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
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4fc;

/** Prepares the hardware vertex stream and the software depth/identity result from the same triangle bins. */
final class NaniteTriangleRaster implements AutoCloseable {
    private static final int WORKGROUP = 64;
    static final int TWO_SIDED = 1;

    record RasterView(Matrix4fc clip, int width, int height, boolean zeroToOne, boolean textureOriginTopDown,
                      boolean reverseWinding, boolean clampNear, int offsetX, int offsetY) {
        RasterView {
            if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid Nanite raster viewport");
        }
    }

    record Hardware(GpuBufferSlice positions, GpuBufferSlice primitives, NaniteRasterBins.Output bins) { }
    record Software(GpuBufferSlice depth, GpuBufferSlice primitives) { }
    record Output(Hardware hardware, @Nullable Software software) { }

    @FunctionalInterface
    interface SoftwareMaterials {
        void dispatch(Software output, int width, int rows, boolean identity);
    }

    private final ComputeRuntime runtime;
    private final NaniteComputeProgram hardware, softwareDepth, softwarePrimitives;

    NaniteTriangleRaster(ComputeRuntime runtime, PreparedNiagaraNaniteShaders shaders) {
        RenderSystem.assertOnRenderThread();
        this.runtime = runtime;
        hardware = new NaniteComputeProgram(runtime, shaders.hardwarePrepare(), WORKGROUP);
        NaniteComputeProgram depth = null;
        try {
            depth = new NaniteComputeProgram(runtime, shaders.softwareDepth(), WORKGROUP);
            softwarePrimitives = new NaniteComputeProgram(runtime, shaders.softwarePrimitives(), WORKGROUP);
            softwareDepth = depth;
        } catch (RuntimeException | Error failure) {
            if (depth != null) depth.close();
            hardware.close();
            throw failure;
        }
    }

    Output prepare(CommandEncoder encoder, NaniteGpuPages pages, NaniteHierarchyCulling.Instances instances,
                   NaniteHierarchyCulling.Output visible, NaniteRasterBins.Output bins, NaniteShadingGeometry.Output geometry, RasterView view,
                   GpuBufferSlice materialFlags, boolean hardwareOnly, @Nullable SoftwareMaterials materials) {
        RenderSystem.assertOnRenderThread();
        if (materialFlags.length() < (long)NaniteRasterBins.MATERIAL_SLOTS * Integer.BYTES)
            throw new IllegalArgumentException("Nanite raster requires a flag word for each material slot");
        int blocks = Math.ceilDiv(bins.triangleCapacity(), WORKGROUP);
        int columns = Math.min(blocks, runtime.maxWorkGroups(0));
        int rows = Math.ceilDiv(blocks, columns);
        if (rows > runtime.maxWorkGroups(1)) throw new IllegalArgumentException("Nanite raster dispatch exceeds the device limits");
        int width = Math.multiplyExact(columns, WORKGROUP);

        int texel = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER;
        long pixels = Math.multiplyExact((long)view.width(), view.height());
        long positionCount = (long)bins.triangleCapacity() * 3;
        if (positionCount > runtime.maxTexelBufferElements() || !hardwareOnly && pixels > runtime.maxTexelBufferElements())
            throw new IllegalArgumentException("Nanite raster output exceeds the texel buffer limit");
        var positions = allocate(encoder, positionCount * 16, texel);
        var hardwareIds = allocate(encoder, (long)bins.triangleCapacity() * 8, texel);
        var buffers = new HashMap<>(Map.ofEntries(
                Map.entry("NaniteClusterPages", pages.pages().slice()),
                Map.entry("NaniteVisibleClusters", visible.clusters()),
                Map.entry("NaniteBinTriangles", bins.triangles()),
                Map.entry("NaniteRasterCommands", bins.commands()),
                Map.entry("NaniteShadingPrimitives", geometry.primitives()),
                Map.entry("NaniteShadingVertices", geometry.vertices()),
                Map.entry("NaniteAssemblyRows", pages.assembly().slice()),
                Map.entry("MeshSceneTransforms", instances.transforms()),
                Map.entry("NaniteMaterialRasterFlags", materialFlags),
                Map.entry("NaniteHardwarePositions", positions),
                Map.entry("NaniteHardwarePrimitives", hardwareIds)));
        var uniforms = Map.of(
                "NaniteRasterWorldToClip", matrix(view.clip()),
                "NaniteRasterViewport", vector(view.width(), view.height(), view.zeroToOne() ? 1 : 0, view.textureOriginTopDown() ? 1 : 0),
                "NaniteRasterLimits", vector(pages.streamingSlots(), width, view.clampNear() ? 1 : 0, 0));
        hardware.dispatch(encoder, buffers, uniforms, width, rows);
        var prepared = new Hardware(positions, hardwareIds, bins);
        if (hardwareOnly) return new Output(prepared, null);
        int clearableTexel = texel | GpuBuffer.USAGE_COPY_DST;
        var depth = allocate(encoder, pixels * Integer.BYTES, clearableTexel);
        var softwareIds = allocate(encoder, pixels * Integer.BYTES, clearableTexel);
        runtime.clearBuffer(depth, 0);
        runtime.clearBuffer(softwareIds, 0);
        buffers.put("NaniteSoftwareDepth", depth);
        buffers.put("NaniteSoftwarePrimitives", softwareIds);
        var software = new Software(depth, softwareIds);
        softwareDepth.dispatch(encoder, buffers, uniforms, width, rows);
        if (materials != null) materials.dispatch(software, width, rows, false);
        softwarePrimitives.dispatch(encoder, buffers, uniforms, width, rows);
        if (materials != null) materials.dispatch(software, width, rows, true);
        return new Output(prepared, software);
    }

    private static byte[] matrix(Matrix4fc matrix) {
        var bytes = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        matrix.get(bytes.asFloatBuffer());
        return bytes.array();
    }

    private static byte[] vector(int x, int y, int z, int w) {
        return ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putInt(x).putInt(y).putInt(z).putInt(w).array();
    }

    private GpuBufferSlice allocate(CommandEncoder encoder, long bytes, int usage) {
        if (bytes > runtime.maxStorageBufferBytes()) throw new IllegalArgumentException("Nanite raster output exceeds the storage limit");
        return encoder.transientMemory().allocateGpu(bytes, Math.max(16, runtime.storageBufferAlignment()), ComputeUsage.storageBuffer(usage));
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        try { softwarePrimitives.close(); }
        finally {
            try { softwareDepth.close(); }
            finally { hardware.close(); }
        }
    }
}
