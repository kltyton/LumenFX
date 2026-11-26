package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko5KeyframeBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures 3D locator orientation from GeckoLib 5 posed locator matrices right before position listeners fire. */
@Pseudo
@Mixin(targets = "com.geckolib.cache.model.GeoLocator", remap = false)
public abstract class Gecko5GeoLocatorMixin {
    @Inject(
            method = "updatePositionListeners",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/geckolib/util/RenderUtil;providePositionsToListeners(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/geckolib/renderer/base/RenderPassInfo;[Lcom/geckolib/renderer/base/RenderPassInfo$BonePositionListener;)V"
            ),
            remap = false
    )
    private void lumenfx$captureLocatorPose(PoseStack poseStack, @Coerce Object renderPassInfo, CallbackInfo ci) {
        Gecko5KeyframeBridge.captureLocatorPose(this, poseStack, renderPassInfo);
    }
}
