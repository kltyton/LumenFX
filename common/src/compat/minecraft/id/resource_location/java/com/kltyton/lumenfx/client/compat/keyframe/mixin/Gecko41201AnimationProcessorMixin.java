package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko4KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks the currently rendered GeoAnimatable right before GeckoLib 4 (1.20.1) walks bone matrices. */
@Pseudo
@Mixin(targets = "software.bernie.geckolib.core.animation.AnimationProcessor", remap = false)
public abstract class Gecko41201AnimationProcessorMixin {
    @Inject(method = "tickAnimation", at = @At("HEAD"), remap = false)
    private void lumenfx$captureActiveAnimatable(
            @Coerce Object animatable, @Coerce Object model, @Coerce Object manager,
            double animTime, @Coerce Object state, boolean crashWhenCantFindBone, CallbackInfo ci) {
        Gecko4KeyframeBridge.setActiveRenderAnimatable(animatable);
    }
}
