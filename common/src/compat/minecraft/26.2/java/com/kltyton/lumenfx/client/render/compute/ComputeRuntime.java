package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.util.Objects;

/** Backend adapter for the native compute runtime. It never creates a separate device or queue. */
public abstract class ComputeRuntime implements AutoCloseable {
    public static boolean supportsStorageBufferKernels(GpuDevice device) {
        return switch (device.backend) {
            case GlDevice ignored -> GlComputeKernel.supportsStorageBufferKernels();
            case VulkanDevice ignored -> true;
            default -> false;
        };
    }

    public static ComputeRuntime create(final GpuDevice device) {
        Objects.requireNonNull(device, "device");
        return switch (device.backend) {
            case GlDevice gl -> new GlComputeRuntime(device, gl);
            case VulkanDevice vk -> new VulkanComputeRuntime(device, vk);
            default -> throw new IllegalStateException(
                    "Unsupported compute backend " + device.backend.getClass().getName()
            );
        };
    }

    /** Existing 2D entry point. */
    public final ComputeTexture createTexture(
            final String label,
            final GpuFormat format,
            final int width,
            final int height
    ) {
        return this.createTextureInternal(label, format, width, height, false, 1, 1, false);
    }

    public final ComputeTexture createTexture2D(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int mips
    ) {
        return this.createTextureInternal(label, format, width, height, false, 1, mips, false);
    }

    public final ComputeTexture createColorTarget2D(String label, GpuFormat format, int width, int height, int mips, boolean srgb) {
        if (srgb && format != GpuFormat.RGBA8_UNORM)
            throw new IllegalArgumentException("sRGB storage views require RGBA8_UNORM");
        if (!srgb) return createTextureInternal(label, format, width, height, false, 1, mips, true);
        return com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.withSrgbAllocation(
                () -> createTextureInternal(label, format, width, height, false, 1, mips, true));
    }

    /** True GL_TEXTURE_2D_ARRAY / VK_IMAGE_VIEW_TYPE_2D_ARRAY, also when layers == 1. */
    public final ComputeTexture createTextureArray(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int layers
    ) {
        return this.createTextureInternal(label, format, width, height, true, layers, 1, false);
    }

    public final ComputeTexture createTextureArray(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int layers,
            final int mips
    ) {
        return this.createTextureInternal(label, format, width, height, true, layers, mips, false);
    }

    /** Renderable array: only this entry point adds USAGE_RENDER_ATTACHMENT. */
    public final ComputeTexture createRenderableTextureArray(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int layers
    ) {
        return this.createTextureInternal(label, format, width, height, true, layers, 1, true);
    }

    public final ComputeTexture createColorTargetArray(String label, GpuFormat format, int width, int height, int layers,
                                                       boolean storageWritable, boolean srgb) {
        if (srgb && format != GpuFormat.RGBA8_UNORM)
            throw new IllegalArgumentException("sRGB color array views require RGBA8_UNORM");
        if (!srgb) return createTextureInternal(label, format, width, height, true, layers, 1, true, storageWritable);
        return com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.withSrgbAllocation(
                () -> createTextureInternal(label, format, width, height, true, layers, 1, true, storageWritable));
    }

    public final ComputeTexture createRenderableTextureArray(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int layers,
            final int mips
    ) {
        return this.createTextureInternal(label, format, width, height, true, layers, mips, true);
    }

    /** Reports the actual device's typed color-image store capability without allocating a texture. */
    public abstract boolean supportsColorImageStores(GpuFormat format);
    public abstract boolean supportsGridArrayFormat(GpuFormat format);
    public abstract boolean supportsSignedImage2D();

    public final void clearSignedImage2D(ComputeTexture texture, int value) {
        RenderSystem.assertOnRenderThread();
        if (texture.isArray() || texture.isClosed() || texture.format() != GpuFormat.R32_SINT || texture.mips() != 1)
            throw new IllegalArgumentException("Expected a live single-level signed integer 2D image");
        clearSignedImage2DInternal(texture, value);
    }

    protected abstract void clearSignedImage2DInternal(ComputeTexture texture, int value);

