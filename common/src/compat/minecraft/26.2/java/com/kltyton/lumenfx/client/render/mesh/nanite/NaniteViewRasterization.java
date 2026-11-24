package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.depth.HierarchicalSceneDepth;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraNaniteShaders;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Owns the visibility passes for a view. The main result stays live through shading and post-HZB construction. */
final class NaniteViewRasterization implements AutoCloseable {
    record Limits(int clusters, int deferred, int triangles) {
        Limits {
            if (clusters < 1 || deferred < 1 || triangles < 1)
                throw new IllegalArgumentException("Invalid Nanite visibility limits");
        }
    }

    record Mesh(NaniteMeshStreaming streaming, NaniteGpuPages pages, NaniteInstanceCollector.Prepared source,
                GpuBufferSlice materialFlags, NaniteHierarchyCulling.PrimitiveBounds bounds) {
        NaniteGpuInstanceScene.Output scene() { return source.scene(); }
        NaniteHierarchyCulling.Instances instances() { return scene().instances(); }
    }
    record Interpolants(GpuBufferSlice slice, int wordsPerVertex) {
        Interpolants {
            if (wordsPerVertex < 5) throw new IllegalArgumentException("Invalid Nanite interpolant stride");
        }
    }
    record Draw(NaniteVisibilityTarget.View visibility, NaniteHierarchyCulling.Output clusters,
                NaniteShadingGeometry.Output shading, NaniteGpuInstanceScene.Output scene,
                Map<Integer, Interpolants> interpolants) { }
    record Main(Draw draw, NaniteHierarchyCulling.@Nullable MainPass deferred) { }
    @FunctionalInterface
    interface VertexMaterials {
        Map<Integer, Interpolants> apply(CommandEncoder encoder, Mesh mesh, NaniteRasterBins.Output bins,
                                        NaniteShadingGeometry.Output geometry, NaniteViewProjection view,
                                        NaniteViewProjection previous);
    }

    interface ProgrammableRaster {
        void draw(NaniteVisibilityTarget.HardwarePass hardware, NaniteShadingGeometry.Output geometry,
                  Map<Integer, Interpolants> interpolants);
        void dispatch(NaniteTriangleRaster.Software output, NaniteRasterBins.Output bins, NaniteShadingGeometry.Output geometry,
                      Map<Integer, Interpolants> interpolants, int width, int rows, boolean identity);
    }

    private final NaniteHierarchyCulling culling;
    private final NaniteRasterBins bins;
    private final NaniteTriangleRaster raster;
    private final NaniteShadingGeometry shading;
    private final NaniteVisibilityTarget visibility = new NaniteVisibilityTarget();
    private final NaniteVisibilityTarget shadowVisibility = new NaniteVisibilityTarget();
    private final NaniteVisibilityTarget primeVisibility = new NaniteVisibilityTarget();
    private final NaniteGpuStreamingFeedback feedback;
    private final HierarchicalSceneDepth postDepth = new HierarchicalSceneDepth();

    NaniteViewRasterization(ComputeRuntime runtime, PreparedNiagaraNaniteShaders shaders) {
        RenderSystem.assertOnRenderThread();
        feedback = new NaniteGpuStreamingFeedback(runtime);
        culling = new NaniteHierarchyCulling(runtime, shaders);
        NaniteRasterBins grouping = null;
        NaniteTriangleRaster triangleRaster = null;
        try {
            grouping = new NaniteRasterBins(runtime, shaders);
            triangleRaster = new NaniteTriangleRaster(runtime, shaders);
            shading = new NaniteShadingGeometry(runtime, shaders);
            raster = triangleRaster;
            bins = grouping;
        } catch (RuntimeException | Error failure) {
            if (triangleRaster != null) triangleRaster.close();
            if (grouping != null) grouping.close();
            culling.close();
            throw failure;
        }
    }

    Main main(CommandEncoder encoder, Mesh mesh, NaniteViewProjection view, NaniteViewProjection previous, GpuTextureView sceneDepth,
              NaniteHierarchyCulling.@Nullable OcclusionView previousHzb, Limits limits, long frame,
              VertexMaterials materials, @Nullable ProgrammableRaster programmable,
              boolean ignoreOpaqueDepth, boolean requestPages) {
        var requests = feedback.output(frame, mesh.streaming(), requestPages ? 3 : -1);
        NaniteHierarchyCulling.MainPass deferred = previousHzb == null ? null
                : culling.cullMain(encoder, mesh.pages(), mesh.instances(), view.culling(), previousHzb,
                        mesh.bounds(), limits.clusters(), requests, limits.deferred());
        var clusters = deferred == null
                ? culling.cull(encoder, mesh.pages(), mesh.instances(), view.culling(), mesh.bounds(),
                        limits.clusters(), requests)
                : deferred.visible();
        var draw = rasterize(encoder, mesh, view, previous, sceneDepth, clusters, limits, materials, programmable, visibility, ignoreOpaqueDepth);
        return new Main(draw, deferred);
    }

