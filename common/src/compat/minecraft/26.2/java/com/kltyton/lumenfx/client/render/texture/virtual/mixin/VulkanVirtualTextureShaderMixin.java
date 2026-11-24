package com.kltyton.lumenfx.client.render.texture.virtual.mixin;

import com.kltyton.lumenfx.client.render.texture.virtual.VirtualTextureFeedbackLayout;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.SpvUniformBuffer;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Includes the source feedback buffer in the host's existing graphics descriptor rebinding. */
@Mixin(GlslCompiler.class)
abstract class VulkanVirtualTextureShaderMixin {
    @WrapOperation(method = "createIntermediary", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;createFromSpirv(Ljava/lang/String;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;"))
    private IntermediaryShaderModule lumenfx$reflectFeedback(String filename, ByteBuffer spirv,
            Operation<IntermediaryShaderModule> original, @Local(argsOnly = true, ordinal = 1) String source) {
        var module = original.call(filename, spirv);
        try {
            for (String bufferName : VirtualTextureFeedbackLayout.GRAPHICS_STORAGE_BUFFERS) {
                if (source.contains("buffer " + bufferName)) {
                    int offset = VirtualTextureFeedbackLayout.bindingOffset(spirv, bufferName);
                    if (offset >= 0) module.uniformBuffers().add(new SpvUniformBuffer(bufferName, offset));
                }
            }
        } catch (RuntimeException | Error failure) {
            module.close();
            throw failure;
        }
        return module;
    }
}
