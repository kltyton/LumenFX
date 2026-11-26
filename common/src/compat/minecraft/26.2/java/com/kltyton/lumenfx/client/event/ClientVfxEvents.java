package com.kltyton.lumenfx.client.event;

import com.kltyton.lumenfx.api.client.attachment.EntityLocatorAttachments;
import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.audio.MinecraftNiagaraAudio;
import com.kltyton.lumenfx.client.audio.analysis.NiagaraAudioBusAnalysis;
import com.kltyton.lumenfx.client.compat.iris.IrisParticleRenderScope;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuBatchDispatcher.TickStage;
import com.kltyton.lumenfx.client.compat.shaderpack.HostFrameContext;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackCompat;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackFrame;
import com.kltyton.lumenfx.client.platform.LumenClientPlatform;
import com.kltyton.lumenfx.effect.render.TranslucencyPass;
import com.lowdragmc.photon.client.render.OpaqueDepthCapture;
import com.lowdragmc.photon.client.render.PhotonDeferredLayer;
import com.lowdragmc.photon.client.render.PhotonFXRenderState;
import com.lowdragmc.photon.client.render.PhotonRenderOutput;
import com.lowdragmc.photon.client.render.PhotonStage;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

public final class ClientVfxEvents {
    private static LevelFrame levelFrame;
    private static long frameSequence;
    private static long drawnExternalFrame = -1;
    private static long rvtRenderedFrame = -1;
    private static SceneEffects sceneEffects;
    private static boolean irisBridgeWired;

    public record LevelFrame(long identity, VfxEngine.FrameInput input, Matrix4fc modelView,
                             Matrix4fc projection, int width, int height,
                             PhotonWorldRenderState.@Nullable Frame photon) { }
    public record SceneEffects(VfxEngine.FrameInput input, VfxEngine.PreparedFrame effects) { }

    private ClientVfxEvents() { }

    public static void onSoundEngineLoaded() { NiagaraAudioBusAnalysis.clear(); }

    public static void onChunkChanged(ChunkAccess chunk) {
        if (chunk instanceof LevelChunk levelChunk && levelChunk.getLevel() instanceof ClientLevel level)
            VfxEngine.INSTANCE.distanceFieldChunkChanged(level, chunk.getPos());
    }

    public static void initializeShaderpackBridge() {
        if (irisBridgeWired) return;
        if (ShaderpackCompat.initializeIrisBridge()) {
            if (!ShaderpackCompat.installHostFrameContextProvider(ClientVfxEvents::hostFrameContext)
                    || !ShaderpackCompat.installFrameListener(new com.kltyton.lumenfx.client.compat.shaderpack.FrameListener() {
                        @Override public void onFrame(ShaderpackFrame frame) {
                            drawShaderpackFrame(frame, EnumSet.allOf(TranslucencyPass.class), false);
                        }
                        @Override public void onOpaqueFrame(ShaderpackFrame frame) {
                            drawOpaqueShaderpackFrame(frame);
                        }
                        @Override public void onBeforeComposite(ShaderpackFrame frame,
                                EnumSet<TranslucencyPass> immediateStages, boolean moreStagesFollow) {
                            drawShaderpackFrame(frame, immediateStages, moreStagesFollow);
                        }
                        @Override public void onCompositeStage(ShaderpackFrame frame,
                                EnumSet<TranslucencyPass> stages, boolean finalStage) {
                            drawShaderpackDeferredStage(frame, stages, finalStage);
                        }
                    }))
                throw new IllegalStateException("The shaderpack frame bridge already has another consumer");
            irisBridgeWired = true;
        }
    }

    public static LevelFrame beginLevelFrame(LevelRenderState state, Matrix4fc modelView) {
        RenderSystem.assertOnRenderThread();
        LevelFrame previous = levelFrame;
        VfxEngine.FrameInput frame = LumenClientPlatform.getFrameInput(state);
        var capture = WorldHdrCapture.INSTANCE;
        capture.captureCameraProjection(state.cameraRenderState.projectionMatrix);
        if (frame != null) {
            Matrix4f projection = new Matrix4f();
            capture.copyProjection(projection);
            var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            levelFrame = new LevelFrame(frameSequence++, frame, new Matrix4f(modelView), projection,
                    target.width, target.height, photonFrame(state));
        } else levelFrame = null;
        return previous;
    }

    private static PhotonWorldRenderState.@Nullable Frame photonFrame(LevelRenderState state) {
        for (var particles : state.particlesRenderState.particles) {
            if (particles instanceof PhotonFXRenderState photon) return photon.frame(state.cameraRenderState);
        }
        return null;
    }

