package com.kltyton.lumenfx.client.render.texture;

import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.function.Supplier;

/** Renderable depth surfaces and their sampled image share a single allocation. */
public interface ShadowDepthTarget extends AutoCloseable {
    int size();
    int viewCount();
    GpuTextureView rasterColorView();
    GpuTextureView sampled();
    GpuTextureView face(int index);
    RenderPassDescriptor descriptor(int index, double clearDepth);
    RenderPassDescriptor descriptor(int index, Supplier<String> label);
    void copyFrom(ShadowDepthTarget source);
    @Override void close();
}
