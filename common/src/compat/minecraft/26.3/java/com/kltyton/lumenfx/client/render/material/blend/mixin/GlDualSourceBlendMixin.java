package com.kltyton.lumenfx.client.render.material.blend.mixin;

import com.kltyton.lumenfx.client.render.material.blend.ParticleDualSourceBlend;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Supplies OpenGL's secondary fragment output as the destination factor for LumenFX dual-source pipelines on MC 26.3. */
@Mixin(GlRenderPipeline.class)
abstract class GlDualSourceBlendMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/opengl/GlConst;toGl(Lcom/mojang/renderpearl/api/pipeline/BlendFactor;)I",
            ordinal = 1))
    private int lumenfx$dualSourceColorDst(BlendFactor factor, Operation<Integer> original, @Local BlendFunction blend) {
        if (blend == ParticleDualSourceBlend.SCENE || blend == ParticleDualSourceBlend.SEPARATE) {
            return GL33C.GL_SRC1_COLOR;
        }
        return original.call(factor);
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/opengl/GlConst;toGl(Lcom/mojang/renderpearl/api/pipeline/BlendFactor;)I",
            ordinal = 3))
    private int lumenfx$dualSourceAlphaDst(BlendFactor factor, Operation<Integer> original, @Local BlendFunction blend) {
        if (blend == ParticleDualSourceBlend.SCENE) {
            return GL15.GL_SRC1_ALPHA;
        }
        return original.call(factor);
    }
}
