package com.kltyton.lumenfx.client.render.texture.virtual.mixin;

import com.kltyton.lumenfx.client.render.texture.virtual.VirtualTextureFeedbackLayout;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Writes the actual storage descriptor while retaining RenderPearl's descriptor push and buffer lifetime. */
@Mixin(VulkanRenderPass.class)
abstract class VulkanVirtualTextureRenderPassMixin {
    @WrapOperation(method = "pushDescriptors", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkWriteDescriptorSet;descriptorType(I)Lorg/lwjgl/vulkan/VkWriteDescriptorSet;", ordinal = 0))
    private VkWriteDescriptorSet lumenfx$feedbackWriteType(
            VkWriteDescriptorSet write, int type,
            Operation<VkWriteDescriptorSet> original,
            @Local BindGroupLayout.UniformDescription entry) {
        return original.call(write, VirtualTextureFeedbackLayout.isGraphicsStorageBuffer(entry.name())
                ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : type);
    }
}
