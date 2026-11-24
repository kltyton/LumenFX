package com.kltyton.lumenfx.api.client.parameter;

import com.mojang.blaze3d.textures.GpuTextureView;

/** Borrowed producer access to the resolved color image of a prepared render-target asset. */
public interface TextureTarget extends TextureParameter {
    GpuTextureView colorAttachment();
    void clear();
    void resize(int width, int height);
}
