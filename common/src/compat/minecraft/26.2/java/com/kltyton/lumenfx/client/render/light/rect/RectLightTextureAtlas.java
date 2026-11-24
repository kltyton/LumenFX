package com.kltyton.lumenfx.client.render.light.rect;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Prefiltered emitter radiance with isolated, mip-aligned slots for forward Rect lights. */
public final class RectLightTextureAtlas implements AutoCloseable {
    public record Slot(float scaleX, float scaleY, float offsetX, float offsetY, int maxMip) {
        public void write(ByteBuffer data) {
            data.putFloat(scaleX).putFloat(scaleY).putFloat(offsetX).putFloat(offsetY);
        }
    }

    public record Frame(GpuTextureView texture, Map<ScenePointLight, Slot> slots) { }

    private record Source(GpuTextureView view, float scaleX, float scaleY, float offsetX, float offsetY) {
        RectLightAtlasLayout.Extent extent(int ratio) {
            float width = view.getWidth(0) * scaleX;
            float height = view.getHeight(0) * scaleY;
            return new RectLightAtlasLayout.Extent(Math.max(1, (int)Math.max(width, height / ratio)),
                    Math.max(1, (int)Math.max(height, width / ratio)));
        }
    }

    private record Storage(GpuTexture image, GpuTextureView sampled, List<GpuTextureView> mips) implements AutoCloseable {
        @Override public void close() {
            try {
                try { mips.forEach(GpuTextureView::close); }
                finally { sampled.close(); }
            } finally { image.close(); }
        }
    }

    private record Pipelines(RenderPipeline upload, RenderPipeline box, RenderPipeline gaussian) { }
    private final Map<GpuFormat, Pipelines> pipelines = new EnumMap<>(GpuFormat.class);
    private @Nullable Storage storage;
    private RectLightAtlasLayout<Source> uploaded = new RectLightAtlasLayout<>(1, 0, Map.of());
    private int uploadedQuality;

