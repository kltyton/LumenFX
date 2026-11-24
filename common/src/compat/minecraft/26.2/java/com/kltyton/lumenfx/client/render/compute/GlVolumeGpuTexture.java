package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;

/** Host view metadata and lifecycle for the already allocated native GL 3D image. */
final class GlVolumeGpuTexture extends GlTexture implements VolumeGpuTexture {
    GlVolumeGpuTexture(GlDevice device, String label, GpuFormat format, int width, int height, int depth, int mipLevels, int texture) {
        super(GpuTexture.USAGE_TEXTURE_BINDING, label, format, width, height, depth, mipLevels, texture,
                device.frameBufferCache());
    }
}
