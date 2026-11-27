package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko4KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks GeckoLib 4 (1.21.1 package) AnimationController reset cleanup and wraps each particle handler call in a
 * {@code try/finally} per-event dispatch scope (re-throwing any callback exception unchanged).
 * Intentionally does NOT mutate {@code particleKeyframeHandler} so standalone GeckoLib stays callback-only
 * and KBR coexistence never depends on Mixin application order.
 */
@Pseudo
@Mixin(targets = "software.bernie.geckolib.animation.AnimationController", remap = false)
public abstract class Gecko41211AnimationControllerMixin {
    @Inject(method = "resetEventKeyFrames", at = @At("HEAD"), remap = false)
    private void lumenfx$onResetEventKeyFrames(CallbackInfo ci) {
        Gecko4KeyframeBridge.onControllerReset(this);
    }

    @Redirect(
            method = "processCurrentAnimation",
            at = @At(
                    value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/animation/AnimationController$ParticleKeyframeHandler;handle(Lsoftware/bernie/geckolib/animation/keyframe/event/ParticleKeyframeEvent;)V"
            ),
            remap = false
    )
    private void lumenfx$dispatchParticleKeyframe(@Coerce Object handler, @Coerce Object event) {
        Gecko4KeyframeBridge.dispatchWithScope(this, handler, event, false);
    }
}
