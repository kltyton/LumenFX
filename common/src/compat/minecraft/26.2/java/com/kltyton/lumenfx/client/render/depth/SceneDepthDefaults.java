package com.kltyton.lumenfx.client.render.depth;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Far depth and zero integer stencil for source scene slots without a produced texture. */
public final class SceneDepthDefaults implements AutoCloseable {
    private record Owned(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
        @Override public void close() {
            try { view.close(); }
            finally { texture.close(); }
        }
    }

    private final Map<GpuFormat, Owned> depths = new LinkedHashMap<>();
    private @Nullable Owned stencil;

    public GpuTextureView depth(GpuFormat format) {
        RenderSystem.assertOnRenderThread();
        if (!format.hasDepthAspect()) throw new IllegalArgumentException("Scene depth requires a depth format");
        return depths.computeIfAbsent(format, key -> {
            var device = RenderSystem.getDevice();
            var texture = device.createTexture("LumenFX far scene depth",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
                    key, 1, 1, 1, 1);
            try {
                device.createCommandEncoder().clearDepthTexture(texture, 0.0);
                return new Owned(texture, device.createTextureView(texture));
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
        }).view();
    }

    public GpuTextureView stencil() {
        RenderSystem.assertOnRenderThread();
        if (stencil == null) {
            var device = RenderSystem.getDevice();
            var texture = device.createTexture("LumenFX zero custom stencil",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RG8_UINT, 1, 1, 1, 1);
            try {
                device.createCommandEncoder().writeToTexture(texture, ByteBuffer.allocateDirect(2), 0, 0, 0, 0, 1, 1);
                stencil = new Owned(texture, device.createTextureView(texture));
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
        }
        return stencil.view();
    }

    @Override public void close() {
        depths.values().forEach(Owned::close);
        depths.clear();
        if (stencil != null) { stencil.close(); stencil = null; }
    }
}
