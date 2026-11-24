package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureCreation;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageViewUsageCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Matches the view format to its source image's physical block and color format. */
@Mixin(VulkanGpuTextureView.class)
abstract class VulkanTextureViewStorageMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkImageViewCreateInfo;format(I)Lorg/lwjgl/vulkan/VkImageViewCreateInfo;"), index = 0)
    private int lumenfx$viewFormat(int original) { return NativeTextureCreation.imageFormat(original); }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target =
            "Lorg/lwjgl/vulkan/VK12;vkCreateImageView(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkImageViewCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private int lumenfx$srgbViewUsage(VkDevice device, VkImageViewCreateInfo info, VkAllocationCallbacks allocator,
                                     LongBuffer output, Operation<Integer> original) {
        int usage = com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.srgbViewUsage();
        if (usage == 0) return original.call(device, info, allocator, output);
        long previous = info.pNext();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var viewUsage = VkImageViewUsageCreateInfo.calloc(stack).sType$Default().pNext(previous).usage(usage);
            info.pNext(viewUsage.address());
            try { return original.call(device, info, allocator, output); }
            finally { info.pNext(previous); }
        }
    }
}