    public static void dispatchNativeGpu(TickStage stage) {
        LevelFrame current = levelFrame;
        if (current == null || WorldHdrCapture.INSTANCE.externalSceneOwner()) return;
        VfxEngine.INSTANCE.dispatchNativeGpuStage(current.input(), current.modelView(),
                Minecraft.getInstance().gameRenderer.mainRenderTarget(), stage);
    }

    public static void endLevelFrame(LevelFrame previous) {
        LevelFrame current = levelFrame;
        levelFrame = previous;
        if (current != null) VfxEngine.INSTANCE.endNativeGpuFrame(current.input());
    }

    public static SceneEffects beginSceneEffects() {
        SceneEffects previous = sceneEffects;
        sceneEffects = null;
        return previous;
    }

    public static void endSceneEffects(SceneEffects previous) {
        SceneEffects current = sceneEffects;
        sceneEffects = previous;
        if (current != null && current.effects() != null) current.effects().close();
    }

    /** Runs before the host copies the opaque depth to its transparency targets. */
    public static void onAfterOpaqueFeatures(LevelRenderState state) {
        var current = levelFrame;
        if (current == null || current.input() != LumenClientPlatform.getFrameInput(state)
                || IrisParticleRenderScope.isShadowPass()) return;
        if (!WorldHdrCapture.INSTANCE.externalSceneOwner()) {
            drawPhoton(current, PhotonStage.AFTER_OPAQUE_FEATURES);
            drawNativeOpaque();
            if (WorldHdrCapture.INSTANCE.capturingNative()) OpaqueDepthCapture.demand();
            OpaqueDepthCapture.capture();
        }
    }

    public static void onAfterTranslucentParticles(LevelRenderState state) {
        var current = levelFrame;
        if (current == null || current.input() != LumenClientPlatform.getFrameInput(state)
                || WorldHdrCapture.INSTANCE.externalSceneOwner() || IrisParticleRenderScope.isShadowPass()) return;
        drawPhoton(current, PhotonStage.AFTER_TRANSLUCENT_PARTICLES);
    }

    /** RenderPearl's main render pass only needs splitting when LumenFX uses the inter-pass boundary. */
    public static boolean needsNativeOpaqueStage() {
        var current = levelFrame;
        var capture = WorldHdrCapture.INSTANCE;
        return current != null && !capture.externalSceneOwner() && !IrisParticleRenderScope.isShadowPass()
                && (current.photon() != null || capture.capturingNative() || VfxEngine.INSTANCE.hasActiveSceneStages());
    }

    private static void drawPhoton(LevelFrame frame, PhotonStage stage) {
        var photon = frame.photon();
        if (photon != null) IrisParticleRenderScope.withParticlePrograms(() ->
                PhotonRenderOutput.withModelView(frame.modelView(), () -> photon.execute(stage)));
    }

    private static void drawNativeOpaque() {
        RenderSystem.assertOnRenderThread();
        LevelFrame current = levelFrame;
        var capture = WorldHdrCapture.INSTANCE;
        if (current == null || sceneEffects != null || !capture.capturingNative()
                || capture.externalSceneOwner()
                || !VfxEngine.INSTANCE.hasActiveSceneStages() && !capture.decalNormalReprojection()) return;
        capture.beginComposition();
        capture.prepareFogView(current.modelView(), current.input().camera(), current.input().level(),
                Minecraft.getInstance().gameRenderer.mainCamera().entity());
        var effects = VfxEngine.INSTANCE.prepareFrame(current.input(), current.modelView(), capture.linearTarget());
        sceneEffects = new SceneEffects(current.input(), effects);
        if (effects != null) {
            VfxEngine.INSTANCE.drawPrepared(effects, current.modelView(), capture.linearTarget(), true);
            if (effects.needsSceneColor()) capture.prepareMaterialSceneColor();
        }
        capture.resumeNativeTransparency();
    }

    private static HostFrameContext hostFrameContext() {
        LevelFrame current = levelFrame;
        if (current == null || current.input().level() != Minecraft.getInstance().level) return null;
        var camera = current.input().camera();
        return new HostFrameContext(current.identity(), current.input().level(), current.modelView(),
                current.projection(), camera.x, camera.y, camera.z, current.width(), current.height(), true);
    }

    public static boolean hasShaderpackOpaqueWork(@Nullable Matrix4fc viewMatrix) {
        if (VfxEngine.INSTANCE.hasActiveSceneStages()) return true;
        LevelFrame current = levelFrame;
        if (current == null || current.photon() == null) return false;
        var photon = current.photon();
        Matrix4fc effectiveView = viewMatrix != null ? viewMatrix : current.modelView();
        boolean[] hasOpaque = new boolean[1];
        IrisParticleRenderScope.withParticlePrograms(() ->
                PhotonRenderOutput.withModelView(effectiveView, () ->
                        hasOpaque[0] = photon.hasStage(PhotonStage.AFTER_OPAQUE_FEATURES)));
        return hasOpaque[0];
    }

