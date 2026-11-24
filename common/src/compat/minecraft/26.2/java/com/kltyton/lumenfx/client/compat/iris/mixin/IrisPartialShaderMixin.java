package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.kltyton.lumenfx.client.compat.iris.motion.IrisMotionShaderClone;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies a private program only while the original Iris factory is cloning it. */
@Mixin(value = PartialShader.class, remap = false)
abstract class IrisPartialShaderMixin {
    @Inject(method = "getFinally", at = @At("HEAD"), cancellable = true, remap = false)
    private void lumenfx$motionProgram(CallbackInfoReturnable<Integer> callback) {
        int replacement = IrisMotionShaderClone.replacement((PartialShader) (Object) this);
        if (replacement != 0) callback.setReturnValue(replacement);
    }
}
