package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.depth.HierarchicalSceneDepth;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowMatrices;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowMatrices;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.PointShadowParticleView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuView;
import com.kltyton.lumenfx.client.render.niagara.NiagaraViewMath;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

record NaniteViewProjection(NaniteHierarchyCulling.View culling, NaniteTriangleRaster.RasterView raster) {
    private static final float CM_TO_BLOCKS = 0.01f;
    private static final Matrix4fc TO_HOST = new Matrix4f().m00(CM_TO_BLOCKS)
            .m11(0).m22(0).m12(CM_TO_BLOCKS).m21(CM_TO_BLOCKS);

    static NaniteViewProjection capture(NiagaraGpuView.Frame frame, boolean zeroToOne, boolean topDown,
                                       float pixelsPerEdge, float minPixelsPerEdgeHardware, boolean hardwareOnly, boolean frustum) {
        if (!(pixelsPerEdge > 0) || !(minPixelsPerEdgeHardware > 0))
            throw new IllegalArgumentException("Nanite edge thresholds must be positive");
        Matrix4fc projection = frame.projection();
        var clip = new Matrix4f(projection).mul(frame.modelView()).mul(TO_HOST);
        var camera = frame.camera();
        var forward = new Vector3f(camera.forwardX(), camera.forwardY(), camera.forwardZ());
        float viewToPixels = 0.5f * Math.abs(projection.m11()) * frame.height();
        boolean orthographic = !NiagaraViewMath.isPerspective(frame.baseProjection());
        var view = new NaniteHierarchyCulling.View(clip, new Vector3f(), forward, camera.nearPlane(),
                viewToPixels / pixelsPerEdge, viewToPixels / minPixelsPerEdgeHardware, orthographic, zeroToOne, hardwareOnly, frustum, false);
        return new NaniteViewProjection(view, new NaniteTriangleRaster.RasterView(clip, frame.width(), frame.height(), zeroToOne, topDown, false, false, 0, 0));
    }

    static NaniteViewProjection localShadow(PointShadowParticleView view, PointShadowMatrices matrices,
            int face, int size, boolean zeroToOne, float pixelsPerEdge,
            float minPixelsPerEdgeHardware, boolean hardwareOnly, boolean frustum) {
        var clip = view.clip(matrices, face).mul(TO_HOST);
        var rotation = matrices.hostView(face);
        var forward = new Vector3f(-rotation.m02(), -rotation.m22(), -rotation.m12());
        var offset = view.lightOrigin().subtract(view.camera());
        var origin = new Vector3f((float) (offset.x * 100), (float) (offset.z * 100), (float) (offset.y * 100));
        float viewToPixels = 0.5f * Math.abs(matrices.hostProjection(face).m11()) * size;
        var culling = new NaniteHierarchyCulling.View(clip, origin, forward, matrices.nearClipCm(),
                viewToPixels / pixelsPerEdge, viewToPixels / minPixelsPerEdgeHardware, false, zeroToOne, hardwareOnly, frustum, false);
        // Shadow target viewports use the same numeric Y mapping on the two host backends.
        return new NaniteViewProjection(culling, new NaniteTriangleRaster.RasterView(clip, size, size, zeroToOne, false, !matrices.spot(), false, 0, 0));
    }

    static NaniteViewProjection directionalShadow(DirectionalShadowMatrices matrices, int index, boolean zeroToOne,
            float pixelsPerEdge, float minPixelsPerEdgeHardware, boolean hardwareOnly, boolean frustum) {
        var area = matrices.outerTile(index);
        var projection = matrices.outerProjection(index);
        float viewToPixels = 0.5f * Math.abs(projection.m11()) * area.height();
        var rotation = matrices.hostView(index);
        var clip = projection.mul(rotation).mul(TO_HOST);
        var forward = new Vector3f(-rotation.m02(), -rotation.m22(), -rotation.m12());
        var culling = new NaniteHierarchyCulling.View(clip, new Vector3f(), forward, 1f,
                viewToPixels / pixelsPerEdge, viewToPixels / minPixelsPerEdgeHardware, true, zeroToOne, hardwareOnly, frustum, true);
        return new NaniteViewProjection(culling, new NaniteTriangleRaster.RasterView(clip, area.width(), area.height(),
                zeroToOne, false, false, true, area.x(), area.y()));
    }

    NaniteHierarchyCulling.OcclusionView occlusion(HierarchicalSceneDepth.View hierarchy) {
        return new NaniteHierarchyCulling.OcclusionView(hierarchy, raster.clip(), raster.width(), raster.height(),
                raster.textureOriginTopDown(), true);
    }
}
