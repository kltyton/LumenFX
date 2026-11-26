package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.kltyton.lumenfx.client.event.ClientVfxEvents;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuBatchDispatcher.TickStage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scopes level submissions and schedules Niagara at the native level render boundaries. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    private static final String MAIN_PASS = "lambda$addMainPass$0";
    private static final String FEATURES = "(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
            + "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;";
    private static final String PASS = "Lcom/mojang/renderpearl/api/commands/RenderPass;)V";
    @Shadow @Final private LevelRenderState levelRenderState;

    @WrapMethod(method = "render")
    private void lumenfx$levelFrame(GraphicsResourceAllocator allocator, boolean renderOutline, CameraRenderState camera,
            GpuBufferSlice fog, Vector4f fogColor, boolean renderSky, boolean consistentDepthRequired,
            Operation<Void> original) {
        var previous = ClientVfxEvents.beginLevelFrame(levelRenderState, camera.viewRotationMatrix);
        Throwable failure = null;
        try {
            ClientVfxEvents.dispatchNativeGpu(TickStage.PRE_INIT_VIEWS);
            original.call(allocator, renderOutline, camera, fog, fogColor, renderSky, consistentDepthRequired);
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try { ClientVfxEvents.endLevelFrame(previous); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) throw cleanup;
                if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
    private void lumenfx$afterViewPreparation(CallbackInfo callback) {
        ClientVfxEvents.dispatchNativeGpu(TickStage.POST_INIT_VIEWS);
    }

    @WrapOperation(method = MAIN_PASS, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeSolid" + FEATURES + PASS))
    private void lumenfx$afterSolid(LevelRenderer self, ChunkSectionsToRender chunks,
            FeatureRenderDispatcher.PreparedFrame features, RenderPass pass, Operation<Void> original,
            @Share("lumenfx$splitMainPass") LocalBooleanRef split) {
        original.call(self, chunks, features, pass);
        if (ClientVfxEvents.needsNativeOpaqueStage()) {
            pass.close();
            ClientVfxEvents.onAfterOpaqueFeatures(this.levelRenderState);
            split.set(true);
        }
    }

    @WrapOperation(method = MAIN_PASS, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency" + FEATURES + PASS))
    private void lumenfx$afterClassicTransparency(LevelRenderer self, ChunkSectionsToRender chunks,
            FeatureRenderDispatcher.PreparedFrame features, RenderPass pass, Operation<Void> original,
            @Local RenderTarget mainTarget, @Share("lumenfx$splitMainPass") LocalBooleanRef split) {
        if (!split.get()) {
            original.call(self, chunks, features, pass);
            return;
        }
        // Reopen the same main attachments with load semantics after LumenFX has recorded its own passes.
        try (var resumed = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Main translucency", mainTarget.getColorTextureView(), Optional.empty(),
                mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(resumed);
            original.call(self, chunks, features, resumed);
        }
        ClientVfxEvents.onAfterTranslucentParticles(this.levelRenderState);
    }

    @Inject(method = "executeOit", at = @At("RETURN"))
    private void lumenfx$afterOit(CallbackInfo callback) {
        ClientVfxEvents.onAfterTranslucentParticles(this.levelRenderState);
    }

    @WrapMethod(method = "submitFeatures")
    private void lumenfx$levelSubmission(
            LevelRenderState state,
            SubmitNodeCollector collector,
            boolean renderOutline,
            Operation<Void> original) {
        MotionHistory.INSTANCE.beginLevelSubmission(collector);
        try {
            original.call(state, collector, renderOutline);
        } finally {
            MotionHistory.INSTANCE.endLevelSubmission();
        }
    }
}
