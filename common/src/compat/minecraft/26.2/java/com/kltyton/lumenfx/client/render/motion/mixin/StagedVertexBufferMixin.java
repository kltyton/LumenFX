package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Binds real Draw consumers to the active submit scope and validates accumulated GPU counts. */
@Mixin(StagedVertexBuffer.class)
public abstract class StagedVertexBufferMixin {
    @Inject(method = "getVertexBuilder", at = @At("RETURN"), cancellable = true)
    private void lumenfx$wrapDraw(
            StagedVertexBuffer.Draw draw, CallbackInfoReturnable<VertexConsumer> cir) {
        VertexConsumer wrapped = MotionHistory.INSTANCE.wrapDrawConsumer(draw, cir.getReturnValue());
        if (wrapped != null) {
            cir.setReturnValue(wrapped);
        }
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void lumenfx$validateDraws(CallbackInfo ci) {
        MotionHistory.INSTANCE.validateDrawCounts();
    }

    @Inject(method = "getExecuteInfo", at = @At("RETURN"))
    private void lumenfx$recordExecution(
            StagedVertexBuffer.Draw draw,
            CallbackInfoReturnable<StagedVertexBuffer.ExecuteInfo> cir) {
        MotionHistory.INSTANCE.recordExecuteInfo(draw, cir.getReturnValue());
    }
}
