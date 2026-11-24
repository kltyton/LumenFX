package com.kltyton.lumenfx.client.render.texture.virtual.mixin;

import com.kltyton.lumenfx.client.render.texture.virtual.VirtualTextureFeedbackLayout;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import java.util.List;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The host's reflected feedback handle uses a Vulkan storage buffer descriptor. */
@Mixin(VulkanBindGroupLayout.class)
abstract class VulkanVirtualTextureLayoutMixin {
    @WrapOperation(method = "create", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;descriptorType(I)Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;"))
    private static VkDescriptorSetLayoutBinding lumenfx$feedbackType(VkDescriptorSetLayoutBinding binding, int type,
            Operation<VkDescriptorSetLayoutBinding> original, @Local(argsOnly = true) List<VulkanBindGroupLayout.Entry> entries, @Local int index) {
        return original.call(binding, VirtualTextureFeedbackLayout.isGraphicsStorageBuffer(entries.get(index).name())
                ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : type);
    }
}