    Main shadow(CommandEncoder encoder, Mesh mesh, NaniteViewProjection view, GpuTextureView shadowDepth,
                NaniteHierarchyCulling.@Nullable OcclusionView previousHzb, Limits limits, long frame,
                VertexMaterials materials, @Nullable ProgrammableRaster programmable, boolean requestPages) {
        var requests = feedback.output(frame, mesh.streaming(), requestPages ? 0 : -1);
        var deferred = previousHzb == null ? null : culling.cullMain(encoder, mesh.pages(), mesh.instances(), view.culling(),
                previousHzb, mesh.bounds(), limits.clusters(), requests, limits.deferred());
        var clusters = deferred == null ? culling.cull(encoder, mesh.pages(), mesh.instances(), view.culling(), mesh.bounds(),
                limits.clusters(), requests) : deferred.visible();
        var draw = rasterize(encoder, mesh, view, view, shadowDepth, clusters, limits, materials,
                programmable, shadowVisibility, true);
        return new Main(draw, deferred);
    }

    Draw shadowPost(CommandEncoder encoder, Mesh mesh, NaniteViewProjection view, GpuTextureView shadowDepth,
                    NaniteHierarchyCulling.OcclusionView currentHzb, NaniteHierarchyCulling.MainPass deferred, Limits limits,
                    long frame, VertexMaterials materials, @Nullable ProgrammableRaster programmable, boolean requestPages) {
        var clusters = culling.cullPost(encoder, mesh.pages(), mesh.instances(), view.culling(), currentHzb, deferred,
                mesh.bounds(), limits.clusters(), feedback.output(frame, mesh.streaming(), requestPages ? 0 : -1));
        return rasterize(encoder, mesh, view, view, shadowDepth, clusters, limits, materials,
                programmable, shadowVisibility, true);
    }

    GpuTextureView primeDepth(CommandEncoder encoder, Mesh mesh, NaniteViewProjection view, GpuTextureView coarseDepth,
                              Limits limits, VertexMaterials materials, @Nullable ProgrammableRaster programmable) {
        var clusters = culling.cull(encoder, mesh.pages(), mesh.instances(), view.culling(), mesh.bounds(), limits.clusters(), null);
        return rasterize(encoder, mesh, view, view, coarseDepth, clusters, limits, materials, programmable, primeVisibility, true)
                .visibility().depth();
    }

    NaniteHierarchyCulling.OcclusionView buildPostHzb(CommandEncoder encoder, NaniteViewProjection view, GpuTextureView mainDepth) {
        return view.occlusion(postDepth.buildFurthest(encoder, mainDepth));
    }

    Draw post(CommandEncoder encoder, Mesh mesh, NaniteViewProjection view, NaniteViewProjection previous, GpuTextureView mainDepth,
              NaniteHierarchyCulling.OcclusionView currentHzb, NaniteHierarchyCulling.MainPass main, Limits limits, long frame,
              VertexMaterials materials, @Nullable ProgrammableRaster programmable) {
        var clusters = culling.cullPost(encoder, mesh.pages(), mesh.instances(), view.culling(), currentHzb, main,
                mesh.bounds(), limits.clusters(), feedback.output(frame, mesh.streaming(), 3));
        return rasterize(encoder, mesh, view, previous, mainDepth, clusters, limits, materials, programmable, visibility, false);
    }

    private Draw rasterize(CommandEncoder encoder, Mesh mesh, NaniteViewProjection view, NaniteViewProjection previous, GpuTextureView depth,
                           NaniteHierarchyCulling.Output clusters, Limits limits, VertexMaterials materials,
                           @Nullable ProgrammableRaster programmable, NaniteVisibilityTarget destination,
                           boolean ignoreOpaqueDepth) {
        var projection = view.raster();
        if (!ignoreOpaqueDepth && (depth.getWidth(0) != projection.width() || depth.getHeight(0) != projection.height()))
            throw new IllegalArgumentException("Nanite projection and depth view disagree");
        var groups = bins.prepare(encoder, mesh.pages(), clusters, limits.triangles());
        var geometry = shading.prepare(encoder, mesh.pages(), mesh.instances(), clusters, groups, projection.reverseWinding());
        var interpolants = Map.copyOf(materials.apply(encoder, mesh, groups, geometry, view, previous));
        var output = raster.prepare(encoder, mesh.pages(), mesh.instances(), clusters, groups, geometry, projection,
                mesh.materialFlags(), view.culling().hardwareOnly(), programmable == null ? null
                        : (software, width, rows, identity) ->
                        programmable.dispatch(software, groups, geometry, interpolants, width, rows, identity));
        var pixels = destination.rasterize(encoder, depth, output, projection, ignoreOpaqueDepth,
                programmable == null ? null : hardware -> programmable.draw(hardware, geometry, interpolants));
        return new Draw(pixels, clusters, geometry, mesh.scene(), interpolants);
    }

    long advanceStreamingFeedback(CommandEncoder encoder, long sequence, int minimum, int maximum,
                                  Collection<NaniteMeshStreaming> resources, NaniteStreamingRequests authored,
                                  boolean useGpuRequests) {
        return feedback.advance(encoder, sequence, minimum, maximum, resources, authored, useGpuRequests);
    }

    void resetStreamingFeedback() { feedback.close(); }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        try { feedback.close(); }
        finally {
            try { shading.close(); }
            finally {
                closeRasterResources();
            }
        }
    }

    private void closeRasterResources() {
        try { postDepth.close(); }
        finally {
            try { visibility.close(); }
            finally {
                try { shadowVisibility.close(); }
                finally {
                    try { primeVisibility.close(); }
                    finally {
                        try { raster.close(); }
                        finally {
                            try { bins.close(); }
                            finally { culling.close(); }
                        }
                    }
                }
            }
        }
    }
}
