package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.kltyton.lumenfx.client.event.ClientVfxEvents;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuBatchDispatcher.TickStage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.DeltaTracker;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.joml.Matrix4fc;
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
    @Shadow @Final private LevelRenderState levelRenderState;

    @WrapMethod(method = "render")
    private void lumenfx$levelFrame(GraphicsResourceAllocator allocator, DeltaTracker tracker,
            boolean renderOutline, CameraRenderState camera, Matrix4fc modelView, GpuBufferSlice fog,
            Vector4f fogColor, boolean renderSky, Operation<Void> original) {
        var previous = ClientVfxEvents.beginLevelFrame(levelRenderState, modelView);
        Throwable failure = null;
        try {
            ClientVfxEvents.dispatchNativeGpu(TickStage.PRE_INIT_VIEWS);
            original.call(allocator, tracker, renderOutline, camera, modelView, fog, fogColor, renderSky);
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
