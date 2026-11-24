package com.kltyton.lumenfx.client.render.compute.mixin;

import com.kltyton.lumenfx.client.render.compute.VulkanEnabledFeatures;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Enables supported storage-image, graphics feedback and particle blend features on the existing device-creation
 * {@link VkPhysicalDeviceFeatures}. Existing feature fields and the pNext chain are untouched.
 */
@Mixin(VulkanBackend.class)
abstract class VulkanComputeFeaturesMixin {
    @WrapOperation(
            method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"
            )
    )
    private static int lumenfx$enableOptionalFeatures(
            final VkPhysicalDevice physical,
            final VkDeviceCreateInfo info,
            final VkAllocationCallbacks allocator,
            final PointerBuffer output,
            final Operation<Integer> original
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
            VK12.vkGetPhysicalDeviceFeatures(physical, supported);
            final VkPhysicalDeviceFeatures enabled = info.pEnabledFeatures();
            if (enabled != null) {
                if (supported.shaderStorageImageExtendedFormats()) {
                    enabled.shaderStorageImageExtendedFormats(true);
                }
                if (supported.shaderStorageImageReadWithoutFormat()) {
                    enabled.shaderStorageImageReadWithoutFormat(true);
                }
                if (supported.shaderStorageImageWriteWithoutFormat()) {
                    enabled.shaderStorageImageWriteWithoutFormat(true);
                }
                if (supported.fragmentStoresAndAtomics()) {
                    enabled.fragmentStoresAndAtomics(true);
                }
                if (supported.geometryShader()) {
                    enabled.geometryShader(true);
                }
                if (supported.dualSrcBlend()) {
                    enabled.dualSrcBlend(true);
                }
            }
            int result = original.call(physical, info, allocator, output);
            if (result == VK10.VK_SUCCESS) {
                VulkanEnabledFeatures.record(
                        enabled != null && enabled.fragmentStoresAndAtomics(),
                        enabled != null && enabled.geometryShader());
            }
            return result;
        }
    }
}
