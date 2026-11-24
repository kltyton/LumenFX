package com.kltyton.lumenfx.client.resource.texture.target;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;

/** Common ownership and resize contract for array and volume render target objects. */
public sealed interface DimensionalRenderTarget extends TextureParameter, AutoCloseable
        permits RenderTargetTextureArray, RenderTargetTextureVolume, RenderTargetTextureCube {
    Dimension dimension();
    RenderTargetTextureDefinition settings();
    int extentZ();
    boolean closed();
    boolean srgbFor(String pixelFormat);
    void configure(ComputeRuntime runtime, RenderTargetTextureDefinition next, int extentZ);
    @Override void close();
}
