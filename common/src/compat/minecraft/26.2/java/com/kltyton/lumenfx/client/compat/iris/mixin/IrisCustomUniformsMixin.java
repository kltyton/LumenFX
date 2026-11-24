package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.kltyton.lumenfx.client.compat.iris.motion.IrisMotionShaderClone;
import net.irisshaders.iris.gl.uniform.LocationalUniformHolder;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks the clone's custom uniform mapping for its exact lifecycle. */
@Mixin(value = CustomUniforms.class, remap = false)
abstract class IrisCustomUniformsMixin {
    @Inject(method = "mapholderToPass", at = @At("RETURN"), remap = false)
    private void lumenfx$recordMotionMapping(LocationalUniformHolder holder, Object pass, CallbackInfo callback) {
        IrisMotionShaderClone.recordUniformMapping((CustomUniforms) (Object) this, pass);
    }
}
