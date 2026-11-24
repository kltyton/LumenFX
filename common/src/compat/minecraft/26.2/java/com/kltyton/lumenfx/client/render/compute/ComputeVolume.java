package com.kltyton.lumenfx.client.render.compute;

import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Native sampled and storage 3D images with independent U/V/W sampler state. */
public abstract class ComputeVolume implements AutoCloseable {
    private static final Sampling SIGNED_GRID_SAMPLING = new Sampling(
            new EffectSamplerFactory.Settings(TextureMetadata.Address.CLAMP, TextureMetadata.Address.CLAMP,
                    TextureMetadata.FilterMode.POINT, TextureMetadata.FilterMode.POINT, 1), TextureMetadata.Address.CLAMP);
    public record Sampling(EffectSamplerFactory.Settings xy, TextureMetadata.Address z) { }
    record Target(GpuFormat format, boolean writable, float[] clear) {
        Target {
            if (clear.length != 4) throw new IllegalArgumentException("A volume clear color requires four components");
            clear = clear.clone();
            for (float value : clear) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite volume clear color");
        }
        @Override public float[] clear() { return clear.clone(); }
    }

    record Payload(int width, int height, int depth, List<ByteBuffer> mips, NativeTextureStorage storage,
                   @Nullable Sampling sampling, @Nullable Target target) {
        Payload {
            mips = List.copyOf(mips);
            if (width < 1 || height < 1 || depth < 1 || mips.isEmpty() == (target == null)
                    || mips.size() > 32 - Integer.numberOfLeadingZeros(Math.max(width, Math.max(height, depth))))
                throw new IllegalArgumentException("Invalid sampled volume extent");
            for (int mip = 0; mip < mips.size(); mip++) {
                int bytes = Math.multiplyExact(storage.format().byteSize(Math.max(1, width >> mip),
                        Math.max(1, height >> mip)), Math.max(1, depth >> mip));
                if (!mips.get(mip).isDirect() || mips.get(mip).remaining() != bytes)
                    throw new IllegalArgumentException("Volume mip has invalid native storage at level " + mip);
            }
        }

        float[] targetClear() {
            var color = java.util.Objects.requireNonNull(target).clear();
            if (storage.srgb()) for (int channel = 0; channel < 3; channel++)
                color[channel] = com.kltyton.lumenfx.client.render.math.SourceColorMath.srgb8(color[channel]) * (1.0f / 255.0f);
            return color;
        }
    }

    private final int width, height, depth, mips;
    private final NativeTextureStorage storage;
    private final @Nullable Target target;
    final @Nullable Sampling sampling;
    private int references = 1;
    private boolean ownerReleased;
    private @Nullable GpuTextureView rasterView;

    ComputeVolume(Payload source) {
        width = source.width(); height = source.height(); depth = source.depth();
        target = source.target();
        mips = target == null ? source.mips().size() : 1;
        storage = source.storage(); sampling = source.sampling();
    }

