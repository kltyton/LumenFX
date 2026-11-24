package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Enables supported native source texture features before logical-device creation. */
@Mixin(VulkanBackend.class)
abstract class VulkanBlockCompressionMixin {
    @WrapOperation(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int lumenfx$enableBC(VkPhysicalDevice physical, VkDeviceCreateInfo info,
                                       VkAllocationCallbacks allocator, PointerBuffer output, Operation<Integer> original) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
            VK12.vkGetPhysicalDeviceFeatures(physical, supported);
            if (supported.textureCompressionBC()) info.pEnabledFeatures().textureCompressionBC(true);
            if (supported.imageCubeArray()) info.pEnabledFeatures().imageCubeArray(true);
            return original.call(physical, info, allocator, output);
        }
    }
}
