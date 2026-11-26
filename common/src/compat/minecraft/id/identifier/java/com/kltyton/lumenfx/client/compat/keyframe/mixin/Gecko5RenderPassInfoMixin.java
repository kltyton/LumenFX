package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko5KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Attaches LumenFX locator position listeners to GeckoLib 5 render passes when effects are bound to an animatable. */
@Pseudo
@Mixin(targets = "com.geckolib.renderer.base.RenderPassInfo", remap = false)
public abstract class Gecko5RenderPassInfoMixin {
    @Inject(method = "create", at = @At("RETURN"), remap = false)
    private static void lumenfx$attachLocatorListeners(CallbackInfoReturnable<Object> cir) {
        Gecko5KeyframeBridge.attachLocatorListeners(cir.getReturnValue());
    }

    @Inject(method = "renderPosed", at = @At("HEAD"), remap = false)
    private void lumenfx$enterRenderPass(CallbackInfo ci) {
        Gecko5KeyframeBridge.enterRenderPass(this);
    }

    @Inject(method = "renderPosed", at = @At("RETURN"), remap = false)
    private void lumenfx$exitRenderPass(CallbackInfo ci) {
        Gecko5KeyframeBridge.exitRenderPass(this);
    }
}
