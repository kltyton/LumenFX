package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko5KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents KBR / KBR-Lite's GeckoLib 5 particle dispatcher from parsing or throwing on keyframes already
 * claimed by LumenFX or not registered in KBR's MolangParticleEngine.
 */
@Pseudo
@Mixin(targets = "com.kltyton.bedrockruntime.client.particle.api.geckolib.GeckoLibHelper", remap = false)
public abstract class KbrGeckoLibHelperMixin {
    @Inject(method = "processParticleEffect", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lumenfx$skipClaimedOrUnknownParticle(@Coerce Object event, CallbackInfo ci) {
        if (Gecko5KeyframeBridge.shouldCancelKbrGeckoLibHelper(event)) {
            ci.cancel();
        }
    }
}
