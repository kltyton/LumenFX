package com.kltyton.lumenfx.client.render.light.shadow.directional;

import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** A directional light's cascades share one depth image, with guard pixels around each tile. */
final class DirectionalShadowAtlas implements ShadowDepthTarget {
    static final int BORDER = 4;
    private final int size, count, columns, width, height;
    private final GpuTexture depth;
    private final GpuTextureView view;
    private final OwnedColorTarget rasterColor;

    DirectionalShadowAtlas(int size, int count) {
        RenderSystem.assertOnRenderThread();
        this.size = size;
        this.count = count;
        columns = columns(count);
        width = columns * size;
        height = Math.ceilDiv(count, columns) * size;
        var device = RenderSystem.getDevice();
        depth = device.createTexture("LumenFX directional shadow atlas", GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                GpuFormat.D32_FLOAT, width, height, 1, 1);
        GpuTextureView sampled = null;
        try {
            sampled = device.createTextureView(depth);
            rasterColor = new OwnedColorTarget("LumenFX directional shadow raster color", width, height, GpuFormat.R8_UNORM);
        } catch (RuntimeException | Error failure) {
            try { if (sampled != null) sampled.close(); }
            finally { depth.close(); }
            throw failure;
        }
        view = sampled;
    }

    static int maximumTileSize(int count) {
        var limits = RenderSystem.getDevice().getDeviceInfo().limits();
        int columns = columns(count), rows = Math.ceilDiv(count, columns);
        int allocation = (int) Math.sqrt((double) limits.maxMemoryAllocationSize() / (columns * rows * GpuFormat.D32_FLOAT.blockSize()));
        return Math.min(allocation, limits.maxTextureSize() / Math.max(columns, rows));
    }

    private static int columns(int count) { return (int) Math.ceil(Math.sqrt(count)); }
    int width() { return width; }
    int height() { return height; }
    int x(int index) { return index % columns * size; }
    int y(int index) { return index / columns * size; }
    int innerSize() { return size - 2 * BORDER; }
    void clear() { RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(depth, 0); }
    @Override public int size() { return size; }
    @Override public int viewCount() { return count; }
    @Override public GpuTextureView rasterColorView() { return rasterColor.getColorTextureView(); }
    @Override public GpuTextureView sampled() { return view; }
    @Override public GpuTextureView face(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
        return view;
    }
    @Override public RenderPassDescriptor descriptor(int index, double clearDepth) {
        return descriptor(index, OptionalDouble.of(clearDepth), () -> "LumenFX directional shadow depth");
    }
    @Override public RenderPassDescriptor descriptor(int index, Supplier<String> label) {
        return descriptor(index, OptionalDouble.empty(), label);
    }
    private RenderPassDescriptor descriptor(int index, OptionalDouble clearDepth, Supplier<String> label) {
        return RenderPassDescriptor.create(label).withColorAttachment(rasterColorView())
                .withDepthAttachment(face(index), clearDepth).withRenderArea(new RenderPass.RenderArea(
                        x(index) + BORDER, y(index) + BORDER, innerSize(), innerSize()));
    }
    @Override public void copyFrom(ShadowDepthTarget source) {
        if (!(source instanceof DirectionalShadowAtlas atlas) || atlas.count != count || atlas.size != size)
            throw new IllegalArgumentException("Directional shadow depth copy requires matching atlases");
        if (atlas != this)
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(atlas.depth, depth, 0, 0, 0, 0, 0, width, height);
    }
    @Override public void close() {
        try { view.close(); }
        finally {
            try { depth.close(); }
            finally { rasterColor.close(); }
        }
    }
}
