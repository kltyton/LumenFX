package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko4KeyframeBridge;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Enables world-matrix tracking on actively bound GeckoLib 4 bones/locators and forwards 3D poses to LumenFX. */
@Pseudo
@Mixin(targets = "software.bernie.geckolib.cache.object.GeoBone", remap = false)
public abstract class Gecko4GeoBoneMixin {
    @Inject(method = "isTrackingMatrices", at = @At("RETURN"), cancellable = true, remap = false)
    private void lumenfx$forceTrackActiveLocators(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && Gecko4KeyframeBridge.shouldTrackBone(this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setWorldSpaceMatrix", at = @At("TAIL"), remap = false)
    private void lumenfx$onWorldSpaceMatrix(Matrix4f matrix, CallbackInfo ci) {
        Gecko4KeyframeBridge.onBoneWorldSpaceMatrix(this, matrix);
    }
}
