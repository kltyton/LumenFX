package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the real prepared render type already created by the native group, without preparing again. */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class RenderTypeFeatureRendererGroupMixin {
    @Inject(method = "getOrAddDraw", at = @At("RETURN"))
    private void lumenfx$bindPrepared(
            RenderType renderType,
            CallbackInfoReturnable<StagedVertexBuffer.Draw> cir,
            @Local PreparedRenderType prepared) {
        MotionHistory.INSTANCE.bindDrawPrepared(cir.getReturnValue(), prepared);
    }
}
