package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko5KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures GeckoLib 5 source particle keyframe metadata (locator, preEffectScript, bindToActor) on bake. */
@Pseudo
@Mixin(targets = "com.geckolib.loading.definition.animation.ActorAnimationParticleEffect", remap = false)
public abstract class Gecko5ActorParticleEffectMixin {
    @Inject(method = "bake", at = @At("RETURN"), remap = false)
    private void lumenfx$captureBakedParticle(double timestamp, CallbackInfoReturnable<Object> cir) {
        Gecko5KeyframeBridge.recordBakedParticleEffect(this, cir.getReturnValue());
    }
}
