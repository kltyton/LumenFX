package com.kltyton.lumenfx.client.graphics.mixin;

import com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Permits {@code sampler3D} ({@code SpvDim3D}) sampled image bindings in MC 26.3 RenderPearl
 * graphics shaders. RenderPearl's SPIRV-Cross reflection preserves explicit fragment output and
 * vertex input locations natively.
 */
@Mixin(PipelineBuilder.class)
abstract class VulkanGlslCompilerMixin {
    @WrapOperation(method = "generateBackendCreateInfo", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/api/SpvModule$Reflection$Type;dimensions()I"))
    private int lumenfx$allow3dSampledImages(SpvModule.Reflection.Type type, Operation<Integer> original) {
        return VulkanShaderPatchState.normalizeSampledImageDimension(original.call(type));
    }
}
