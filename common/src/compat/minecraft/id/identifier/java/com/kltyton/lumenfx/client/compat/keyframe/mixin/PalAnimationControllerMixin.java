package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.keyframe.PalKeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pure soft-compat mixin for Player Animation Library (PAL 1.2.x on 26.x).
 * Preserves PAL's callback-only particle keyframe semantics (never auto-spawns particles);
 * publishes bent player bone world poses to LumenFX when a mod triggers effects via
 * {@code LumenFxKeyframeBridge.trigger(...)} and cleans up on controller reset/stop.
 */
@Pseudo
@Mixin(targets = "com.zigythebird.playeranimcore.animation.AnimationController", remap = false)
public abstract class PalAnimationControllerMixin {
    @Inject(method = "process", at = @At("RETURN"), remap = false)
    private void lumenfx$sampleTrackedPlayerBones(@Coerce Object animationData, CallbackInfo ci) {
        PalKeyframeBridge.onPalControllerProcessed(this, animationData);
    }

    @Inject(method = "resetEventKeyFrames", at = @At("HEAD"), remap = false)
    private void lumenfx$onResetEventKeyFrames(CallbackInfo ci) {
        PalKeyframeBridge.onPalControllerReset(this);
    }

    @Inject(method = "stop", at = @At("HEAD"), remap = false)
    private void lumenfx$onStop(CallbackInfo ci) {
        PalKeyframeBridge.onPalControllerReset(this);
    }
}
