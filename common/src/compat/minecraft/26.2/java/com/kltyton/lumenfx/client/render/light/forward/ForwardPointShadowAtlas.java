package com.kltyton.lumenfx.client.render.light.forward;

import com.kltyton.lumenfx.client.render.light.shadow.PointShadowMatrices;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.kltyton.lumenfx.client.render.texture.cube.CubeDepthCopy;
import com.kltyton.lumenfx.client.render.texture.cube.CubeDepthTarget;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Copies each live point-shadow face into a sampleable 2D atlas for forward translucent lights. */
final class ForwardPointShadowAtlas implements AutoCloseable {
    private static final String ATLAS = "LfxForwardPointShadowAtlas";
    private static final String DATA = "LfxForwardPointShadowData";
    private static final int FACE_COUNT = 6;
    private static final int MATRICES_OFFSET = 8;
    private static final int INVERSE_MATRICES_OFFSET = MATRICES_OFFSET + FACE_COUNT * 4;
    private static final int SLOT_TEXELS = INVERSE_MATRICES_OFFSET + FACE_COUNT * 4;
    private static final long SLOT_BYTES = SLOT_TEXELS * 4L * Float.BYTES;

    record Slot(int index) { }
    record Frame(GpuTextureView texture, GpuBufferSlice data, Map<ScenePointLight, Slot> slots) {
        void bind(RenderPass pass) {
            pass.setUniform(DATA, data);
            pass.bindTexture(ATLAS, texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
    }

    private record Source(ScenePointLight light, VolumeShadow shadow, CubeDepthTarget depth) { }

    private @Nullable GpuTexture atlas;
    private @Nullable GpuTextureView atlasView;
    private int atlasWidth, atlasHeight;
    private @Nullable GpuTexture fallback;
    private @Nullable GpuTextureView fallbackView;
    private @Nullable GpuBuffer metadata;

    static void layout(BindGroupLayout.Builder layout) {
        layout.withUniform(DATA, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT).withSampler(ATLAS);
    }

    Frame prepare(CommandEncoder encoder, List<ScenePointLight> visible,
                  Map<ScenePointLight, VolumeShadow> shadows, int requestedMaximum) {
        RenderSystem.assertOnRenderThread();
        var sources = new ArrayList<Source>();
        for (var light : visible) {
            var shadow = shadows.get(light);
            if (shadow == null || shadow.matrices().spot() || shadow.depth().viewCount() != FACE_COUNT) continue;
            if (!(shadow.depth() instanceof CubeDepthTarget cube))
                throw new IllegalStateException("Forward point shadow capture must retain its six-face cube depth target");
            Objects.requireNonNull(shadow.projectionSettings(), "Forward point shadows require the captured projection settings");
            sources.add(new Source(light, shadow, cube));
        }
        if (sources.isEmpty()) return new Frame(fallback(encoder), writeData(encoder, sources, List.of()), Map.of());

        var device = RenderSystem.getDevice();
        int maximum = Math.min(requestedMaximum, device.getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.D32_FLOAT));
        var rectangles = new ArrayList<ForwardShadowAtlasLayout.Rectangle>(Math.multiplyExact(sources.size(), FACE_COUNT));
        for (var source : sources) {
            int size = source.depth().size();
            for (int face = 0; face < FACE_COUNT; face++) rectangles.add(new ForwardShadowAtlasLayout.Rectangle(size, size));
        }
        var layout = ForwardShadowAtlasLayout.pack(rectangles, maximum);
        ensureAtlas(layout.width(), layout.height());
        var texture = Objects.requireNonNull(atlas);
        var slots = new IdentityHashMap<ScenePointLight, Slot>();
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            var source = sources.get(sourceIndex);
            int[] x = new int[FACE_COUNT], y = new int[FACE_COUNT];
            for (int face = 0; face < FACE_COUNT; face++) {
                var placement = layout.placements().get(sourceIndex * FACE_COUNT + face);
                x[face] = placement.x();
                y[face] = placement.y();
            }
            CubeDepthCopy.copyToAtlas(source.depth(), texture, x, y);
            slots.put(source.light(), new Slot(sourceIndex));
        }
        var data = writeData(encoder, sources, layout.placements());
        return new Frame(Objects.requireNonNull(atlasView), data, Collections.unmodifiableMap(slots));
    }

    private GpuBufferSlice writeData(CommandEncoder encoder, List<Source> sources,
                                     List<ForwardShadowAtlasLayout.Placement> placements) {
        long bytes = Math.max(SLOT_BYTES, Math.multiplyExact((long)sources.size(), SLOT_BYTES));
        var device = RenderSystem.getDevice();
        if (bytes > device.getDeviceInfo().limits().maxMemoryAllocationSize())
            throw new IllegalStateException("Forward point-shadow metadata exceeds the device's single-allocation limit");
        metadata = reserve(metadata, bytes);
        var target = Objects.requireNonNull(metadata);
        if (sources.isEmpty()) return target.slice(0, SLOT_BYTES);
        GpuBufferSlice staging;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(bytes, 16, GpuBuffer.USAGE_COPY_SRC)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            for (int index = 0; index < sources.size(); index++) {
                var source = sources.get(index);
                int faceSize = source.depth().size();
                for (int face = 0; face < FACE_COUNT; face++) {
                    var placement = placements.get(index * FACE_COUNT + face);
                    data.putFloat(placement.x()).putFloat(placement.y()).putFloat(faceSize).putFloat(0f);
                }
                var settings = Objects.requireNonNull(source.shadow().projectionSettings());
                data.putFloat(settings.depthBias()).putFloat(settings.slopeBias())
                        .putFloat(settings.maximumSlopeBias()).putFloat(settings.sharpen());
                data.putFloat(settings.fade()).putFloat(settings.quality()).putFloat(1f / faceSize).putFloat(0f);
                var sampling = source.shadow().matrices();
                for (int face = 0; face < FACE_COUNT; face++) matrix(data, sampling.sampling(face));
                for (int face = 0; face < FACE_COUNT; face++) matrix(data, new Matrix4f(sampling.sampling(face)).invert());
            }
            staging = mapped.slice();
        }
        var output = target.slice(0, bytes);
        encoder.copyToBuffer(staging, output);
        return output;
    }

    private static GpuBuffer reserve(@Nullable GpuBuffer previous, long bytes) {
        if (previous != null && previous.size() >= bytes) return previous;
        var device = RenderSystem.getDevice();
        var replacement = device.createBuffer(() -> "LumenFX forward point-shadow metadata",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, bytes);
        if (previous != null) previous.close();
        return replacement;
    }

    private static void matrix(ByteBuffer destination, Matrix4fc matrix) {
        int offset = destination.position();
        matrix.get(offset, destination);
        destination.position(offset + 16 * Float.BYTES);
    }

    private GpuTextureView fallback(CommandEncoder encoder) {
        if (fallbackView != null) return fallbackView;
        var device = RenderSystem.getDevice();
        var texture = device.createTexture("LumenFX forward no point-shadow depth",
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
        long bytes = Math.multiplyExact(Math.multiplyExact((long)width, height), GpuFormat.D32_FLOAT.blockSize());
        if (bytes > device.getDeviceInfo().limits().maxMemoryAllocationSize())
            throw new IllegalStateException("Forward point-shadow atlas exceeds the device's single-allocation limit");
        var next = device.createTexture("LumenFX forward point-shadow atlas",
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
        if (metadata != null) { metadata.close(); metadata = null; }
        atlasWidth = atlasHeight = 0;
    }
}
