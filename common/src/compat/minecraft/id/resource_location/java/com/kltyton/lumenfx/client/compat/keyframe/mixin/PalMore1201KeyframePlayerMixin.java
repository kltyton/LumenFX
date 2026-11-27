package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.keyframe.PalKeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pure soft-compat mixin for 1.20.1 {@code PlayerAnimationLibraryMoreRotation} ({@code PalMoreKeyframePlayer}).
 * Preserves PALMore's callback-only particle keyframe contract (never auto-spawns particles);
 * handles lifecycle cleanup when playback ends and fallback tick pose sampling.
 */
@Pseudo
@Mixin(targets = "com.kltyton.playeranimationlibrarymorerotation.client.PalMoreKeyframePlayer", remap = false)
public abstract class PalMore1201KeyframePlayerMixin {
    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void lumenfx$onTickTail(CallbackInfo ci) {
        PalKeyframeBridge.onLegacyPalMoreTick(this);
    }
}
