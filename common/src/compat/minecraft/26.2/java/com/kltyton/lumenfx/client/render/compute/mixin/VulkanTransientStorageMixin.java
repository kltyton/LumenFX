package com.kltyton.lumenfx.client.render.compute.mixin;

import com.mojang.blaze3d.vulkan.VulkanTransientMemory;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The shared transient pool also supplies mesh compute inputs and outputs. */
@Mixin(VulkanTransientMemory.class)
abstract class VulkanTransientStorageMixin {
    @ModifyArg(method = "allocateVulkanBlock", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkBufferCreateInfo;usage(I)Lorg/lwjgl/vulkan/VkBufferCreateInfo;"), index = 0)
    private int lumenfx$storagePool(int usage) {
        return usage | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    }
}
