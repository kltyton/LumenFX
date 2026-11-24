package com.kltyton.lumenfx.client.render.compute;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.system.MemoryUtil;

import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

/** Native 3D texture on the host OpenGL device. */
final class GlComputeVolume extends ComputeVolume {
    private final int texture, sampled, storageFormat;
    private final GlVolumeGpuTexture hostTexture;
    private final GpuSampler[] samplers = new GpuSampler[8];

    static boolean supportsGridFormat(TextureStorageFormat format) {
        return supportsGridFormat(GL12C.GL_TEXTURE_3D, format);
    }

    static boolean supportsGridFormat(int target, TextureStorageFormat format) {
        int internal = format.glFormat(false);
        return GL43C.glGetInternalformati(target, internal, GL43C.GL_SHADER_IMAGE_LOAD) != 0
                && GL43C.glGetInternalformati(target, internal, GL43C.GL_SHADER_IMAGE_STORE) != 0
                && GL43C.glGetInternalformati(target, internal, GL43C.GL_FILTER) != 0;
    }
    GlComputeVolume(GlDevice device, String label, Payload source) {
        super(source);
        int maximum = GL11C.glGetInteger(GL12C.GL_MAX_3D_TEXTURE_SIZE);
        if (width() > maximum || height() > maximum || depth() > maximum)
            throw new IllegalArgumentException("Volume exceeds OpenGL 3D texture limit: " + label);
        var storage = source.storage();
        var target = source.target();
        int internal = target == null ? storage.glFormat() : storage.format().glFormat(false);
        if (!storage.format().supportedGl(GL.getCapabilities()))
            throw new IllegalArgumentException("Unsupported OpenGL volume storage " + storage);
        if (writable() && GL43C.glGetInternalformati(GL12C.GL_TEXTURE_3D, internal, GL43C.GL_SHADER_IMAGE_STORE) == 0)
            throw new IllegalArgumentException("Volume format lacks OpenGL shader image stores: " + storage);
        if (writable() && format() == GpuFormat.R32_SINT
                && GL43C.glGetInternalformati(GL12C.GL_TEXTURE_3D, internal, GL43C.GL_SHADER_IMAGE_ATOMIC) == 0)
            throw new IllegalArgumentException("OpenGL R32I volume lacks signed image atomics");
        int binding = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        int unpack = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int alignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        int rowLength = GL11C.glGetInteger(GL12C.GL_UNPACK_ROW_LENGTH);
        int imageHeight = GL11C.glGetInteger(GL12C.GL_UNPACK_IMAGE_HEIGHT);
        int skipRows = GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_ROWS);
        int skipPixels = GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_PIXELS);
        int skipImages = GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_IMAGES);
        int swapBytes = GL11C.glGetInteger(GL11C.GL_UNPACK_SWAP_BYTES);
        int id = GlStateManager._genTexture();
        int alias = 0;
        try {
            GlStateManager.clearGlErrors();
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, id);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_MAX_LEVEL, mipLevels() - 1);
            if (!storage.format().floatingColor()) {
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_NEAREST);
                GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            }
            GL42C.glTexStorage3D(GL12C.GL_TEXTURE_3D, mipLevels(), internal, width(), height(), depth());
            for (int mip = 0; mip < source.mips().size(); mip++) {
                int w = Math.max(1, width() >> mip), h = Math.max(1, height() >> mip), d = Math.max(1, depth() >> mip);
                var data = source.mips().get(mip).duplicate();
                if (storage.format().compressed()) GL13C.glCompressedTexSubImage3D(GL12C.GL_TEXTURE_3D, mip, 0, 0, 0, w, h, d,
                        storage.glFormat(), data);
                else GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, mip, 0, 0, 0, w, h, d,
                        storage.format().glPixelFormat(), storage.format().glPixelType(), data);
            }
            if (target != null) {
                if (format() == GpuFormat.R32_SINT) GlComputeRuntime.clearSignedVolume(id, depth(), 0);
                else GlComputeRuntime.clearColorLayers(id, true, 1, depth(), source.targetClear());
                if (storage.srgb()) {
                    alias = GlStateManager._genTexture();
                    GL43C.glTextureView(alias, GL12C.GL_TEXTURE_3D, id, storage.glFormat(), 0, 1, 0, 1);
                }
            }
            int error = GlStateManager._getError();
            if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("OpenGL volume upload failed for " + label + ": " + error);
            hostTexture = new GlVolumeGpuTexture(device, label, format(), width(), height(), depth(), mipLevels(),
                    alias == 0 ? id : alias);
            texture = id;
            sampled = alias == 0 ? id : alias;
            storageFormat = internal;
        } catch (RuntimeException | Error error) {
            if (alias != 0) GlStateManager._deleteTexture(alias);
            GlStateManager._deleteTexture(id);
            throw error;
        } finally {
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, alignment);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, swapBytes);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, imageHeight);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_ROWS, skipRows);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, skipImages);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, unpack);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, binding);
        }
    }

    @Override int glTexture() { if (isClosed()) throw new IllegalStateException("Volume closed"); return sampled; }
    @Override GpuTextureView createRasterView() {
        if (isClosed()) throw new IllegalStateException("Volume closed");
        return RenderSystem.getDevice().createTextureView(hostTexture);
    }
    @Override GpuSampler samplerObject(int axes) {
        if (isClosed()) throw new IllegalStateException("Volume closed");
        int index = sampling == null ? java.util.Objects.checkIndex(axes, samplers.length) : 0;
        if (samplers[index] == null) samplers[index] = createSamplerObject(axes);
        return samplers[index];
    }
    @Override void clearSignedInternal(int value) { GlComputeRuntime.clearSignedVolume(texture, depth(), value); }
    @Override void clearColorInternal(float[] value) { GlComputeRuntime.clearColorLayers(texture, true, 1, depth(), value); }

    @Override void copyInternal(ComputeVolume destination) {
        var target = (GlComputeVolume)destination;
        int barriers = GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT;
        GL42C.glMemoryBarrier(barriers);
        GL43C.glCopyImageSubData(texture, GL12C.GL_TEXTURE_3D, 0, 0, 0, 0,
                target.texture, GL12C.GL_TEXTURE_3D, 0, 0, 0, 0, width(), height(), depth());
        GL42C.glMemoryBarrier(barriers);
    }

    @Override void writeCacheTiles(CommandEncoder encoder, NiagaraCacheVolumeFrame frame) {
        clearColor(frame.fallback());
        if (frame.pages().isEmpty()) return;
        var stored = frame.pixelData();
        var pixels = MemoryUtil.memAlloc(stored.remaining());
        int binding = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        int unpack = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] parameters = {GL11C.GL_UNPACK_ALIGNMENT, GL11C.GL_UNPACK_SWAP_BYTES, GL12C.GL_UNPACK_ROW_LENGTH,
                GL12C.GL_UNPACK_IMAGE_HEIGHT, GL12C.GL_UNPACK_SKIP_ROWS, GL12C.GL_UNPACK_SKIP_PIXELS, GL12C.GL_UNPACK_SKIP_IMAGES};
        int[] previous = new int[parameters.length];
        for (int index = 0; index < parameters.length; index++) previous[index] = GL11C.glGetInteger(parameters[index]);
        int tile = NiagaraCacheVolumeFrame.TILE_SIZE;
        int[] desired = {1, 0, tile, tile, 0, 0, 0};
        try {
            pixels.put(stored).flip();
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int index = 0; index < parameters.length; index++) GL11C.glPixelStorei(parameters[index], desired[index]);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
            var format = TextureStorageFormat.forRenderTarget(format());
            for (var page : frame.pages()) {
                int offset = Math.multiplyExact(page.tile(), frame.tileBytes());
                pixels.limit(offset + frame.tileBytes()).position(offset);
                GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, page.x(), page.y(), page.z(),
                        Math.min(tile, width() - page.x()), Math.min(tile, height() - page.y()), Math.min(tile, depth() - page.z()),
                        format.glPixelFormat(), format.glPixelType(), pixels);
            }
            GL42C.glMemoryBarrier(GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        } finally {
            for (int index = 0; index < parameters.length; index++) GL11C.glPixelStorei(parameters[index], previous[index]);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, unpack);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, binding);
            MemoryUtil.memFree(pixels.clear());
        }
    }
    @Override int glStorageTexture() { if (isClosed() || !writable()) throw new IllegalStateException("Volume is not writable"); return texture; }
    @Override void readCachePixels(CommandEncoder encoder, GpuBufferSlice destination) {
        int binding = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        int pack = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] parameters = {GL11C.GL_PACK_ALIGNMENT, GL11C.GL_PACK_SWAP_BYTES, GL11C.GL_PACK_ROW_LENGTH,
                GL12C.GL_PACK_IMAGE_HEIGHT, GL11C.GL_PACK_SKIP_ROWS, GL11C.GL_PACK_SKIP_PIXELS, GL12C.GL_PACK_SKIP_IMAGES};
        int[] previous = new int[parameters.length];
        for (int index = 0; index < parameters.length; index++) previous[index] = GL11C.glGetInteger(parameters[index]);
        try {
            GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, ((GlBuffer)destination.buffer()).handle());
            for (int index = 0; index < parameters.length; index++) GL11C.glPixelStorei(parameters[index], index == 0 ? 1 : 0);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
            var format = TextureStorageFormat.forRenderTarget(format());
            GL11C.glGetTexImage(GL12C.GL_TEXTURE_3D, 0, format.glPixelFormat(), format.glPixelType(), destination.offset());
        } finally {
            for (int index = 0; index < parameters.length; index++) GL11C.glPixelStorei(parameters[index], previous[index]);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, pack);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, binding);
        }
    }
    @Override int glStorageFormat() { return storageFormat; }
    @Override int glSampler(int axes) { return ((GlSampler)samplerObject(axes)).getId(); }
    @Override long vkView() { throw new IllegalStateException("OpenGL volume has no Vulkan view"); }
    @Override long vkStorageView() { throw new IllegalStateException("OpenGL volume has no Vulkan view"); }
    @Override long vkSampler(int axes) { throw new IllegalStateException("OpenGL volume has no Vulkan sampler"); }

    @Override void releaseNative() {
        GpuSampler[] captured = samplers.clone();
        GpuTextureView view = detachRasterView();
        RenderSystem.queueFencedTask(() -> {
            for (GpuSampler sampler : captured) if (sampler != null) sampler.close();
            try { if (view != null) view.close(); }
            finally {
                hostTexture.close();
                if (sampled != texture) GlStateManager._deleteTexture(texture);
            }
        });
    }
}
