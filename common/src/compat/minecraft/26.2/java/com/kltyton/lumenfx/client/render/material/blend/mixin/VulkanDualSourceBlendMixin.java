package com.kltyton.lumenfx.client.render.material.blend.mixin;

import com.kltyton.lumenfx.client.render.material.blend.ParticleDualSourceBlend;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.BlendFunction;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState.Buffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Supplies Vulkan's secondary fragment output as the destination factor for LumenFX dual-source pipelines. */
@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanRenderPipeline")
abstract class VulkanDualSourceBlendMixin {
    @WrapOperation(method = "applyBlendInformation", at = @At(value = "INVOKE", target =
            "Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState$Buffer;dstColorBlendFactor(I)Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState$Buffer;"))
    private static Buffer lumenfx$colorFactor(Buffer attachment, int factor, Operation<Buffer> original,
                                              @Local(argsOnly = true) BlendFunction blend) {
        if (blend == ParticleDualSourceBlend.SCENE || blend == ParticleDualSourceBlend.SEPARATE)
            factor = VK10.VK_BLEND_FACTOR_SRC1_COLOR;
        return original.call(attachment, factor);
    }

    @WrapOperation(method = "applyBlendInformation", at = @At(value = "INVOKE", target =
            "Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState$Buffer;dstAlphaBlendFactor(I)Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState$Buffer;"))
    private static Buffer lumenfx$alphaFactor(Buffer attachment, int factor, Operation<Buffer> original,
                                              @Local(argsOnly = true) BlendFunction blend) {
        return original.call(attachment, blend == ParticleDualSourceBlend.SCENE ? VK10.VK_BLEND_FACTOR_SRC1_ALPHA : factor);
    }
}
