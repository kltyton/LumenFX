package com.kltyton.lumenfx.client.graphics.mixin;

import com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import java.nio.IntBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Fixes three upstream MC 26.2 Vulkan SPIR-V decoration issues in {@link IntermediaryShaderModule}:
 * <ul>
 *   <li>Preserves explicit fragment shader output {@code Location} decorations (MRT and dual-source blend).</li>
 *   <li>Binds vertex attributes and fragment varyings to their true source slot index {@code i} in
 *       {@code inputVariables}, preventing location shift whenever an earlier attribute/varying is unused.</li>
 *   <li>Permits {@code SpvDim3D} ({@code sampler3D}) sampled images during descriptor rebinding.</li>
 * </ul>
 */
@Mixin(IntermediaryShaderModule.class)
abstract class VulkanIntermediaryShaderMixin {
    @WrapOperation(method = "createFromSpirv", at = @At(value = "INVOKE",
            target = "Ljava/nio/IntBuffer;put(II)Ljava/nio/IntBuffer;"))
    private static IntBuffer lumenfx$keepFragmentOutputLocations(IntBuffer buffer, int offset, int location,
                                                                 Operation<IntBuffer> original) {
        if (VulkanShaderPatchState.preservingFragmentOutputLocations()) {
            return buffer;
        }
        return original.call(buffer, offset, location);
    }

    @WrapOperation(method = "rebind", at = @At(value = "INVOKE",
            target = "Ljava/nio/IntBuffer;put(II)Ljava/nio/IntBuffer;", ordinal = 0))
    private IntBuffer lumenfx$bindInputAtSourceLocation(IntBuffer buffer, int offset, int compactedLocation,
                                                        Operation<IntBuffer> original,
                                                        @Local(ordinal = 1) int sourceLocation) {
        return original.call(buffer, offset, sourceLocation);
    }

    @WrapOperation(method = "rebind", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/glsl/SpvSampler;dimensions()I"))
    private int lumenfx$allow3dSampledImages(@Coerce Object sampler, Operation<Integer> original) {
        return VulkanShaderPatchState.normalizeSampledImageDimension(original.call(sampler));
    }
}
