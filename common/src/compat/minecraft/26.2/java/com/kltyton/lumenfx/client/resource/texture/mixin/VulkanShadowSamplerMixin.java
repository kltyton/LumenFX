package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Adds hardware shadow comparison only within the dedicated shadow-sampler allocation. */
@Mixin(VulkanGpuSampler.class)
abstract class VulkanShadowSamplerMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateSampler(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSamplerCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"), index = 1)
    private VkSamplerCreateInfo lumenfx$comparisonSampler(VkSamplerCreateInfo original) { return PointShadowSampler.configure(original); }
}
