package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.kltyton.lumenfx.client.compat.iris.motion.IrisShaderSources;
import com.kltyton.lumenfx.client.compat.iris.particle.KbrIrisParticleCoverage;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
abstract class IrisProgramDeletionMixin {
    @Inject(method = "glDeleteProgram(I)V", at = @At("HEAD"))
    private static void lumenfx$forgetDeletedProgram(int program, CallbackInfo callback) {
        IrisShaderSources.remove(program);
        KbrIrisParticleCoverage.removeProgram(program);
    }
}
