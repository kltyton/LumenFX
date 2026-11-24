package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Supplies source mip, three-axis address and bounded LOD-bias settings during effect sampler creation. */
@Mixin(VulkanGpuSampler.class)
abstract class VulkanSamplerSettingsMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipmapMode(I)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"), index = 0)
    private int lumenfx$sourceMipFilter(int original) {
        return EffectSamplerFactory.vulkanMipmapMode(original);
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;addressModeU(I)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"), index = 0)
    private int lumenfx$sourceAddressU(int original) {
        return EffectSamplerFactory.vulkanAddressMode(original, false);
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;addressModeV(I)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"), index = 0)
    private int lumenfx$sourceAddressV(int original) {
        return EffectSamplerFactory.vulkanAddressMode(original, true);
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateSampler(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSamplerCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"), index = 1)
    private VkSamplerCreateInfo lumenfx$sourceAddressW(VkSamplerCreateInfo original) {
        return EffectSamplerFactory.vulkanSamplerAddressW(original);
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipLodBias(F)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"), index = 0)
    private float lumenfx$sourceLodBias(float original) {
        return EffectSamplerFactory.vulkanLodBias(original);
    }
}
