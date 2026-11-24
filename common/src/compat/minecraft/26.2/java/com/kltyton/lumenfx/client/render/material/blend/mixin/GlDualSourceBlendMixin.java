package com.kltyton.lumenfx.client.render.material.blend.mixin;

import com.kltyton.lumenfx.client.render.material.blend.ParticleDualSourceBlend;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.BlendFunction;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Supplies OpenGL's secondary fragment output as the destination factor for LumenFX dual-source pipelines. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
abstract class GlDualSourceBlendMixin {
    @WrapOperation(method = "applyPipelineState", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_blendFuncSeparate(IIII)V"))
    private void lumenfx$dualSourceFactors(int sourceColor, int destinationColor, int sourceAlpha, int destinationAlpha,
                                           Operation<Void> original, @Local BlendFunction blend) {
        if (blend == ParticleDualSourceBlend.SCENE || blend == ParticleDualSourceBlend.SEPARATE)
            destinationColor = GL33C.GL_SRC1_COLOR;
        if (blend == ParticleDualSourceBlend.SCENE) destinationAlpha = GL15.GL_SRC1_ALPHA;
        original.call(sourceColor, destinationColor, sourceAlpha, destinationAlpha);
    }
}
