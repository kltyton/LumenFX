package com.kltyton.lumenfx.client.render.texture;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** One projected depth image serves both shadow raster and receiver sampling. */
public final class ProjectedShadowDepthTarget implements ShadowDepthTarget {
    private final int size;
    private final GpuTexture depth;
    private final GpuTextureView view;
    private final OwnedColorTarget rasterColor;

    public static int maximumSize() {
        var limits = RenderSystem.getDevice().getDeviceInfo().limits();
        int allocation = (int)Math.sqrt((double)limits.maxMemoryAllocationSize() / GpuFormat.D32_FLOAT.blockSize());
        return Math.min(limits.maxTextureSize(), allocation);
    }

    public ProjectedShadowDepthTarget(String label, int size) {
        RenderSystem.assertOnRenderThread();
        if (size <= 0) throw new IllegalArgumentException("Projected shadow depth size must be positive");
        this.size = size;
        var device = RenderSystem.getDevice();
        depth = device.createTexture(label, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, size, size, 1, 1);
        GpuTextureView next = null;
        try {
            next = device.createTextureView(depth);
            rasterColor = new OwnedColorTarget(label + " raster color slot", size, size, GpuFormat.R8_UNORM);
        } catch (RuntimeException | Error failure) {
            if (next != null) next.close();
            depth.close();
            throw failure;
        }
        view = next;
    }

    @Override public int size() { return size; }
    @Override public int viewCount() { return 1; }
    @Override public GpuTextureView rasterColorView() { return rasterColor.getColorTextureView(); }
    @Override public GpuTextureView sampled() { return view; }
    @Override public GpuTextureView face(int index) {
        if (index != 0) throw new IndexOutOfBoundsException(index);
        return view;
    }
    @Override public RenderPassDescriptor descriptor(int index, double clearDepth) {
        return descriptor(index, OptionalDouble.of(clearDepth), () -> "LumenFX projected shadow depth");
    }
    @Override public RenderPassDescriptor descriptor(int index, Supplier<String> label) {
        return descriptor(index, OptionalDouble.empty(), label);
    }
    private RenderPassDescriptor descriptor(int index, OptionalDouble clearDepth, Supplier<String> label) {
        return RenderPassDescriptor.create(label).withColorAttachment(rasterColorView())
                .withDepthAttachment(face(index), clearDepth).withRenderArea(new RenderPass.RenderArea(0, 0, size, size));
    }
    @Override public void copyFrom(ShadowDepthTarget source) {
        if (!(source instanceof ProjectedShadowDepthTarget projected) || projected.size != size)
            throw new IllegalArgumentException("Projected shadow depth copy requires matching images");
        if (source != this)
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(projected.depth, depth, 0, 0, 0, 0, 0, size, size);
    }
    @Override public void close() {
        view.close();
        depth.close();
        rasterColor.close();
    }
}