    public Frame prepare(CommandEncoder encoder, NiagaraPlatformEnvironment platform, List<ScenePointLight> visible,
                         GpuTextureView fallback) {
        RenderSystem.assertOnRenderThread();
        var references = new IdentityHashMap<ScenePointLight, Source>();
        var sources = new LinkedHashMap<Source, RectLightAtlasLayout.Extent>();
        var renderTargets = new HashSet<Source>();
        int ratio = Math.clamp(setting(platform, "r.RectLightAtlas.MaxTextureRatio"), 1, 16);
        for (var light : visible) {
            var shader = light.data().shader();
            var rect = shader.rect();
            if (!shader.affectsTranslucency() || rect == null || rect.texture() == null) continue;
            var view = EffectTextures.snapshot(rect.texture()).view();
            if (view == null || view.texture().getDepthOrLayers() != 1
                    || (view.texture().usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0)
                throw new IllegalArgumentException("Rect light source requires a 2D image: " + rect.texture());
            var source = new Source(view, rect.scaleX(), rect.scaleY(), rect.offsetX(), rect.offsetY());
            references.put(light, source);
            sources.computeIfAbsent(source, key -> key.extent(ratio));
            if (EffectTextures.target(rect.texture()) != null) renderTargets.add(source);
        }
        if (sources.isEmpty()) {
            close();
            return new Frame(fallback, Map.of());
        }

        var device = RenderSystem.getDevice();
        var format = setting(platform, "r.RectLightAtlas.Format") > 0 ? GpuFormat.RGBA16_FLOAT : GpuFormat.RG11B10_FLOAT;
        int maximum = Math.min(Math.max(1, setting(platform, "r.RectLightAtlas.MaxResolution")),
                device.getDeviceInfo().limits().maxTextureSizeForFormat(format));
        var layout = RectLightAtlasLayout.create(sources, maximum);
        var output = storage(layout.size(), format);
        int quality = Math.clamp(setting(platform, "r.RectLightAtlas.FilterQuality"), 0, 1);
        var previous = uploaded;
        boolean refresh = quality != uploadedQuality || layout.sourceMip() != previous.sourceMip()
                || setting(platform, "r.RectLightAtlas.ForceUpdate") > 0
                || setting(platform, "r.RectLightAtlas.ResetOnChange") > 0 && !previous.equals(layout);
        var shaders = pipelines.computeIfAbsent(format, value -> new Pipelines(
                pipeline(value, "upload", false, false), pipeline(value, "box", true, false), pipeline(value, "gaussian", true, true)));
        uploaded = new RectLightAtlasLayout<>(1, 0, Map.of());
        for (var entry : layout.slots().entrySet()) {
            var slot = entry.getValue();
            var source = entry.getKey();
            if (!refresh && !renderTargets.contains(source) && slot.equals(previous.slots().get(source))) continue;
            int mip = Math.min(layout.sourceMip(), source.view().mipLevels() - 1);
            draw(encoder, shaders.upload(), output.mips().getFirst(), source.view(), slot, 0, source, mip);
            for (int level = 1; level <= slot.extent().maxMip(); level++)
                draw(encoder, quality == 0 ? shaders.box() : shaders.gaussian(), output.mips().get(level),
                        output.mips().get(level - 1), slot, level, source, 0);
        }
        uploaded = layout;
        uploadedQuality = quality;

        var slots = new IdentityHashMap<ScenePointLight, Slot>();
        references.forEach((light, source) -> {
            var slot = layout.slots().get(source);
            var extent = slot.extent();
            float inverseSize = 1f / layout.size();
            slots.put(light, new Slot(extent.width() * inverseSize, extent.height() * inverseSize,
                    slot.x() * inverseSize, slot.y() * inverseSize, extent.maxMip()));
        });
        return new Frame(output.sampled(), slots);
    }

    private static void draw(CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView destination, GpuTextureView input,
                             RectLightAtlasLayout.Slot slot, int level, Source source, int sourceMip) {
        var extent = slot.extent().mip(level);
        int x = slot.x() >> level, y = slot.y() >> level;
        var renderArea = new RenderPass.RenderArea(x, y, extent.width(), extent.height());
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX Rect light atlas mip")
                .withColorAttachment(destination).withRenderArea(renderArea);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice uniformSlice;
        try (var uniforms = encoder.transientMemory().allocateGpuMapped(48, alignment, GpuBuffer.USAGE_UNIFORM)) {
            uniforms.data().order(ByteOrder.nativeOrder())
                    .putInt(x).putInt(y).putInt(extent.width()).putInt(extent.height())
                    .putFloat(source.scaleX()).putFloat(source.scaleY()).putFloat(source.offsetX()).putFloat(source.offsetY())
                    .putInt(sourceMip).putInt(0).putInt(0).putInt(0);
            uniformSlice = uniforms.slice();
        }
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            pass.setUniform("RectAtlasSlot", uniformSlice);
            pass.bindTexture("RectAtlasInput", input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, level == 0));
            pass.draw(3, 1, 0, 0);
        }
    }

    private Storage storage(int size, GpuFormat format) {
        var old = storage;
        if (old != null && old.sampled().getWidth(0) == size && old.image().getFormat() == format) return old;
        var device = RenderSystem.getDevice();
        int levels = Math.max(1, Integer.numberOfTrailingZeros(size));
        var image = device.createTexture("LumenFX Rect light source atlas", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                format, size, size, 1, levels);
        GpuTextureView sampled = null;
        var mips = new ArrayList<GpuTextureView>(levels);
        try {
            sampled = device.createTextureView(image);
            for (int level = 0; level < levels; level++) mips.add(device.createTextureView(image, level, 1));
        } catch (RuntimeException | Error failure) {
            try {
                try { mips.forEach(GpuTextureView::close); }
                finally { if (sampled != null) sampled.close(); }
            } finally { image.close(); }
            throw failure;
        }
        storage = new Storage(image, sampled, List.copyOf(mips));
        uploaded = new RectLightAtlasLayout<>(1, 0, Map.of());
        if (old != null) old.close();
        return storage;
    }

    private static RenderPipeline pipeline(GpuFormat format, String mode, boolean reduce, boolean gaussian) {
        return RenderPipeline.builder().withLocation(LumenFx.id("pipeline/light/rect_atlas/" + mode + "/" + format.name().toLowerCase(Locale.ROOT)))
                .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("lighting/rect_atlas"))
                .withShaderDefine("LFX_RECT_ATLAS_REDUCE", reduce ? 1 : 0)
                .withShaderDefine("LFX_RECT_ATLAS_GAUSSIAN", gaussian ? 1 : 0)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("RectAtlasSlot", UniformType.UNIFORM_BUFFER)
                        .withSampler("RectAtlasInput").build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    }

    private static int setting(NiagaraPlatformEnvironment platform, String name) {
        return Objects.requireNonNull(platform.console(name), name).bits();
    }

    @Override public void close() {
        if (storage != null) { storage.close(); storage = null; }
        uploaded = new RectLightAtlasLayout<>(1, 0, Map.of());
    }
}
