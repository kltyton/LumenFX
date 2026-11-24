package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.render.compute.VolumeGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Selects a 3D image view for host raster sampling of native compute volumes. */
@Mixin(VulkanGpuTextureView.class)
abstract class VulkanTextureVolumeViewMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkImageViewCreateInfo;viewType(I)Lorg/lwjgl/vulkan/VkImageViewCreateInfo;"), index = 0)
    private int lumenfx$volumeViewType(int original) {
        return ((VulkanGpuTextureView)(Object)this).texture() instanceof VolumeGpuTexture
                ? VK10.VK_IMAGE_VIEW_TYPE_3D : original;
    }
}
