package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.graphics.RenderPearlCompat;
import com.kltyton.lumenfx.client.render.texture.NativeColorFormats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import java.nio.IntBuffer;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Resolves physical color formats from the source pipeline before Vulkan compilation. */
@Mixin(VulkanRenderPipeline.class)
abstract class VulkanPipelineColorFormatsMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE", target =
            "Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;pColorAttachmentFormats(Ljava/nio/IntBuffer;)Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;"))
    private static VkPipelineRenderingCreateInfoKHR lumenfx$colorFormats(
            VkPipelineRenderingCreateInfoKHR info, IntBuffer formats, Operation<VkPipelineRenderingCreateInfoKHR> original,
            @Local(argsOnly = true) BackendRenderPipeline.CreateInfo createInfo) {
        var pipeline = RenderPearlCompat.getSource(createInfo);
        if (pipeline != null) NativeColorFormats.applyVulkan(pipeline, formats);
        return original.call(info, formats);
    }
}

