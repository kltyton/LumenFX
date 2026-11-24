package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.api.client.render.VfxRenderViews;
import com.kltyton.lumenfx.client.render.light.shadow.ShadowRasterViews;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowCaster;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jspecify.annotations.Nullable;

/** Native entity and block-entity feature submissions append real opaque casters to the six loaded depth faces. */
public final class DynamicPointShadowRenderer implements AutoCloseable {
    private final WorldShadowPipelines pipelines = new WorldShadowPipelines();
    private @Nullable StagedVertexBuffer staged;
    private @Nullable FeatureRenderDispatcher features;

    public void draw(DynamicShadowScene.Frame scene, ShadowDepthTarget target, ShadowRasterViews matrices, Set<GpuTexture> worldDepths) {
        try (var view = VfxRenderViews.shadow()) { drawShadow(scene, target, matrices, worldDepths, 0, target.viewCount()); }
    }
    public void drawView(DynamicShadowScene.Frame scene, ShadowDepthTarget target, ShadowRasterViews matrices,
                         Set<GpuTexture> worldDepths, int view) {
        try (var scope = VfxRenderViews.shadow()) { drawShadow(scene, target, matrices, worldDepths, view, view + 1); }
    }
    private void drawShadow(DynamicShadowScene.Frame scene, ShadowDepthTarget target, ShadowRasterViews matrices,
                            Set<GpuTexture> worldDepths, int first, int end) {
        RenderSystem.assertOnRenderThread();
        if (scene.entities().isEmpty() && scene.blockEntities().isEmpty()) return;
        var minecraft = Minecraft.getInstance();
        initialize(minecraft);
        var dispatcher = Objects.requireNonNull(features);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var spotCaster = SpotShadowCaster.uniform(encoder, matrices);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        int stride = Math.multiplyExact(Math.ceilDiv(64, alignment), alignment);
        GpuBufferSlice projections;
        try (var mapped = encoder.transientMemory().allocateGpuMapped((long) stride * (end - first), alignment, GpuBuffer.USAGE_UNIFORM)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            for (int face = first; face < end; face++) matrices.hostProjection(face).get((face - first) * stride, bytes);
            projections = mapped.slice();
        }
        var originalProjection = Objects.requireNonNull(RenderSystem.getProjectionMatrixBuffer(), "Shadow features require a captured world projection");
        var originalType = RenderSystem.getProjectionType();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            for (int face = first; face < end; face++) {
                CameraRenderState camera = matrices.camera(face, scene.origin());
                modelView.set(camera.viewRotationMatrix);
                var projection = projections.slice((long) (face - first) * stride, 64);
                RenderSystem.setProjectionMatrix(projection, matrices.projectionType());
                var submits = new SubmitNodeStorage();
                submit(minecraft, scene, camera, submits);
                try (var frame = dispatcher.prepareFrame(submits)) {
                    int index = face;
                    WorldShadowPassScope.draw(target, index, projection, spotCaster,
                            DirectionalShadowCaster.uniform(encoder, matrices, index), worldDepths, pipelines, frame::executeSolid);
                }
            }
        } finally {
            modelView.popMatrix();
            RenderSystem.setProjectionMatrix(originalProjection, originalType);
        }
    }

    private void initialize(Minecraft minecraft) {
        if (features != null) return;
        var nextStaged = new StagedVertexBuffer(() -> "LumenFX point-shadow features", 4194304);
        try {
            var next = new FeatureRenderDispatcher(minecraft.gameRenderer.renderBuffers(), minecraft.getModelManager(),
                    minecraft.getAtlasManager(), minecraft.font, minecraft.gameRenderer.gameRenderState());
            // This dedicated dispatcher must also close each prepared frame against its own staging buffer.
            next.stagedVertexBuffer = nextStaged;
            staged = nextStaged; features = next;
        } catch (RuntimeException | Error failure) { nextStaged.close(); throw failure; }
    }

    private static void submit(Minecraft minecraft, DynamicShadowScene.Frame scene, CameraRenderState camera, SubmitNodeStorage output) {
        var pose = new PoseStack(); var origin = scene.origin();
        for (var entity : scene.entities())
            minecraft.getEntityRenderDispatcher().submit(entity, camera, entity.x - origin.x, entity.y - origin.y, entity.z - origin.z, pose, output);
        for (var block : scene.blockEntities()) {
            var position = block.blockPos;
            pose.pushPose();
            try {
                pose.translate(position.getX() - origin.x, position.getY() - origin.y, position.getZ() - origin.z);
                minecraft.getBlockEntityRenderDispatcher().submit(block, pose, output, camera);
            } finally { pose.popPose(); }
        }
    }

    /** Called once after the complete light batch; the host pool fences every buffer used across all six-face draws. */
    public void endFrame() { RenderSystem.assertOnRenderThread(); if (staged != null) staged.endFrame(); }

    @Override public void close() {
        RenderSystem.assertOnRenderThread(); pipelines.clear();
        if (features != null) { features.close(); features = null; }
        if (staged != null) { staged.close(); staged = null; }
    }
}
