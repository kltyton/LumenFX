package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureCreation;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Supplies physical BC/sRGB storage while retaining native image allocation and barriers. */
@Mixin(VulkanGpuTexture.class)
abstract class VulkanTextureStorageMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;flags(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"), index = 0)
    private int lumenfx$mutableColorViews(int original) {
        int flags = NativeTextureCreation.imageFlags(original);
        return com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.mutableSrgbAllocation()
                ? flags | org.lwjgl.vulkan.VK12.VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : flags;
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;imageType(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"), index = 0)
    private int lumenfx$volumeImageType(int original) { return NativeTextureCreation.imageType(original); }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;format(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"), index = 0)
    private int lumenfx$imageFormat(int original) { return NativeTextureCreation.imageFormat(original); }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;usage(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"), index = 0)
    private int lumenfx$imageUsage(int original) { return NativeTextureCreation.imageUsage(original); }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkExtent3D;set(III)Lorg/lwjgl/vulkan/VkExtent3D;"), index = 2)
    private int lumenfx$volumeDepth(int original) { return NativeTextureCreation.imageDepth(original); }
}
