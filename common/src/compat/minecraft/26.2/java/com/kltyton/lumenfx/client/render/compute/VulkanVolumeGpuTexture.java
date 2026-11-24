package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;

/** The host lifecycle owns the native 3D image through its ordinary deferred destruction path. */
final class VulkanVolumeGpuTexture extends VulkanGpuTexture implements VolumeGpuTexture {
    private final int volumeDepth;

    VulkanVolumeGpuTexture(VulkanDevice device, String label, GpuFormat format, int width, int height,
                           int depth, int mipLevels, boolean transferSource) {
        super(device, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST
                        | (transferSource ? GpuTexture.USAGE_COPY_SRC : 0),
                label, format, width, height, 1, mipLevels);
        volumeDepth = depth;
    }

    @Override public int getDepthOrLayers() { return volumeDepth; }
}
