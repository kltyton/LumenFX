package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Native compute texture. A real 2D texture exposes a host view for native postprocessing; a real
 * 2D-array texture stays compute-only. Compute SRV/UAV subresource views are cached per texture and
 * retired with the same fenced lifetime as the texture.
 */
public final class ComputeTexture implements AutoCloseable {
    private static final class Allocation {
        private final Runnable destroy;
        private int references = 1;
        Allocation(Runnable destroy) { this.destroy = Objects.requireNonNull(destroy); }
        void retain() {
            if (references <= 0) throw new IllegalStateException("Compute image allocation has been released");
            references = Math.incrementExact(references);
        }
        void release() {
            if (references <= 0) throw new IllegalStateException("Compute image allocation reference underflow");
            if (--references == 0) destroy.run();
        }
    }
    private final GpuTexture texture;
    private final @Nullable GpuTextureView sampledView;
    private final GpuFormat format;
    private final int width;
    private final int height;
    private final boolean array;
    private final int layers;
    private final int mips;
    private final long vkImageView;
    private final int glTextureId;
    private final int glTextureTarget;
    private final int glInternalFormat;
    private final int glArraySampledId;
    private final @Nullable SampledViewFactory sampledViewFactory;
    private final @Nullable VkStorageViewFactory vkStorageViewFactory;
    private final @Nullable LongConsumer vkViewDestroyer;
    private final @Nullable IntConsumer glViewDestroyer;
    private final Map<SampledViewKey, SampledView> sampledViews = new HashMap<>();
    private final Map<StorageViewKey, Long> vkStorageViews = new HashMap<>();
    private final Allocation allocation;
    private @Nullable GpuTextureView rasterArrayView;
    private boolean closed;

    ComputeTexture(
            final GpuTexture texture,
            final @Nullable GpuTextureView sampledView,
            final GpuFormat format,
            final int width,
            final int height,
            final boolean array,
            final int layers,
            final int mips,
            final long vkImageView,
            final int glTextureId,
            final int glTextureTarget,
            final int glInternalFormat,
            final @Nullable SampledViewFactory sampledViewFactory,
            final @Nullable VkStorageViewFactory vkStorageViewFactory,
            final @Nullable LongConsumer vkViewDestroyer,
            final @Nullable IntConsumer glViewDestroyer,
            final Runnable deferredClose
    ) {
        this(texture, sampledView, format, width, height, array, layers, mips, vkImageView, glTextureId, glTextureTarget,
                glInternalFormat, sampledViewFactory, vkStorageViewFactory, vkViewDestroyer, glViewDestroyer, deferredClose, 0);
    }

    ComputeTexture(
            final GpuTexture texture,
            final @Nullable GpuTextureView sampledView,
            final GpuFormat format,
            final int width,
            final int height,
            final boolean array,
            final int layers,
            final int mips,
            final long vkImageView,
            final int glTextureId,
            final int glTextureTarget,
            final int glInternalFormat,
            final @Nullable SampledViewFactory sampledViewFactory,
            final @Nullable VkStorageViewFactory vkStorageViewFactory,
            final @Nullable LongConsumer vkViewDestroyer,
            final @Nullable IntConsumer glViewDestroyer,
            final Runnable deferredClose,
            final int glArraySampledId
    ) {
        this.texture = Objects.requireNonNull(texture, "texture");
        this.sampledView = sampledView;
        this.format = Objects.requireNonNull(format, "format");
        this.width = width;
        this.height = height;
        this.array = array;
        this.layers = layers;
        this.mips = mips;
        this.vkImageView = vkImageView;
        this.glTextureId = glTextureId;
        this.glTextureTarget = glTextureTarget;
        this.glInternalFormat = glInternalFormat;
        this.glArraySampledId = glArraySampledId;
        this.sampledViewFactory = sampledViewFactory;
        this.vkStorageViewFactory = vkStorageViewFactory;
        this.vkViewDestroyer = vkViewDestroyer;
        this.glViewDestroyer = glViewDestroyer;
        this.allocation = new Allocation(deferredClose);
    }

