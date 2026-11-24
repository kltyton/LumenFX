package com.kltyton.lumenfx.client.render.texture.cube;

import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerViews;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/** Adds an explicit cube-face subresource while retaining host format, usage, size and lifetime checks. */
public final class CubeFaceViews {
    private CubeFaceViews() { }

    public static GpuTextureView create(GpuDevice device, GpuTexture texture, int face) {
        return create(device, texture, face, 0);
    }

    public static GpuTextureView create(GpuDevice device, GpuTexture texture, int face, int mip) {
        if (face < 0 || face >= 6 || texture.getDepthOrLayers() != 6
                || mip < 0 || mip >= texture.getMipLevels()
                || (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) == 0)
            throw new IllegalArgumentException("Cube face requires one of the six cube image layers");
        if (device.backend instanceof GlDevice && !(texture instanceof GlCubeRenderTexture))
            throw new IllegalArgumentException("GL cube-face views require immutable cube storage");
        return TextureLayerViews.createLayer(device, texture, face, mip);
    }
}