    private static boolean hasPendingTranslucentPhoton(PhotonWorldRenderState.Frame photon, Matrix4fc viewMatrix) {
        boolean[] pending = new boolean[1];
        PhotonRenderOutput.withModelView(viewMatrix, () ->
                pending[0] = photon.hasStage(PhotonStage.AFTER_TRANSLUCENT_PARTICLES)
                        || photon.hasStage(PhotonStage.DEFERRED));
        return pending[0];
    }

    private static void renderVirtualTexturePagesOnce(LevelFrame frame) {
        if (rvtRenderedFrame == frame.identity()) return;
        rvtRenderedFrame = frame.identity();
        VfxEngine.INSTANCE.renderVirtualTexturePages(frame.input());
    }

    private static void drawShaderpackFrame(ShaderpackFrame frame, EnumSet<TranslucencyPass> immediateStages, boolean moreStagesFollow) {
        RenderSystem.assertOnRenderThread();
        LevelFrame current = levelFrame;
        if (current == null || current.identity() != frame.hostFrameIdentity()
                || current.input().level() != frame.worldIdentity())
            throw new IllegalStateException("The shaderpack callback does not belong to the current level frame");
        if (drawnExternalFrame == frame.hostFrameIdentity())
            throw new IllegalStateException("The shaderpack level frame was submitted more than once");
        var capture = WorldHdrCapture.INSTANCE;
        IrisParticleRenderScope.withParticlePrograms(() -> {
            capture.beginExternalComposition(frame, false);
            SceneEffects prepared = sceneEffects;
            if (prepared == null) capture.prepareFogView(frame.viewMatrix(), current.input().camera(), current.input().level(),
                    Minecraft.getInstance().gameRenderer.mainCamera().entity());
            withCapturedProjection(() -> {
                renderVirtualTexturePagesOnce(current);
                if (prepared == null) VfxEngine.INSTANCE.draw(current.input(), frame.viewMatrix(), capture.linearTarget());
                else {
                    if (prepared.input() != current.input()) throw new IllegalStateException("Shaderpack effects belong to another level frame");
                    if (prepared.effects() != null) {
                        VfxEngine.INSTANCE.drawPrepared(prepared.effects(), frame.viewMatrix(), capture.linearTarget(), false);
                        prepared.effects().finish();
                    }
                    sceneEffects = null;
                }
                var photon = current.photon();
                if (photon != null && hasPendingTranslucentPhoton(photon, frame.viewMatrix())) {
                    capture.drawExternalPhoton(frame.viewMatrix(), () -> {
                        photon.execute(PhotonStage.AFTER_TRANSLUCENT_PARTICLES);
                        PhotonDeferredLayer.compositePending();
                    });
                }
                capture.finishExternalCompositionStages(frame, immediateStages, !moreStagesFollow);
            });
        });
        drawnExternalFrame = frame.hostFrameIdentity();
    }

    private static void drawShaderpackDeferredStage(ShaderpackFrame frame, EnumSet<TranslucencyPass> stages, boolean finalStage) {
        RenderSystem.assertOnRenderThread();
        LevelFrame current = levelFrame;
        if (current == null || current.identity() != frame.hostFrameIdentity()
                || current.input().level() != frame.worldIdentity())
            throw new IllegalStateException("The shaderpack deferred callback does not belong to the current level frame");
        if (drawnExternalFrame != frame.hostFrameIdentity())
            throw new IllegalStateException("The shaderpack deferred stage requires its before-composite frame");
        IrisParticleRenderScope.withParticlePrograms(() ->
                WorldHdrCapture.INSTANCE.finishExternalDeferredComposition(frame, stages, finalStage));
    }

