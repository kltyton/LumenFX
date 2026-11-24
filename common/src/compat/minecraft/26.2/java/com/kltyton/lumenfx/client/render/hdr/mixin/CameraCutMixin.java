package com.kltyton.lumenfx.client.render.hdr.mixin;

import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidates scene history when the host explicitly resets its camera state. */
@Mixin(Camera.class)
abstract class CameraCutMixin {
    @Inject(method = "reset", at = @At("HEAD"))
    private void lumenfx$cameraCut(CallbackInfo callback) {
        WorldHdrCapture.INSTANCE.cameraCut();
    }
}
