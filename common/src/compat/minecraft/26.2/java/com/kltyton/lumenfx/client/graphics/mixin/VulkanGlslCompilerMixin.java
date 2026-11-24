package com.kltyton.lumenfx.client.graphics.mixin;

import com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Marks fragment SPIR-V reflection so explicit {@code layout(location = N)} color attachments are
 * preserved, and permits {@code sampler3D} ({@code SpvDim3D}) bindings in graphics shaders.
 */
@Mixin(GlslCompiler.class)
abstract class VulkanGlslCompilerMixin {
    @WrapOperation(method = "createIntermediary", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;createFromSpirv(Ljava/lang/String;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;"))
    private IntermediaryShaderModule lumenfx$preserveFragmentOutputs(String filename, ByteBuffer spirv,
                                                                     Operation<IntermediaryShaderModule> original,
                                                                     @Local(argsOnly = true) ShaderType type) {
        if (type != ShaderType.FRAGMENT) {
            return original.call(filename, spirv);
        }
        boolean previous = VulkanShaderPatchState.enterFragmentOutputPreservation();
        try {
            return original.call(filename, spirv);
        } finally {
            VulkanShaderPatchState.exitFragmentOutputPreservation(previous);
        }
    }

    @WrapOperation(method = "addToBindGroup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/glsl/SpvSampler;dimensions()I"))
    private static int lumenfx$allow3dSampledImages(@Coerce Object sampler, Operation<Integer> original) {
        return VulkanShaderPatchState.normalizeSampledImageDimension(original.call(sampler));
    }
}