    private ComputeTexture(ComputeTexture source) {
        texture = source.texture; sampledView = source.sampledView; format = source.format;
        width = source.width; height = source.height; array = source.array; layers = source.layers; mips = source.mips;
        vkImageView = source.vkImageView; glTextureId = source.glTextureId;
        glTextureTarget = source.glTextureTarget; glInternalFormat = source.glInternalFormat;
        glArraySampledId = source.glArraySampledId;
        sampledViewFactory = source.sampledViewFactory; vkStorageViewFactory = source.vkStorageViewFactory;
        vkViewDestroyer = source.vkViewDestroyer; glViewDestroyer = source.glViewDestroyer;
        allocation = source.allocation;
    }

    /** Each handle owns its subresource views; the final handle retires the shared image and primary view. */
    public ComputeTexture retain() {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Cannot retain a closed compute image handle");
        allocation.retain();
        try { return new ComputeTexture(this); }
        catch (RuntimeException | Error failure) { allocation.release(); throw failure; }
    }

    public GpuTexture texture() {
        if (this.closed) {
            throw new IllegalStateException("Compute texture is closed");
        }
        return this.texture;
    }

    public boolean hasSampledView() {
        return this.sampledView != null;
    }

    public boolean srgb() { return com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.isSrgb(sampledView); }

    /** The real 2D host view for native postprocessing. Array textures throw. */
    public GpuTextureView sampledView() {
        if (this.closed) {
            throw new IllegalStateException("Compute texture is closed");
        }
        if (this.sampledView == null) {
            throw new UnsupportedOperationException(
                    "Array storage textures are compute-only; use the compute-native texture binding"
            );
        }
        return this.sampledView;
    }

    /** Lazily creates and owns the host graphics view for binding this physical array in a render pass. */
    public GpuTextureView rasterArrayView(Supplier<GpuTextureView> factory) {
        RenderSystem.assertOnRenderThread();
        if (closed || !array) throw new IllegalStateException("A live sampled texture array is required");
        if (rasterArrayView == null) rasterArrayView = Objects.requireNonNull(factory.get());
        return rasterArrayView;
    }

