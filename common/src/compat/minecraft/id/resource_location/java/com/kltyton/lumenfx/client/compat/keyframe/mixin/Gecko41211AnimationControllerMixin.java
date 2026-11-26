package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko4KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hooks GeckoLib 4 (1.21.1 package) AnimationController particle keyframes and reset cleanup. */
@Pseudo
@Mixin(targets = "software.bernie.geckolib.animation.AnimationController", remap = false)
public abstract class Gecko41211AnimationControllerMixin {
    @Inject(method = "process", at = @At("HEAD"), remap = false)
    private void lumenfx$ensureParticleHandler(CallbackInfo ci) {
        Gecko4KeyframeBridge.ensureParticleHandlerWrapped(this, false);
    }

    @Inject(method = "setParticleKeyframeHandler", at = @At("TAIL"), remap = false)
    private void lumenfx$wrapCustomParticleHandler(CallbackInfoReturnable<Object> cir) {
        Gecko4KeyframeBridge.ensureParticleHandlerWrapped(this, false);
    }

    @Inject(method = "resetEventKeyFrames", at = @At("HEAD"), remap = false)
    private void lumenfx$onResetEventKeyFrames(CallbackInfo ci) {
        Gecko4KeyframeBridge.onControllerReset(this, false);
    }
}