    public static ComputeVolume create(String label, VectorFieldVolume field) {
        RenderSystem.assertOnRenderThread();
        if (!field.sourceCoordinates()) throw new IllegalArgumentException("GPU Niagara vector fields require source-coordinate data");
        short[] payload = field.halfRgba();
        ByteBuffer pixels = MemoryUtil.memAlloc(Math.multiplyExact(payload.length, Short.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            pixels.asShortBuffer().put(payload);
            return create(label, new Payload(field.sizeX(), field.sizeY(), field.sizeZ(), List.of(pixels),
                    new NativeTextureStorage(TextureStorageFormat.RGBA16F, false), null, null));
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    public static ComputeVolume createSampled(String label, int width, int height, int depth, List<ByteBuffer> mips,
                                              NativeTextureStorage storage, Sampling sampling) {
        if (!storage.format().floatingColor()
                && (sampling.xy().minMag() != TextureMetadata.FilterMode.POINT || sampling.xy().mip() != TextureMetadata.FilterMode.POINT))
            throw new IllegalArgumentException("Integer 3D images require point filtering");
        return create(label, new Payload(width, height, depth, mips, storage, java.util.Objects.requireNonNull(sampling), null));
    }

    public static ComputeVolume createTarget(String label, int width, int height, int depth, GpuFormat format,
                                             boolean writable, boolean srgb, float[] clear, Sampling sampling) {
        if (format == GpuFormat.R32_SINT) throw new IllegalArgumentException("Use a signed grid for integer volume storage");
        if (srgb && format != GpuFormat.RGBA8_UNORM) throw new IllegalArgumentException("sRGB volume targets require RGBA8_UNORM");
        return create(label, new Payload(width, height, depth, List.of(),
                new NativeTextureStorage(TextureStorageFormat.forRenderTarget(format), srgb),
                java.util.Objects.requireNonNull(sampling), new Target(format, writable, clear)));
    }

    public static ComputeVolume createSignedGrid(String label, int width, int height, int depth, boolean writable) {
        return create(label, new Payload(width, height, depth, List.of(),
                new NativeTextureStorage(TextureStorageFormat.R32_SINT, false), SIGNED_GRID_SAMPLING,
                new Target(GpuFormat.R32_SINT, writable, new float[4])));
    }

    public final void clearSigned(int value) {
        RenderSystem.assertOnRenderThread();
        if (isClosed() || format() != GpuFormat.R32_SINT || !writable())
            throw new IllegalStateException("Signed grid is not a live storage image");
        clearSignedInternal(value);
    }

    abstract void clearSignedInternal(int value);

    public final void clearColor(float[] value) {
        RenderSystem.assertOnRenderThread();
        if (isClosed() || target == null || !floatingColor() || value.length != 4)
            throw new IllegalArgumentException("Invalid color volume clear");
        clearColorInternal(value);
    }

    abstract void clearColorInternal(float[] value);

    public final void copyTo(ComputeVolume destination) {
        RenderSystem.assertOnRenderThread();
        if (isClosed() || destination.isClosed() || target == null || destination.target == null
                || !storage.equals(destination.storage) || width != destination.width
                || height != destination.height || depth != destination.depth)
            throw new IllegalArgumentException("Volume copy requires live targets with matching native storage and dimensions");
        if (destination != this) copyInternal(destination);
    }

    abstract void copyInternal(ComputeVolume destination);

    public static boolean supportsGridFormat(GpuFormat format) {
        RenderSystem.assertOnRenderThread();
        var storage = TextureStorageFormat.forRenderTarget(format);
        return switch (RenderSystem.getDevice().backend) {
            case GlDevice ignored -> GlComputeVolume.supportsGridFormat(storage);
            case VulkanDevice device -> VulkanComputeVolume.supportsGridFormat(device, storage);
            default -> false;
        };
    }

    private static ComputeVolume create(String label, Payload source) {
        RenderSystem.assertOnRenderThread();
        return switch (RenderSystem.getDevice().backend) {
            case GlDevice gl -> new GlComputeVolume(gl, label, source);
            case VulkanDevice vk -> new VulkanComputeVolume(vk, label, source);
            default -> throw new IllegalStateException("Unsupported volume texture backend");
        };
    }

    public final GpuFormat format() {
        if (target != null) return target.format();
        return switch (storage.format()) {
            case R32_UINT -> GpuFormat.R32_UINT;
            case R32_SINT -> GpuFormat.R32_SINT;
            default -> GpuFormat.RGBA16_FLOAT;
        };
    }
    public final boolean writable() { return target != null && target.writable(); }
    public final boolean accepts(ShaderBinding.SampledTexture binding) {
        if (!storage.format().floatingColor())
            return !binding.dynamicFloatFormat() && binding.format() == format();
        return sampling == null ? binding.format() == format() : binding.dynamicFloatFormat() && storage.format().floatingColor();
    }
    public final boolean hasSampling(Sampling expected) { return expected.equals(sampling); }
    public final boolean floatingColor() { return storage.format().floatingColor(); }
    public final String physicalFormat() { return storage.toString(); }
    public final int width() { return width; }
    public final int height() { return height; }
    public final int depth() { return depth; }
    public final int mipLevels() { return mips; }
    public final boolean isClosed() { return references == 0; }
    public final boolean isOwnerReleased() { return ownerReleased; }
    public final void restoreCache(CommandEncoder encoder, NiagaraCacheVolumeFrame frame) {
        RenderSystem.assertOnRenderThread();
        if (!frame.writePixels()) return;
        if (target == null || isClosed() || format() != RenderTargetTextureDefinition.gpuFormat(frame.format())
                || width != frame.width() || height != frame.height() || depth != frame.depth())
            throw new IllegalArgumentException("Live Niagara volume target does not match the cached image");
        writeCacheTiles(encoder, frame);
    }
    abstract void writeCacheTiles(CommandEncoder encoder, NiagaraCacheVolumeFrame frame);

    public final void copyCachePixels(CommandEncoder encoder, GpuBufferSlice destination) {
        RenderSystem.assertOnRenderThread();
        long bytes = Math.multiplyExact((long)width * height, (long)depth * format().blockSize());
        if (target == null || isClosed() || destination.length() != bytes)
            throw new IllegalArgumentException("Invalid Niagara volume cache readback");
        readCachePixels(encoder, destination);
    }
    abstract void readCachePixels(CommandEncoder encoder, GpuBufferSlice destination);
    public final GpuTextureView rasterView() {
        RenderSystem.assertOnRenderThread();
        if (isClosed()) throw new IllegalStateException("Volume image was released");
        if (rasterView == null) rasterView = createRasterView();
        return rasterView;
    }
    public final GpuSampler rasterSampler() {
        RenderSystem.assertOnRenderThread();
        if (isClosed()) throw new IllegalStateException("Volume image was released");
        return samplerObject(0);
    }
    final GpuSampler createSamplerObject(int axes) {
        var settings = sampling == null
                ? new EffectSamplerFactory.Settings(address(axes, 1), address(axes, 2),
                        TextureMetadata.FilterMode.LINEAR, TextureMetadata.FilterMode.POINT, 1)
                : sampling.xy();
        var addressZ = sampling == null ? address(axes, 4) : sampling.z();
        return EffectSamplerFactory.create(settings, addressZ, mipLevels(), 0.0f);
    }
    private static TextureMetadata.Address address(int axes, int bit) {
        return (axes & bit) != 0 ? TextureMetadata.Address.WRAP : TextureMetadata.Address.CLAMP;
    }
    abstract GpuTextureView createRasterView();
    abstract GpuSampler samplerObject(int axes);
    final @Nullable GpuTextureView detachRasterView() {
        GpuTextureView view = rasterView;
        rasterView = null;
        return view;
    }
    public static int maximumSize() {
        RenderSystem.assertOnRenderThread();
        return switch (RenderSystem.getDevice().backend) {
            case GlDevice ignored -> org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL12C.GL_MAX_3D_TEXTURE_SIZE);
            case VulkanDevice vk -> {
                try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    var properties = org.lwjgl.vulkan.VkPhysicalDeviceProperties.calloc(stack);
                    org.lwjgl.vulkan.VK12.vkGetPhysicalDeviceProperties(vk.vkDevice().getPhysicalDevice(), properties);
                    yield properties.limits().maxImageDimension3D();
                }
            }
            default -> throw new IllegalStateException("Unsupported volume texture backend");
        };
    }
    public final Lease retain() {
        RenderSystem.assertOnRenderThread();
        if (isClosed()) throw new IllegalStateException("Volume image was released");
        var lease = new Lease(this);
        references = Math.incrementExact(references);
        return lease;
    }

    public static final class Lease implements AutoCloseable {
        private @Nullable ComputeVolume volume;
        private Lease(ComputeVolume volume) { this.volume = volume; }
        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            var previous = volume;
            volume = null;
            if (previous != null) previous.release();
        }
    }

    abstract int glTexture();
    abstract int glStorageTexture();
    abstract int glStorageFormat();
    abstract int glSampler(int axes);
    abstract long vkView();
    abstract long vkStorageView();
    abstract long vkSampler(int axes);
    @Override public final void close() {
        RenderSystem.assertOnRenderThread();
        if (ownerReleased) return;
        ownerReleased = true;
        release();
    }
    private void release() { if (--references == 0) releaseNative(); }
    abstract void releaseNative();
    public static int axes(boolean tileX, boolean tileY, boolean tileZ) {
        return (tileX ? 1 : 0) | (tileY ? 2 : 0) | (tileZ ? 4 : 0);
    }
}
