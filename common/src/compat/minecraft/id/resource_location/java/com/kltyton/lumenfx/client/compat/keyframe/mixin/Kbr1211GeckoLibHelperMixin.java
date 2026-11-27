package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko4KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents KBR / KBR-Lite (1.21.1 GeckoLib 4) from throwing on keyframes claimed by LumenFX or unregistered
 * in KBR's MolangParticleEngine, independent of Mixin application order.
 */
@Pseudo
@Mixin(targets = "com.kltyton.bedrockruntime.client.particle.api.geckolib.GeckoLibHelper", remap = false)
public abstract class Kbr1211GeckoLibHelperMixin {
    @Inject(
            method = "processParticleEffect(Lsoftware/bernie/geckolib/animatable/GeoAnimatable;Lsoftware/bernie/geckolib/animation/AnimationController;Lsoftware/bernie/geckolib/animation/keyframe/event/data/ParticleKeyframeData;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void lumenfx$skipClaimedOrUnknownParticle(
            @Coerce Object animatable, @Coerce Object controller, @Coerce Object keyframeData,
            CallbackInfoReturnable<Boolean> cir) {
        if (Gecko4KeyframeBridge.shouldCancelKbrGeckoLibHelper(animatable, controller, keyframeData, false)) {
            cir.setReturnValue(false);
        }
    }
}
