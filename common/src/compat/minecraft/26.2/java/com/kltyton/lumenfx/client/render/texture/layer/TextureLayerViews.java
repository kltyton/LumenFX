package com.kltyton.lumenfx.client.render.texture.layer;

import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/** Scoped layer selection for the host's GL and Vulkan texture-view constructors. */
public final class TextureLayerViews {
    public record Selection(GpuTexture texture, int first, int count, boolean array) { }
    private static final ThreadLocal<Selection> SELECTION = new ThreadLocal<>();

    private TextureLayerViews() { }

    public static @Nullable Selection creating(GpuTexture texture) {
        var selection = SELECTION.get();
        return selection != null && selection.texture() == texture ? selection : null;
    }

    public static GpuTextureView createLayer(GpuDevice device, GpuTexture texture, int layer, int mip) {
        if (layer < 0 || layer >= texture.getDepthOrLayers() || mip < 0 || mip >= texture.getMipLevels())
            throw new IllegalArgumentException("Image view layer or mip is out of range");
        return create(device, new Selection(texture, layer, 1, false), mip, 1);
    }

    public static GpuTextureView createArray(GpuDevice device, ComputeTexture texture) {
        if (!texture.isArray()) throw new IllegalArgumentException("A physical texture array is required");
        return create(device, new Selection(texture.texture(), 0, texture.layers(), true), 0, texture.texture().getMipLevels());
    }

    private static GpuTextureView create(GpuDevice device, Selection selection, int mip, int mipCount) {
        var previous = SELECTION.get();
        SELECTION.set(selection);
        try {
            return device.createTextureView(selection.texture(), mip, mipCount);
        } finally {
            if (previous == null) SELECTION.remove();
            else SELECTION.set(previous);
        }
    }

    public static int attachmentLayers(GpuTexture texture, RenderPassDescriptor descriptor, int original) {
        boolean selected = false;
        for (var attachment : descriptor.colorAttachments()) {
            if (attachment == null || attachment.textureView().texture() != texture) continue;
            if (!singleLayer(attachment.textureView(), original)) return original;
            selected = true;
        }
        var depth = descriptor.depthAttachment();
        if (depth != null && depth.textureView().texture() == texture) {
            if (!singleLayer(depth.textureView(), original)) return original;
            selected = true;
        }
        return selected ? 1 : original;
    }

    private static boolean singleLayer(GpuTextureView view, int layers) {
        return view instanceof TextureLayerView layer && !layer.samplesImageArray()
                && layer.selectedImageLayer() >= 0 && layer.selectedImageLayer() < layers;
    }
}
