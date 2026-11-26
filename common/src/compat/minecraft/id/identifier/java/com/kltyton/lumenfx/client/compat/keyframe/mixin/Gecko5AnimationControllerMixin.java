package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko5KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hooks GeckoLib 5 AnimationController particle keyframe dispatch and controller reset cleanup. */
@Pseudo
@Mixin(targets = "com.geckolib.animation.AnimationController", remap = false)
public abstract class Gecko5AnimationControllerMixin {
    @Inject(method = "checkControllerState", at = @At("HEAD"), remap = false)
    private void lumenfx$ensureParticleHandler(CallbackInfoReturnable<Boolean> cir) {
        Gecko5KeyframeBridge.ensureParticleHandlerWrapped(this);
    }

    @Inject(method = "setParticleKeyframeHandler", at = @At("TAIL"), remap = false)
    private void lumenfx$wrapCustomParticleHandler(CallbackInfoReturnable<Object> cir) {
        Gecko5KeyframeBridge.ensureParticleHandlerWrapped(this);
    }

    @Inject(method = "reset", at = @At("HEAD"), remap = false)
    private void lumenfx$onControllerReset(CallbackInfo ci) {
        Gecko5KeyframeBridge.onControllerReset(this);
    }
}
