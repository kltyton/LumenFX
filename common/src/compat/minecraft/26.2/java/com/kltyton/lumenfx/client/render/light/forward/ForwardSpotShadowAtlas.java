package com.kltyton.lumenfx.client.render.light.forward;

import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Copies the current projected local-light depth maps into one sampleable forward-pass atlas. */
final class ForwardSpotShadowAtlas implements AutoCloseable {
    record Slot(int x, int y, int size, VolumeShadow shadow) { }
    record Frame(GpuTextureView texture, Map<ScenePointLight, Slot> slots) { }
    private record Source(ScenePointLight light, VolumeShadow shadow, int size) { }
    private @Nullable GpuTexture atlas;
    private @Nullable GpuTextureView atlasView;
    private int atlasWidth, atlasHeight;
    private @Nullable GpuTexture fallback;
    private @Nullable GpuTextureView fallbackView;

    Frame prepare(CommandEncoder encoder, List<ScenePointLight> visible,
                  Map<ScenePointLight, VolumeShadow> shadows) {
        RenderSystem.assertOnRenderThread();
        var sources = new ArrayList<Source>();
        for (var light : visible) {
            var shadow = shadows.get(light);
            if (shadow != null && shadow.matrices().spot() && shadow.depth().viewCount() == 1)
                sources.add(new Source(light, shadow, shadow.depth().size()));
        }
        if (sources.isEmpty()) return new Frame(fallback(encoder), Map.of());

        var limits = RenderSystem.getDevice().getDeviceInfo().limits();
        int maximum = Math.min(limits.maxTextureSize(), limits.maxTextureSizeForFormat(GpuFormat.D32_FLOAT));
        var rectangles = sources.stream().map(source -> new ForwardShadowAtlasLayout.Rectangle(source.size(), source.size())).toList();
        var layout = ForwardShadowAtlasLayout.pack(rectangles, maximum);
        ensureAtlas(layout.width(), layout.height());
        var texture = Objects.requireNonNull(atlas);
        var slots = new IdentityHashMap<ScenePointLight, Slot>();
        for (int index = 0; index < sources.size(); index++) {
            var source = sources.get(index);
            var placement = layout.placements().get(index);
            var depth = source.shadow().depth().sampled().texture();
            if (depth.isClosed() || depth.getFormat() != GpuFormat.D32_FLOAT
                    || (depth.usage() & GpuTexture.USAGE_COPY_SRC) == 0)
                throw new IllegalStateException("Forward Spot shadow source is not a live copyable D32 image");
            encoder.copyTextureToTexture(depth, texture, 0, placement.x(), placement.y(), 0, 0,
                    source.size(), source.size());
            slots.put(source.light(), new Slot(placement.x(), placement.y(), source.size(), source.shadow()));
        }
        return new Frame(Objects.requireNonNull(atlasView), java.util.Collections.unmodifiableMap(slots));
    }

    private GpuTextureView fallback(CommandEncoder encoder) {
        if (fallbackView != null) return fallbackView;
        var device = RenderSystem.getDevice();
        var texture = device.createTexture("LumenFX forward no Spot shadow depth",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
                GpuFormat.D32_FLOAT, 1, 1, 1, 1);
        fallback = texture;
        try {
            encoder.clearDepthTexture(texture, 0.0);
            var view = device.createTextureView(texture);
            fallbackView = view;
            return view;
        } catch (RuntimeException | Error failure) {
            texture.close();
            fallback = null;
            throw failure;
        }
    }

    private void ensureAtlas(int width, int height) {
        if (atlas != null && atlasWidth == width && atlasHeight == height) return;
        var device = RenderSystem.getDevice();
        long bytes = Math.multiplyExact(Math.multiplyExact((long)width, height), Integer.BYTES);
        if (bytes > device.getDeviceInfo().limits().maxMemoryAllocationSize())
            throw new IllegalStateException("Forward Spot shadow atlas exceeds the device's single-allocation limit");
        var next = device.createTexture("LumenFX forward Spot shadow atlas",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.D32_FLOAT, width, height, 1, 1);
        GpuTextureView nextView = null;
        try {
            nextView = device.createTextureView(next);
        } catch (RuntimeException | Error failure) {
            next.close();
            throw failure;
        }
        if (atlasView != null) atlasView.close();
        if (atlas != null) atlas.close();
        atlas = next;
        atlasView = nextView;
        atlasWidth = width;
        atlasHeight = height;
    }

    @Override public void close() {
        if (atlasView != null) { atlasView.close(); atlasView = null; }
        if (atlas != null) { atlas.close(); atlas = null; }
        if (fallbackView != null) { fallbackView.close(); fallbackView = null; }
        if (fallback != null) { fallback.close(); fallback = null; }
        atlasWidth = atlasHeight = 0;
    }
}