    public GpuFormat format() {
        return this.format;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int width(final int mipLevel) {
        if (mipLevel < 0 || mipLevel >= this.mips) {
            throw new IllegalArgumentException("Mip " + mipLevel + " is outside [0," + this.mips + ")");
        }
        return Math.max(1, this.width >> mipLevel);
    }

    public int height(final int mipLevel) {
        if (mipLevel < 0 || mipLevel >= this.mips) {
            throw new IllegalArgumentException("Mip " + mipLevel + " is outside [0," + this.mips + ")");
        }
        return Math.max(1, this.height >> mipLevel);
    }

    public boolean isArray() {
        return this.array;
    }

    public int layers() {
        return this.layers;
    }

    public int mips() {
        return this.mips;
    }

    public long vkImageView() {
        return this.vkImageView;
    }

    /** Wraps a sample-only source allocation whose physical format and view were created by the uploader. */
    public static ComputeTexture sourceArray(GpuTexture texture, long vkView, int glId, int glTarget, int glFormat,
                                             Runnable release) {
        if (texture.isClosed() || (texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0
                || (vkView == 0) == (glId == 0))
            throw new IllegalArgumentException("Invalid source texture array allocation");
        return new ComputeTexture(texture, null, GpuFormat.RGBA8_UNORM, texture.getWidth(0), texture.getHeight(0), true,
                texture.getDepthOrLayers(), texture.getMipLevels(), vkView, glId, glTarget, glFormat,
                null, null, null, null, release);
    }

    public SampledView fullArraySampledView() {
        if (closed || !array) throw new IllegalStateException("A live array image is required for a full mip-chain view");
        return new SampledView(vkImageView, glArraySampledId == 0 ? glTextureId : glArraySampledId, glTextureTarget, false);
    }

    public SampledView cachedSampledView(
            final int mipLevel,
            final int baseLayer,
            final int layerCount,
            final boolean arrayView
    ) {
        validateSubresource(mipLevel, baseLayer, layerCount);
        if (!this.array) {
            if (arrayView || baseLayer != 0 || layerCount != 1) {
                throw new IllegalArgumentException(
                        "2D sampled binding must use arrayView=false, layer 0 and layerCount 1"
                );
            }
            if (mipLevel == 0) {
                if (this.sampledView instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView view)
                    return new SampledView(view.vkImageView(), 0, 0, false);
                if (this.sampledView instanceof com.mojang.blaze3d.opengl.GlTextureView view)
                    return new SampledView(0, view.glId(), this.glTextureTarget, false);
                return new SampledView(this.vkImageView, this.glTextureId, this.glTextureTarget, false);
            }
            if (this.sampledViewFactory == null) {
                throw new UnsupportedOperationException("2D sampled subresource views are not available");
            }
            return this.sampledViews.computeIfAbsent(
                    new SampledViewKey(mipLevel, 0, 1, false),
                    key -> this.sampledViewFactory.create(key.mipLevel, 0, 1, false)
            );
        }
        if (!arrayView && layerCount != 1) {
            throw new IllegalArgumentException(
                    "Single-slice sampled binding must use layerCount 1"
            );
        }
        if (this.sampledViewFactory == null) {
            throw new UnsupportedOperationException("Sampled subresource views are not available");
        }
        return this.sampledViews.computeIfAbsent(
                new SampledViewKey(mipLevel, baseLayer, layerCount, arrayView),
                key -> this.sampledViewFactory.create(
                        key.mipLevel, key.baseLayer, key.layerCount, key.arrayView
                )
        );
    }

    long vkStorageView(final int mipLevel, final int baseLayer, final int layerCount) {
        validateSubresource(mipLevel, baseLayer, layerCount);
        if (!this.array) {
            if (baseLayer != 0 || layerCount != 1) {
                throw new IllegalArgumentException("2D storage binding must use layer 0 and layerCount 1");
            }
            if (mipLevel == 0 && this.mips == 1) {
                return this.vkImageView;
            }
        }
        if (this.vkStorageViewFactory == null) {
            throw new UnsupportedOperationException("Storage subresource views are only available on Vulkan");
        }
        return this.vkStorageViews.computeIfAbsent(
                new StorageViewKey(mipLevel, baseLayer, layerCount),
                key -> this.vkStorageViewFactory.create(key.mipLevel, key.baseLayer, key.layerCount)
        );
    }

    public int glTextureId() {
        return this.glTextureId;
    }

    public int glTextureTarget() {
        return this.glTextureTarget;
    }

    public int glInternalFormat() {
        return this.glInternalFormat;
    }

    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        GpuTextureView hostArrayView = rasterArrayView;
        rasterArrayView = null;
        try {
            if (hostArrayView != null) hostArrayView.close();
        } finally {
            try {
                for (SampledView view : this.sampledViews.values()) {
                    if (!view.owned) continue;
                    if (this.vkViewDestroyer != null && view.vkImageView != 0L) this.vkViewDestroyer.accept(view.vkImageView);
                    if (this.glViewDestroyer != null && view.glTextureId != 0) this.glViewDestroyer.accept(view.glTextureId);
                }
                this.sampledViews.clear();
            } finally {
                try {
                    if (this.vkViewDestroyer != null) {
                        for (long view : this.vkStorageViews.values())
                            if (view != this.vkImageView) this.vkViewDestroyer.accept(view);
                    }
                    this.vkStorageViews.clear();
                } finally {
                    this.allocation.release();
                }
            }
        }
    }

    private void validateSubresource(final int mipLevel, final int baseLayer, final int layerCount) {
        if (this.closed) {
            throw new IllegalStateException("Compute texture is closed");
        }
        if (mipLevel < 0 || mipLevel >= this.mips) {
            throw new IllegalArgumentException(
                    "Subresource mip " + mipLevel + " is outside [0," + this.mips + ")"
            );
        }
        if (baseLayer < 0 || layerCount < 1 || baseLayer + layerCount > this.layers) {
            throw new IllegalArgumentException(
                    "Subresource layers [" + baseLayer + "," + (baseLayer + layerCount)
                            + ") are outside [0," + this.layers + ")"
            );
        }
    }

    @FunctionalInterface
    interface SampledViewFactory {
        SampledView create(int mipLevel, int baseLayer, int layerCount, boolean arrayView);
    }

    @FunctionalInterface
    interface VkStorageViewFactory {
        long create(int mipLevel, int baseLayer, int layerCount);
    }

    public record SampledView(long vkImageView, int glTextureId, int glTextureTarget, boolean owned) {
    }

    private record SampledViewKey(int mipLevel, int baseLayer, int layerCount, boolean arrayView) {
    }

    private record StorageViewKey(int mipLevel, int baseLayer, int layerCount) {
    }
}
