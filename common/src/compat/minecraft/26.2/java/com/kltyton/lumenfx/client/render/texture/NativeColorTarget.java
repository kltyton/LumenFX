package com.kltyton.lumenfx.client.render.texture;

import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureCreation;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/** Owns an uncompressed physical color attachment and its host-managed view. */
public final class NativeColorTarget implements AutoCloseable {
    private final NativeTextureStorage storage;
    private final GpuTexture texture;
    private final GpuTextureView view;

    public NativeColorTarget(String label, int width, int height, boolean srgb) {
        RenderSystem.assertOnRenderThread();
        storage = new NativeTextureStorage(TextureStorageFormat.BGRA8, srgb);
        var device = RenderSystem.getDevice();
        if (!(device.backend instanceof com.mojang.blaze3d.opengl.GlDevice)
                && !(device.backend instanceof com.mojang.blaze3d.vulkan.VulkanDevice))
            throw new IllegalStateException("Native color storage has no backend adapter for " + device.backend.getClass().getName());
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
        texture = NativeTextureCreation.colorTarget(storage, () -> device.createTexture(label, usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1));
        try { view = NativeTextureCreation.colorTarget(storage, () -> device.createTextureView(texture)); }
        catch (RuntimeException | Error error) { texture.close(); throw error; }
    }

    public GpuTexture texture() { return texture; }
    public GpuTextureView view() { return view; }
    public NativeTextureStorage storage() { return storage; }
    @Override public void close() { view.close(); texture.close(); }
}
