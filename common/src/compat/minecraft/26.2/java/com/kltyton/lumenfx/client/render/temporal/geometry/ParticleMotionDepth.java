package com.kltyton.lumenfx.client.render.temporal.geometry;

import com.kltyton.lumenfx.effect.render.MaterialDepth;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Writable velocity depth copied before translucent velocity draws, without changing the color pass depth. */
public final class ParticleMotionDepth implements AutoCloseable {
    private final TranslucentMotionReplayLog replayLog = new TranslucentMotionReplayLog();
    private final TranslucentMotionStepReplay stepReplay = new TranslucentMotionStepReplay();
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private @Nullable GpuTexture preColorTexture;
    private @Nullable GpuTextureView preColorView;
    private @Nullable GpuTexture motionSnapshotTexture;
    private @Nullable GpuTextureView motionSnapshotView;
    private boolean copied;
    private boolean preColorCaptured;

    public void beginFrame() {
        copied = false;
        preColorCaptured = false;
    }

    public boolean isActive() { return copied; }

    public GpuTextureView attachment(CommandEncoder encoder, RenderTarget scene) {
        RenderSystem.assertOnRenderThread();
        var source = scene.getDepthTexture();
        if (source == null) throw new IllegalStateException("Particle velocity requires scene depth");
        if (texture == null || texture.getWidth(0) != scene.width || texture.getHeight(0) != scene.height
                || texture.getFormat() != source.getFormat()) {
            closeDepthTarget();
            texture = RenderSystem.getDevice().createTexture("LumenFX particle velocity depth",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                            | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    source.getFormat(), scene.width, scene.height, 1, 1);
            try { view = RenderSystem.getDevice().createTextureView(texture); }
            catch (RuntimeException | Error error) { texture.close(); texture = null; throw error; }
        }
        if (!copied) {
            com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.INSTANCE.copyDepth(encoder, source, texture);
            copied = true;
        }
        return Objects.requireNonNull(view);
    }

    /** Snapshots the color target's depth strictly before a translucent pass that guards or replays velocity depth. */
    public void snapshotPreColorDepth(CommandEncoder encoder, RenderTarget scene) {
        RenderSystem.assertOnRenderThread();
        if (!copied) {
            preColorCaptured = false;
            return;
        }
        var source = scene.getDepthTexture();
        if (source == null || texture == null
                || texture.getWidth(0) != scene.width || texture.getHeight(0) != scene.height
                || texture.getFormat() != source.getFormat()) {
            preColorCaptured = false;
            return;
        }
        if (preColorTexture == null
                || preColorTexture.getWidth(0) != scene.width
                || preColorTexture.getHeight(0) != scene.height
                || preColorTexture.getFormat() != source.getFormat()) {
            closePreColorTarget();
            preColorTexture = RenderSystem.getDevice().createTexture("LumenFX translucent pre-color depth",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                            | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    source.getFormat(), scene.width, scene.height, 1, 1);
            try { preColorView = RenderSystem.getDevice().createTextureView(preColorTexture); }
            catch (RuntimeException | Error error) { preColorTexture.close(); preColorTexture = null; throw error; }
        }
        com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.INSTANCE.copyDepth(encoder, source, preColorTexture);
        preColorCaptured = true;
    }

    /**
     * Executes tiled, overflow-proof ordered translucent motion replay (OpenGL 4.3+ / Vulkan with
     * {@code fragmentStoresAndAtomics && geometryShader}).
     */
    public boolean executeTranslucentMotionLog(
            CommandEncoder encoder, RenderTarget scene,
            GeometryMotionRenderer.CoverageTargets targets,
            @Nullable ScissorState scissor,
            List<TranslucentMotionReplayLog.DrawSpec> draws,
            long maxPrimitives,
            BiConsumer<RenderPass, TranslucentMotionReplayLog.DrawCursor> captureDrawer,
            Consumer<ScissorState> singlePixelOverflowFallback) {
        RenderSystem.assertOnRenderThread();
        if (!copied || !preColorCaptured || view == null || preColorView == null || texture == null
                || texture.getWidth(0) != scene.width || texture.getHeight(0) != scene.height
                || targets.width() != scene.width || targets.height() != scene.height) {
            throw new IllegalStateException("Translucent motion log requires active velocity depth and pre-color depth snapshot");
        }
        GpuTextureView existingMotionView = snapshotMotionDepth(encoder, scene);
        if (existingMotionView == null) return true;
        return replayLog.executeTiled(encoder, targets, view, preColorView, existingMotionView,
                scissor, draws, maxPrimitives, captureDrawer, singlePixelOverflowFallback);
    }

