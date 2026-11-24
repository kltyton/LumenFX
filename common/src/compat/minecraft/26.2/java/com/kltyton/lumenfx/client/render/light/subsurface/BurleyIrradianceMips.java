package com.kltyton.lumenfx.client.render.light.subsurface;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Classifies 8×8 AFIS tiles and builds the native six-level irradiance chain when enough tiles are present. */
final class BurleyIrradianceMips implements AutoCloseable {
    private static final GpuFormat IRRADIANCE = GpuFormat.RGBA16_FLOAT;
    private static final GpuFormat COUNT = GpuFormat.R32_FLOAT;
    private static final String CONTROLS = "LfxBurleyMipSettings";
    private static final RenderPipeline CLASSIFY = pipeline("afis_tile_count", COUNT,
            BindGroupLayout.builder().withSampler("LfxSubsurfaceSelection").withSampler("LfxSubsurfaceProfiles"));
    private static final RenderPipeline REDUCE = pipeline("sum_tile_count", COUNT, BindGroupLayout.builder().withSampler("InputCount"));
    private static final RenderPipeline GENERATE = pipeline("irradiance_mip", IRRADIANCE, BindGroupLayout.builder()
            .withSampler("ParentIrradiance").withSampler("AfisTileCount").withUniform(CONTROLS, UniformType.UNIFORM_BUFFER));

    record Frame(GpuTextureView irradiance, GpuTextureView count, GpuBufferSlice controls) { }
    private record Storage(GpuTexture texture, GpuTextureView full, List<GpuTextureView> levels) implements AutoCloseable {
        @Override public void close() {
            try {
                try { levels.forEach(GpuTextureView::close); }
                finally { full.close(); }
            } finally { texture.close(); }
        }
    }

    private final List<OwnedColorTarget> countTargets = new ArrayList<>();
    private @Nullable Storage storage;

    @Nullable Frame build(CommandEncoder encoder, GpuTextureView irradiance, GpuTextureView selection, GpuTextureView profiles, int threshold) {
        int width = irradiance.getWidth(0), height = irradiance.getHeight(0);
        int tilesX = Math.ceilDiv(width, 8), tilesY = Math.ceilDiv(height, 8);
        int levels = Math.min(6, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(width, height)));
        if (levels < 2 || (long) tilesX * tilesY < threshold) return null;
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var count = countTarget(0, tilesX, tilesY).getColorTextureView();
        try (var pass = encoder.createRenderPass(() -> "LumenFX AFIS tile classification", count, Optional.empty())) {
            pass.setPipeline(CLASSIFY);
            pass.bindTexture("LfxSubsurfaceSelection", selection, nearest);
            pass.bindTexture("LfxSubsurfaceProfiles", profiles, nearest);
            pass.draw(3, 1, 0, 0);
        }
        int index = 1;
        while (tilesX > 1 || tilesY > 1) {
            tilesX = Math.ceilDiv(tilesX, 2);
            tilesY = Math.ceilDiv(tilesY, 2);
            var reduced = countTarget(index++, tilesX, tilesY).getColorTextureView();
            try (var pass = encoder.createRenderPass(() -> "LumenFX AFIS tile count reduction", reduced, Optional.empty())) {
                pass.setPipeline(REDUCE);
                pass.bindTexture("InputCount", count, nearest);
                pass.draw(3, 1, 0, 0);
            }
            count = reduced;
        }

        var pyramid = storage(width, height, levels);
        encoder.copyTextureToTexture(irradiance.texture(), pyramid.texture(), 0, 0, 0, 0, 0, width, height);
        GpuBufferSlice controls;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(threshold).putInt(levels - 1).putInt(0).putInt(0);
            controls = mapped.slice();
        }
        for (int level = 1; level < levels; ++level) {
            try (var pass = encoder.createRenderPass(() -> "LumenFX Burley irradiance mip", pyramid.levels().get(level), Optional.empty())) {
                pass.setPipeline(GENERATE);
                pass.setUniform(CONTROLS, controls);
                pass.bindTexture("ParentIrradiance", pyramid.levels().get(level - 1), linear);
                pass.bindTexture("AfisTileCount", count, nearest);
                pass.draw(3, 1, 0, 0);
            }
        }
        return new Frame(pyramid.full(), count, controls);
    }

    private OwnedColorTarget countTarget(int index, int width, int height) {
        var previous = index < countTargets.size() ? countTargets.get(index) : null;
        if (previous != null && previous.width == width && previous.height == height) return previous;
        var next = new OwnedColorTarget("LumenFX AFIS tile count " + index, width, height, COUNT);
        if (previous == null) countTargets.add(next);
        else {
            countTargets.set(index, next);
            previous.close();
        }
        return next;
    }

    private Storage storage(int width, int height, int levels) {
        var previous = storage;
        if (previous != null && previous.full().getWidth(0) == width && previous.full().getHeight(0) == height) return previous;
        var device = RenderSystem.getDevice();
        var texture = device.createTexture("LumenFX Burley irradiance pyramid",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
                IRRADIANCE, width, height, 1, levels);
        GpuTextureView full = null;
        var views = new ArrayList<GpuTextureView>(levels);
        try {
            full = device.createTextureView(texture);
            for (int level = 0; level < levels; ++level) views.add(device.createTextureView(texture, level, 1));
            storage = new Storage(texture, full, List.copyOf(views));
        } catch (RuntimeException | Error failure) {
            try {
                try { views.forEach(GpuTextureView::close); }
                finally { if (full != null) full.close(); }
            } finally { texture.close(); }
            throw failure;
        }
        if (previous != null) previous.close();
        return storage;
    }

    private static RenderPipeline pipeline(String name, GpuFormat format, BindGroupLayout.Builder bindings) {
        return RenderPipeline.builder().withLocation(LumenFx.id("pipeline/subsurface/" + name))
                .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/subsurface/" + name))
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    }

    @Override public void close() {
        countTargets.forEach(OwnedColorTarget::close);
        countTargets.clear();
        var previous = storage;
        storage = null;
        if (previous != null) previous.close();
    }
}
