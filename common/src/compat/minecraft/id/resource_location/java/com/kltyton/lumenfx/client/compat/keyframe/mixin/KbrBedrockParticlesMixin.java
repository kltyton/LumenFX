package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko4KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes KBR native Bedrock animation particle keyframes to LumenFX when not defined in KBR's MolangParticleEngine. */
@Pseudo
@Mixin(targets = "com.kltyton.bedrockruntime.client.addon.animation.BedrockAnimationParticles", remap = false)
public abstract class KbrBedrockParticlesMixin {
    @Inject(method = "create", at = @At("RETURN"), remap = false)
    private static void lumenfx$fallbackToLumenFx(
            @Coerce Object actor, @Coerce Object position, String particle,
            @Coerce Object expression, boolean bound, CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() == null) {
            Gecko4KeyframeBridge.onKbrAnimationParticleFallback(actor, position, particle, expression, bound);
        }
    }
}
