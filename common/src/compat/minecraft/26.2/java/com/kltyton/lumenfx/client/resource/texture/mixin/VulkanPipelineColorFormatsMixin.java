package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.render.texture.NativeColorFormats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import java.nio.IntBuffer;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vulkan dynamic-rendering formats match explicitly registered physical color attachments. */
@Mixin(VulkanRenderPipeline.class)
abstract class VulkanPipelineColorFormatsMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE", target =
            "Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;pColorAttachmentFormats(Ljava/nio/IntBuffer;)Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;"))
    private static VkPipelineRenderingCreateInfoKHR lumenfx$colorFormats(VkPipelineRenderingCreateInfoKHR info, IntBuffer formats,
            Operation<VkPipelineRenderingCreateInfoKHR> original, @Local(argsOnly = true) RenderPipeline pipeline) {
        NativeColorFormats.applyVulkan(pipeline, formats);
        return original.call(info, formats);
    }
}