    private static void drawOpaqueShaderpackFrame(ShaderpackFrame frame) {
        RenderSystem.assertOnRenderThread();
        LevelFrame current = levelFrame;
        if (current == null || current.identity() != frame.hostFrameIdentity() || current.input().level() != frame.worldIdentity())
            throw new IllegalStateException("The opaque shaderpack callback does not belong to the current level frame");
        if (sceneEffects != null || drawnExternalFrame == frame.hostFrameIdentity())
            throw new IllegalStateException("The opaque shaderpack frame was submitted more than once");
        var capture = WorldHdrCapture.INSTANCE;
        IrisParticleRenderScope.withParticlePrograms(() -> {
            capture.beginExternalComposition(frame, true);
            capture.prepareFogView(frame.viewMatrix(), current.input().camera(), current.input().level(),
                    Minecraft.getInstance().gameRenderer.mainCamera().entity());
            withCapturedProjection(() -> {
                renderVirtualTexturePagesOnce(current);
                var photon = current.photon();
                if (photon != null) {
                    boolean[] hasOpaque = new boolean[1];
                    PhotonRenderOutput.withModelView(frame.viewMatrix(), () ->
                            hasOpaque[0] = photon.hasStage(PhotonStage.AFTER_OPAQUE_FEATURES));
                    if (hasOpaque[0]) {
                        capture.drawExternalPhoton(frame.viewMatrix(),
                                () -> photon.execute(PhotonStage.AFTER_OPAQUE_FEATURES));
                        capture.noteOpaqueParticleDepthWrite();
                    }
                }
                if (VfxEngine.INSTANCE.hasActiveSceneStages()) {
                    var prepared = VfxEngine.INSTANCE.prepareFrame(current.input(), frame.viewMatrix(), capture.linearTarget());
                    sceneEffects = new SceneEffects(current.input(), prepared);
                    if (prepared != null) {
                        VfxEngine.INSTANCE.drawPrepared(prepared, frame.viewMatrix(), capture.linearTarget(), true);
                        if (prepared.needsSceneColor()) capture.prepareMaterialSceneColor();
                    }
                }
                capture.finishExternalOpaqueComposition(frame);
            });
        });
    }

    private static void withCapturedProjection(Runnable draw) {
        var capture = WorldHdrCapture.INSTANCE;
        var previousProjection = RenderSystem.getProjectionMatrixBuffer();
        var previousType = RenderSystem.getProjectionType();
        if (previousProjection == null) throw new IllegalStateException("The shaderpack callback has no host projection buffer");
        var device = RenderSystem.getDevice();
        com.mojang.blaze3d.buffers.GpuBufferSlice capturedProjection;
        try (var projection = device.createCommandEncoder().transientMemory().allocateGpuMapped(
                64, device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            Matrix4f sourceProjection = new Matrix4f();
            capture.copyProjection(sourceProjection);
            sourceProjection.get(projection.data().order(ByteOrder.nativeOrder()));
            capturedProjection = projection.slice();
        }
        RenderSystem.setProjectionMatrix(capturedProjection, ProjectionType.PERSPECTIVE);
        try {
            draw.run();
        } finally {
            RenderSystem.setProjectionMatrix(previousProjection, previousType);
        }
    }

    public static void onExtractLevelRenderState(ClientLevel level, LevelRenderState state, DeltaTracker deltaTracker) {
        double time = (state.gameTime + deltaTracker.getGameTimeDeltaPartialTick(false)) / 20.0;
        LumenClientPlatform.setFrameInput(state, VfxEngine.INSTANCE.capture(level, state.cameraRenderState, time));
    }

    public static void onAfterLevel(LevelRenderState state, Matrix4fc modelView) {
        VfxEngine.FrameInput frame = LumenClientPlatform.getFrameInput(state);
        var capture = WorldHdrCapture.INSTANCE;
        if (capture.externalSceneOwner()) {
            // External shaderpacks already composited PhotonDeferredLayer before Iris composite/final passes.
            PhotonDeferredLayer.discardPending();
            return;
        }
        capture.captureCameraProjection(state.cameraRenderState.projectionMatrix);
        capture.beginComposition();
        if (frame != null) VfxEngine.INSTANCE.renderVirtualTexturePages(frame);
        SceneEffects prepared = sceneEffects;
        if (frame != null && prepared == null && capture.composing()) capture.prepareFogView(modelView, frame.camera(), frame.level(),
                Minecraft.getInstance().gameRenderer.mainCamera().entity());
        var target = capture.composing() ? capture.linearTarget() : Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (prepared != null) {
            if (prepared.input() != frame) throw new IllegalStateException("Native effects belong to a different level frame");
            if (prepared.effects() != null) {
                VfxEngine.INSTANCE.drawPrepared(prepared.effects(), modelView, target, false);
                prepared.effects().finish();
            }
            sceneEffects = null;
        } else if (frame != null) VfxEngine.INSTANCE.draw(frame, modelView, target);
        capture.finishComposition();
        PhotonDeferredLayer.compositePending();
    }

    public static void onAfterLevelFromCurrent(LevelRenderState state) {
        LevelFrame current = levelFrame;
        if (current != null) {
            onAfterLevel(state, current.modelView());
        }
    }

    public static void onClientTickPost() {
        var level = Minecraft.getInstance().level;
        EntityLocatorAttachments.tickAll(level);
        com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onClientTick(level);
        if (level == null) {
            VfxEngine.INSTANCE.disconnected();
        } else {
            VfxEngine.INSTANCE.finishClientTick(level);
            MinecraftNiagaraAudio.INSTANCE.tickCues();
        }
    }
}
