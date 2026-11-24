package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerViews;
import com.kltyton.lumenfx.client.resource.texture.storage.SampledTextureStorage;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Selects an attachment layer or a complete sampled array view. */
@Mixin(VulkanGpuTextureView.class)
abstract class VulkanTextureFaceViewMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkImageViewCreateInfo;viewType(I)Lorg/lwjgl/vulkan/VkImageViewCreateInfo;"), index = 0)
    private int lumenfx$faceType(int original) {
        var texture = ((VulkanGpuTextureView)(Object)this).texture();
        var selection = TextureLayerViews.creating(texture);
        if (selection != null) return selection.array() ? VK12.VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK12.VK_IMAGE_VIEW_TYPE_2D;
        return SampledTextureStorage.isCubeArray(texture) ? VK12.VK_IMAGE_VIEW_TYPE_CUBE_ARRAY : original;
    }
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkImageSubresourceRange;baseArrayLayer(I)Lorg/lwjgl/vulkan/VkImageSubresourceRange;"), index = 0)
    private int lumenfx$faceLayer(int original) {
        var selection = TextureLayerViews.creating(((VulkanGpuTextureView)(Object)this).texture());
        return selection == null ? original : selection.first();
    }
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkImageSubresourceRange;layerCount(I)Lorg/lwjgl/vulkan/VkImageSubresourceRange;"), index = 0)
    private int lumenfx$faceCount(int original) {
        var texture = ((VulkanGpuTextureView)(Object)this).texture();
        var selection = TextureLayerViews.creating(texture);
        if (selection != null) return selection.count();
        return SampledTextureStorage.isCubeArray(texture) ? texture.getDepthOrLayers() : original;
    }
}
