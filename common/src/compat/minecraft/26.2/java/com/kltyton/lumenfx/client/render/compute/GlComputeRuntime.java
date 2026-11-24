package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import java.nio.ByteBuffer;
import java.util.function.IntConsumer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.MemoryStack;

/** GL 26.2 compute runtime. All work stays on the host render thread and host command stream. */
final class GlComputeRuntime extends ComputeRuntime {
    private final GpuDevice device;
    private final GlDevice gl;
    private final int maxTexture2D;
    private final int maxTextureArrayLayers;
    private final java.util.Map<GpuFormat, Boolean> colorImageStores = new java.util.EnumMap<>(GpuFormat.class);

    GlComputeRuntime(final GpuDevice device, final GlDevice gl) {
        this.device = device;
        this.gl = gl;
        this.maxTexture2D = GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE);
        this.maxTextureArrayLayers = GL11C.glGetInteger(GL30C.GL_MAX_ARRAY_TEXTURE_LAYERS);
    }

    @Override public boolean supportsColorImageStores(GpuFormat format) {
        RenderSystem.assertOnRenderThread();
        if (!com.kltyton.lumenfx.client.render.compute.format.StorageImageFormats.floatingColor(format)) return false;
        return colorImageStores.computeIfAbsent(format, value -> {
            int internal = GlConst.toGlInternalId(value);
            return internal != 0 && GL43C.glGetInternalformati(GL11C.GL_TEXTURE_2D, internal, GL43C.GL_SHADER_IMAGE_STORE) != 0;
        });
    }

    @Override public boolean supportsGridArrayFormat(GpuFormat format) {
        RenderSystem.assertOnRenderThread();
        return GlComputeVolume.supportsGridFormat(GL30C.GL_TEXTURE_2D_ARRAY, TextureStorageFormat.forRenderTarget(format));
    }

    @Override public boolean supportsSignedImage2D() {
        RenderSystem.assertOnRenderThread();
        int target = GL11C.GL_TEXTURE_2D, format = GL30C.GL_R32I;
        return GL43C.glGetInternalformati(target, format, GL43C.GL_SHADER_IMAGE_LOAD) != 0
                && GL43C.glGetInternalformati(target, format, GL43C.GL_SHADER_IMAGE_STORE) != 0
                && GL43C.glGetInternalformati(target, format, GL43C.GL_SHADER_IMAGE_ATOMIC) != 0;
    }

    @Override protected void clearSignedImage2DInternal(ComputeTexture texture, int value) {
        clearSignedTexture(texture.glTextureId(), false, 1, value);
    }

    @Override protected void clearColor2DInternal(ComputeTexture texture) {
        clearColorArrayInternal(texture, 1, new float[4]);
    }

    @Override protected void clearColorArrayInternal(ComputeTexture texture, int layers, float[] color) {
        clearColorLayers(texture.glTextureId(), texture.isArray(), texture.mips(), layers, color);
    }

    static void clearColorLayers(int texture, boolean layered, int mips, int layers, float[] color) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var clear = stack.floats(color);
            clearLayers(texture, layered, mips, layers, () -> GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, clear));
        }
    }

    static void clearSignedVolume(int texture, int layers, int value) {
        clearSignedTexture(texture, true, layers, value);
    }

    private static void clearSignedTexture(int texture, boolean layered, int layers, int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var clear = stack.ints(value, 0, 0, 0);
            var capabilities = GL.getCapabilities();
            if (capabilities.OpenGL44 || capabilities.GL_ARB_clear_texture) {
                int barriers = GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                        | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT;
                GL42C.glMemoryBarrier(barriers);
                GL44C.glClearTexImage(texture, 0, GL30C.GL_RED_INTEGER, GL11C.GL_INT, clear);
                GL42C.glMemoryBarrier(barriers);
            } else {
                clearLayers(texture, layered, 1, layers, () -> GL30C.glClearBufferiv(GL11C.GL_COLOR, 0, clear));
            }
        }
    }

    private static void clearLayers(int texture, boolean layered, int mips, int layers, Runnable clearLayer) {
        int previous = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        int framebuffer = GL30C.glGenFramebuffers();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, mask);
            try {
                GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                GL11C.glColorMask(true, true, true, true);
                for (int mip = 0; mip < mips; mip++) for (int layer = 0; layer < layers; layer++) {
                    if (layered) GL30C.glFramebufferTextureLayer(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                            texture, mip, layer);
                    else GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                            GL11C.GL_TEXTURE_2D, texture, mip);
                    if (GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE)
                        throw new IllegalStateException("Image is not color-clearable: " + texture);
                    clearLayer.run();
                }
                GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                        | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
            } finally {
                GL11C.glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
            }
        } finally {
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previous);
            GL30C.glDeleteFramebuffers(framebuffer);
        }
    }

    @Override protected void clearBufferInternal(GpuBufferSlice slice, int value) {
        int previous = GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, ((GlBuffer)slice.buffer()).handle());
            GL43C.glClearBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, GL30C.GL_R32UI, slice.offset(),
                    slice.length(), GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, stack.ints(value));
            GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        } finally {
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, previous);
        }
    }

    @Override
    protected ComputeTexture createTextureInternal(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final boolean array,
            final int layers,
            final int mips,
            final boolean renderAttachment,
            final boolean storageWritable
    ) {
        verifyTextureArgs(label, format, width, height, array, layers, mips);
        if (width > this.maxTexture2D || height > this.maxTexture2D) {
            throw new IllegalArgumentException(
                    "Compute texture " + width + "x" + height
                            + " exceeds GL_MAX_TEXTURE_SIZE " + this.maxTexture2D
            );
        }
        if (GlConst.toGlInternalId(format) == 0
                || GlConst.toGlExternalId(format) == 0
                || GlConst.toGlType(format) == 0) {
            throw new IllegalArgumentException("Format cannot be allocated as a GL storage texture: " + format);
        }

        if (!array) {
            final boolean srgb = com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.mutableSrgbAllocation();
            final GlImmutableTexture2D texture = GlImmutableTexture2D.create(
                    this.gl, label, format, width, height, mips, renderAttachment
            );
            try {
                final GpuTextureView view = srgb
                        ? com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.glView(this.gl, texture, mips)
                        : this.device.createTextureView(texture);
                return new ComputeTexture(
                        texture,
                        view,
                        format,
                        width,
                        height,
                        false,
                        1,
                        mips,
                        0L,
                        texture.glId(),
                        GL11C.GL_TEXTURE_2D,
                        GlConst.toGlInternalId(format),
                        (mipLevel, baseLayer, layerCount, arrayView) -> createSampledView(
                                texture, format, mipLevel, baseLayer, layerCount, arrayView, srgb
                        ),
                        null,
                        null,
                        id -> RenderSystem.queueFencedTask(() -> GlStateManager._deleteTexture(id)),
                        () -> RenderSystem.queueFencedTask(() -> {
                            view.close();
                            texture.close();
                        })
                );
            } catch (RuntimeException error) {
                texture.close();
                throw error;
            }
        }

        final GlArrayTexture texture = GlArrayTexture.create(
                this.gl, label, format, width, height, layers, mips, renderAttachment, storageWritable,
                this.maxTexture2D, this.maxTextureArrayLayers
        );
        final boolean srgb = com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.mutableSrgbAllocation();
        final int sampled;
        try {
            sampled = srgb ? createSampledView(texture, format, 0, 0, layers, true, true, mips).glTextureId() : 0;
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
        return new ComputeTexture(
                texture,
                null,
                format,
                width,
                height,
                true,
                layers,
                mips,
                0L,
                texture.glId(),
                GlArrayTexture.TARGET,
                GlConst.toGlInternalId(format),
                (mipLevel, baseLayer, layerCount, arrayView) -> createSampledView(
                        texture, format, mipLevel, baseLayer, layerCount, arrayView, srgb
                ),
                null,
                null,
                id -> RenderSystem.queueFencedTask(() -> GlStateManager._deleteTexture(id)),
                () -> RenderSystem.queueFencedTask(() -> {
                    if (sampled != 0) GlStateManager._deleteTexture(sampled);
                    texture.close();
                }),
                sampled
        );
    }

    @Override
    protected ComputeTexture createZeroTextureInternal(
            final String label,
            final GpuFormat format,
            final boolean array
    ) {
        final ComputeTexture texture = createTextureInternal(label, format, 1, 1, array, 1, 1, false);
        try {
            uploadZeroGl(texture, format, array);
            return texture;
        } catch (RuntimeException error) {
            texture.close();
            throw error;
        }
    }

    private static void uploadZeroGl(
            final ComputeTexture texture,
            final GpuFormat format,
            final boolean array
    ) {
        final int external = GlConst.toGlExternalId(format);
        final int type = GlConst.toGlType(format);
        if (external == 0 || type == 0) {
            throw new IllegalArgumentException("Format cannot be zero-uploaded: " + format);
        }
        final int target = texture.glTextureTarget();
        final int bindingName = array ? GL30C.GL_TEXTURE_BINDING_2D_ARRAY : GL11C.GL_TEXTURE_BINDING_2D;
        final int previousBinding = GL11C.glGetInteger(bindingName);
        final int previousPbo = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        final int previousAlignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        final int previousRowLength = GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH);
        final int previousImageHeight = GL11C.glGetInteger(GL12C.GL_UNPACK_IMAGE_HEIGHT);
        final int previousSkipRows = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS);
        final int previousSkipPixels = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS);
        final int previousSkipImages = GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_IMAGES);
        final int previousSwapBytes = GL11C.glGetInteger(GL11C.GL_UNPACK_SWAP_BYTES);
        final int previousLsbFirst = GL11C.glGetInteger(GL11C.GL_UNPACK_LSB_FIRST);
        final ByteBuffer zeros = MemoryUtil.memCalloc(format.blockSize());
        try {
            GL15C.glBindBuffer(GlConst.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_LSB_FIRST, 0);
            GL11C.glBindTexture(target, texture.glTextureId());
            if (array) {
                GL12C.glTexSubImage3D(target, 0, 0, 0, 0, 1, 1, 1, external, type, zeros);
            } else {
                GL11C.glTexSubImage2D(target, 0, 0, 0, 1, 1, external, type, zeros);
            }
        } finally {
            GL11C.glBindTexture(target, previousBinding);
            GL15C.glBindBuffer(GlConst.GL_PIXEL_UNPACK_BUFFER, previousPbo);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, previousAlignment);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, previousRowLength);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, previousImageHeight);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, previousSkipRows);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, previousSkipPixels);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, previousSkipImages);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, previousSwapBytes);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_LSB_FIRST, previousLsbFirst);
            MemoryUtil.memFree(zeros);
        }
    }

    @Override
    protected void copyArrayRegionInternal(
            final ComputeTexture source,
            final int sourceLayer,
            final int sourceMip,
            final ComputeTexture destination,
            final int destinationMip,
            final int destinationX,
            final int destinationY,
            final int width,
            final int height,
            final int layers
    ) {
        RenderSystem.assertOnRenderThread();
        GL42C.glMemoryBarrier(GL42C.GL_ALL_BARRIER_BITS);
        GL43C.glCopyImageSubData(
                source.glTextureId(),
                source.glTextureTarget(),
                sourceMip,
                0,
                0,
                sourceLayer,
                destination.glTextureId(),
                destination.glTextureTarget(),
                destinationMip,
                destinationX,
                destinationY,
                0,
                width,
                height,
                layers
        );
        GL42C.glMemoryBarrier(GL42C.GL_ALL_BARRIER_BITS);
    }

    private static ComputeTexture.SampledView createSampledView(
            final GlTexture texture,
            final GpuFormat format,
            final int mipLevel,
            final int baseLayer,
            final int layerCount,
            final boolean arrayView
    ) {
        return createSampledView(texture, format, mipLevel, baseLayer, layerCount, arrayView, false);
    }

    private static ComputeTexture.SampledView createSampledView(
            GlTexture texture, GpuFormat format, int mipLevel, int baseLayer, int layerCount, boolean arrayView, boolean srgb
    ) {
        return createSampledView(texture, format, mipLevel, baseLayer, layerCount, arrayView, srgb, 1);
    }

    private static ComputeTexture.SampledView createSampledView(GlTexture texture, GpuFormat format, int mipLevel,
            int baseLayer, int layerCount, boolean arrayView, boolean srgb, int mipCount) {
        final int target = arrayView ? GL30C.GL_TEXTURE_2D_ARRAY : GL11C.GL_TEXTURE_2D;
        final int bindingName = arrayView ? GL30C.GL_TEXTURE_BINDING_2D_ARRAY : GL11C.GL_TEXTURE_BINDING_2D;
        final int previousBinding = GL11C.glGetInteger(bindingName);
        final int viewId = GlStateManager._genTexture();
        try {
            GL43C.glTextureView(
                    viewId,
                    target,
                    texture.glId(),
                    srgb ? GL21C.GL_SRGB8_ALPHA8 : GlConst.toGlInternalId(format),
                    mipLevel,
                    mipCount,
                    baseLayer,
                    arrayView ? layerCount : 1
            );
            GL11C.glBindTexture(target, viewId);
            GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(target, GL12C.GL_TEXTURE_MAX_LEVEL, mipCount - 1);
        } catch (RuntimeException error) {
            GlStateManager._deleteTexture(viewId);
            throw error;
        } finally {
            GL11C.glBindTexture(target, previousBinding);
        }
        return new ComputeTexture.SampledView(0L, viewId, target, true);
    }

    @Override
    public ComputeKernel createKernel(final ShaderProgram program) {
        return new GlComputeKernel(this.device, this.gl, program);
    }

    @Override
    public int maxTexture2D() {
        return this.maxTexture2D;
    }

    @Override
    public int maxTextureArrayLayers() {
        return this.maxTextureArrayLayers;
    }

    @Override public long maxStorageBufferBytes() {
        RenderSystem.assertOnRenderThread();
        return Math.min(GL32C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE),
                device.getDeviceInfo().limits().maxMemoryAllocationSize());
    }

    @Override public int maxWorkGroups(int axis) {
        RenderSystem.assertOnRenderThread();
        return GL30C.glGetIntegeri(GL43C.GL_MAX_COMPUTE_WORK_GROUP_COUNT, java.util.Objects.checkIndex(axis, 3));
    }

    @Override public long storageBufferAlignment() {
        RenderSystem.assertOnRenderThread();
        return Integer.toUnsignedLong(GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
    }

    @Override public long maxTexelBufferElements() {
        RenderSystem.assertOnRenderThread();
        return Integer.toUnsignedLong(GL11C.glGetInteger(GL31C.GL_MAX_TEXTURE_BUFFER_SIZE));
    }
}
