package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.keyframe.PalKeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame smooth bone + bend world-pose sampling for 1.20.1 {@code PalMoreKeyframePlayer}
 * (which inherits {@code setupAnim(float)} from {@code KeyframeAnimationPlayer}).
 * Only enabled by {@code LumenFxKeyframeMixinPlugin} when {@code PalMoreKeyframePlayer} exists on the classpath.
 */
@Pseudo
@Mixin(targets = "dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer", remap = false)
public abstract class PalMore1201PoseSampleMixin {
    @Inject(method = "setupAnim", at = @At("TAIL"), remap = false)
    private void lumenfx$samplePalMorePose(float tickDelta, CallbackInfo ci) {
        PalKeyframeBridge.onLegacyPalMoreSetupAnim(this, tickDelta);
    }
}
