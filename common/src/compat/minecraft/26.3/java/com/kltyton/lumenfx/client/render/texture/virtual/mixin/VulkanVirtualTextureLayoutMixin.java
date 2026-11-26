package com.kltyton.lumenfx.client.render.texture.virtual.mixin;

import com.kltyton.lumenfx.client.render.texture.virtual.VirtualTextureFeedbackLayout;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Uses a Vulkan storage buffer descriptor type for LumenFX virtual-texture feedback bindings on MC 26.3. */
@Mixin(VulkanRenderPipeline.class)
abstract class VulkanVirtualTextureLayoutMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;descriptorType(I)Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;"))
    private static VkDescriptorSetLayoutBinding lumenfx$feedbackType(
            VkDescriptorSetLayoutBinding binding, int type,
            Operation<VkDescriptorSetLayoutBinding> original,
            @Local(argsOnly = true) BackendRenderPipeline.CreateInfo createInfo,
            @Local(ordinal = 1) int index) {
        return original.call(binding, VirtualTextureFeedbackLayout.isGraphicsStorageBuffer(
                createInfo.uniforms().get(index).name())
                ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : type);
    }
}
