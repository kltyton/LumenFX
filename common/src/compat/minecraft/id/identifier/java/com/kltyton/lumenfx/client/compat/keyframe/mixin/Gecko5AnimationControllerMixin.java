package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko5KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks GeckoLib 5 AnimationController reset cleanup for any LumenFX effects bound to that controller
 * (either via manual {@code LumenFxKeyframeBridge.trigger(...)} in a callback or via KBR coexistence).
 * Intentionally does NOT wrap {@code particleKeyframeHandler} so standalone GeckoLib stays callback-only.
 */
@Pseudo
@Mixin(targets = "com.geckolib.animation.AnimationController", remap = false)
public abstract class Gecko5AnimationControllerMixin {
    @Inject(method = "reset", at = @At("HEAD"), remap = false)
    private void lumenfx$onControllerReset(CallbackInfo ci) {
        Gecko5KeyframeBridge.onControllerReset(this);
    }
}