    /** Initializes the pure GLSL 330 per-primitive step replay depth target from pre-color depth. */
    public void beginTranslucentStepReplay(CommandEncoder encoder, RenderTarget scene) {
        RenderSystem.assertOnRenderThread();
        if (!copied || !preColorCaptured || preColorTexture == null
                || preColorTexture.getWidth(0) != scene.width
                || preColorTexture.getHeight(0) != scene.height) {
            throw new IllegalStateException("Translucent step replay requires active velocity depth and pre-color depth snapshot");
        }
        stepReplay.beginGroup(encoder, preColorTexture, scene.width, scene.height);
    }

    /** Executes one non-self-overlapping primitive step on the pure GLSL 330 fallback path. */
    public void runTranslucentStepPrimitive(CommandEncoder encoder, RenderTarget scene,
                                            GeometryMotionRenderer.CoverageTargets targets,
                                            MaterialDepth.Compare compare, boolean writesColorDepth,
                                            int instanceBase, int indirectOffsetUints, boolean indirectGuard,
                                            @Nullable ScissorState scissor,
                                            BiConsumer<RenderPass, GpuBufferSlice> drawPrimitive) {
        RenderSystem.assertOnRenderThread();
        if (!copied || view == null || texture == null
                || texture.getWidth(0) != targets.width() || texture.getHeight(0) != targets.height()) {
            return;
        }
        stepReplay.runPrimitiveStep(encoder, scene, targets, view,
                () -> snapshotMotionDepth(encoder, scene),
                compare, writesColorDepth, instanceBase, indirectOffsetUints, indirectGuard, scissor, drawPrimitive);
    }

    private @Nullable GpuTextureView snapshotMotionDepth(CommandEncoder encoder, RenderTarget scene) {
        if (texture == null) return null;
        if (motionSnapshotTexture == null
                || motionSnapshotTexture.getWidth(0) != scene.width
                || motionSnapshotTexture.getHeight(0) != scene.height
                || motionSnapshotTexture.getFormat() != texture.getFormat()) {
            closeMotionSnapshotTarget();
            motionSnapshotTexture = RenderSystem.getDevice().createTexture("LumenFX translucent motion depth snapshot",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    texture.getFormat(), scene.width, scene.height, 1, 1);
            try { motionSnapshotView = RenderSystem.getDevice().createTextureView(motionSnapshotTexture); }
            catch (RuntimeException | Error error) { motionSnapshotTexture.close(); motionSnapshotTexture = null; throw error; }
        }
        encoder.copyTextureToTexture(texture, motionSnapshotTexture, 0, 0, 0, 0, 0, scene.width, scene.height);
        return motionSnapshotView;
    }

    public @Nullable GpuTextureView preColorView() { return preColorCaptured ? preColorView : null; }

    public void clearPreColorCapture() { preColorCaptured = false; }

    public GpuTextureView resolveDepth(GpuTextureView opaqueDepth) { return copied ? Objects.requireNonNull(view) : opaqueDepth; }

    private void closeDepthTarget() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        view = null;
        texture = null;
    }

    private void closePreColorTarget() {
        if (preColorView != null) preColorView.close();
        if (preColorTexture != null) preColorTexture.close();
        preColorView = null;
        preColorTexture = null;
    }

    private void closeMotionSnapshotTarget() {
        if (motionSnapshotView != null) motionSnapshotView.close();
        if (motionSnapshotTexture != null) motionSnapshotTexture.close();
        motionSnapshotView = null;
        motionSnapshotTexture = null;
    }

    @Override public void close() {
        replayLog.close();
        stepReplay.close();
        closeDepthTarget();
        closePreColorTarget();
        closeMotionSnapshotTarget();
        copied = false;
        preColorCaptured = false;
    }
}