    /** Clears every mip of an owned floating 2D image on the host command stream. */
    public final void clearColor2D(ComputeTexture texture) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(texture, "texture");
        if (texture.isClosed() || texture.isArray()
                || !com.kltyton.lumenfx.client.render.compute.format.StorageImageFormats.floatingColor(texture.format()))
            throw new IllegalArgumentException("Color clear requires a live floating 2D storage image");
        clearColor2DInternal(texture);
    }

    protected abstract void clearColor2DInternal(ComputeTexture texture);

    public final void clearColorArray(ComputeTexture texture, int layers, float[] color, boolean srgb) {
        RenderSystem.assertOnRenderThread();
        if (texture.isClosed() || !texture.isArray() || layers < 1 || layers > texture.layers()
                || color.length != 4 || !com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.floatingColor(texture.format())
                || (texture.texture().usage() & com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT) == 0)
            throw new IllegalArgumentException("Invalid floating color array clear");
        float[] encoded = color;
        if (srgb) {
            encoded = color.clone();
            for (int channel = 0; channel < 3; channel++)
                encoded[channel] = com.kltyton.lumenfx.client.render.math.SourceColorMath.srgb8(color[channel]) * (1.0f / 255.0f);
        }
        clearColorArrayInternal(texture, layers, encoded);
    }

    protected abstract void clearColorArrayInternal(ComputeTexture texture, int layers, float[] color);

    protected final ComputeTexture createTextureInternal(String label, GpuFormat format, int width, int height,
                                                         boolean array, int layers, int mips, boolean renderAttachment) {
        return createTextureInternal(label, format, width, height, array, layers, mips, renderAttachment, true);
    }

    protected abstract ComputeTexture createTextureInternal(
            String label,
            GpuFormat format,
            int width,
            int height,
            boolean array,
            int layers,
            int mips,
            boolean renderAttachment,
            boolean storageWritable
    );

    /** One 1x1, single-layer, single-mip zero-initialized texture. */
    public final ComputeTexture createZeroTexture(
            final String label,
            final GpuFormat format,
            final boolean array
    ) {
        return this.createZeroTextureInternal(label, format, array);
    }

    protected abstract ComputeTexture createZeroTextureInternal(
            String label,
            GpuFormat format,
            boolean array
    );

    /**
     * Exact mip-0 GPU copy of one source-array slice into a true 2D destination.
     * No filtering, resampling or CPU readback; only the requested display rectangle is copied.
     */
    public final void copyLayerTo2D(
            final ComputeTexture source,
            final int sourceLayer,
            final ComputeTexture destination,
            final int width,
            final int height
    ) {
        this.copyLayerTo2D(source, sourceLayer, 0, destination, 0, width, height);
    }

    public final void copyLayerTo2D(
            final ComputeTexture source,
            final int sourceLayer,
            final int sourceMip,
            final ComputeTexture destination,
            final int destinationMip,
            final int width,
            final int height
    ) {
        if (destination.isArray()) throw new IllegalArgumentException("copyLayerTo2D requires a 2D destination");
        copyArrayRegion(source, sourceLayer, sourceMip, destination, destinationMip, 0, 0, width, height, 1);
    }

    public final void copyArrayRegion(ComputeTexture source, int sourceLayer, int sourceMip, ComputeTexture destination,
                                      int destinationMip, int destinationX, int destinationY, int width, int height, int layers) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        if (source.isClosed() || destination.isClosed()) {
            throw new IllegalStateException("Cannot copy a closed compute texture");
        }
        if (!source.isArray()) {
            throw new IllegalArgumentException("Texture region copy requires an array source");
        }
        if (source.format() != destination.format()) {
            throw new IllegalArgumentException(
                    "copyLayerTo2D format mismatch: " + source.format() + " -> " + destination.format()
            );
        }
        if (layers < 1 || layers > destination.layers() || sourceLayer < 0 || (long)sourceLayer + layers > source.layers()) {
            throw new IllegalArgumentException(
                    "Source layer " + sourceLayer + " is outside [0," + source.layers() + ")"
            );
        }
        if (sourceMip < 0 || sourceMip >= source.mips()) {
            throw new IllegalArgumentException(
                    "Source mip " + sourceMip + " is outside [0," + source.mips() + ")"
            );
        }
        if (destinationMip < 0 || destinationMip >= destination.mips()) {
            throw new IllegalArgumentException(
                    "Destination mip " + destinationMip + " is outside [0," + destination.mips() + ")"
            );
        }
        if (width <= 0 || height <= 0 || destinationX < 0 || destinationY < 0) {
            throw new IllegalArgumentException("Copy extent must be positive");
        }
        if (width > source.width(sourceMip) || height > source.height(sourceMip)
                || (long)width + destinationX > destination.width(destinationMip)
                || (long)height + destinationY > destination.height(destinationMip)) {
            throw new IllegalArgumentException(
                    "Copy " + width + "x" + height + " exceeds source or destination mip extent"
            );
        }
        if ((source.texture().usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
            throw new IllegalArgumentException("Source texture lacks USAGE_COPY_SRC");
        }
        if ((destination.texture().usage() & GpuTexture.USAGE_COPY_DST) == 0) {
            throw new IllegalArgumentException("Destination texture lacks USAGE_COPY_DST");
        }
        this.copyArrayRegionInternal(
                source, sourceLayer, sourceMip, destination, destinationMip, destinationX, destinationY, width, height, layers
        );
    }

    protected abstract void copyArrayRegionInternal(
            ComputeTexture source,
            int sourceLayer,
            int sourceMip,
            ComputeTexture destination,
            int destinationMip,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int layers
    );

    public abstract ComputeKernel createKernel(ShaderProgram program);

    /** Queues a 32-bit word fill without CPU mapping or waiting for prior frame users. */
    public final void clearBuffer(GpuBufferSlice slice, int value) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(slice, "slice");
        if (slice.buffer().isClosed()) throw new IllegalStateException("Cannot fill a closed buffer");
        if (slice.length() <= 0 || (slice.offset() & 3L) != 0 || (slice.length() & 3L) != 0)
            throw new IllegalArgumentException("GPU word fill requires a nonempty 4-byte aligned slice");
        if ((slice.buffer().usage() & GpuBuffer.USAGE_COPY_DST) == 0)
            throw new IllegalArgumentException("GPU word fill requires USAGE_COPY_DST");
        clearBufferInternal(slice, value);
    }

    protected abstract void clearBufferInternal(GpuBufferSlice slice, int value);

    /** Native maximum 2D extent from the current device, captured at runtime construction. */
    public abstract int maxTexture2D();

    /** Native maximum array-layer count from the current device, captured at runtime construction. */
    public abstract int maxTextureArrayLayers();

    /** Maximum size of one storage-buffer binding on the active device. */
    public abstract long maxStorageBufferBytes();

    /** Native alignment for the start of a storage-buffer descriptor range. */
    public abstract long storageBufferAlignment();

    /** Maximum dispatch workgroup count for X, Y or Z on the active device. */
    public abstract int maxWorkGroups(int axis);

    /** Maximum number of texels in one buffer texture on the active device. */
    public abstract long maxTexelBufferElements();

    /**
     * Runtime owns no device, queue, texture, pipeline or descriptor lifetime. Textures and kernels
     * own their native resources and are closed individually.
     */
    @Override
    public void close() {
    }

    protected static void verifyTextureArgs(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final boolean array,
            final int layers,
            final int mips
    ) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(format, "format");
        if (!format.hasColorAspect()) {
            throw new IllegalArgumentException("Compute storage texture must be a color format: " + format);
        }
        if (!array && layers != 1) {
            throw new IllegalArgumentException("2D compute texture must have exactly one layer");
        }
        if (width <= 0 || height <= 0 || layers <= 0 || mips <= 0) {
            throw new IllegalArgumentException("Compute texture extents must be positive");
        }
        if (mips > Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.max(width, height))) + 1) {
            throw new IllegalArgumentException("Too many mip levels for " + width + "x" + height);
        }
    }
}
