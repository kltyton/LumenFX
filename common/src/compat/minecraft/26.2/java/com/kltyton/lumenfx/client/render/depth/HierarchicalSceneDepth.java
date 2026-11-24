package com.kltyton.lumenfx.client.render.depth;

import com.kltyton.lumenfx.LumenFx;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Furthest device-depth hierarchy for the current native view. */
public final class HierarchicalSceneDepth implements AutoCloseable {
    private static final RenderPipeline REDUCE = pipeline("furthest_scene_depth", GpuFormat.R16_FLOAT);
    private static final RenderPipeline REDUCE_FULL = pipeline("furthest_scene_depth_full", GpuFormat.R32_FLOAT);
    private static RenderPipeline pipeline(String name, GpuFormat format) {
        return RenderPipeline.builder()
            .withLocation(LumenFx.id("pipeline/" + name))
            .withVertexShader(LumenFx.id("post/fullscreen"))
            .withFragmentShader(LumenFx.id("post/depth/furthest_scene_depth"))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("ParentDepth").build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
            .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    }

    public record View(GpuTextureView depth, float viewportU, float viewportV) { }

    private record Storage(GpuTexture texture, GpuTextureView full, List<GpuTextureView> mips) implements AutoCloseable {
        @Override public void close() {
            try {
                try { mips.forEach(GpuTextureView::close); }
                finally { full.close(); }
            } finally { texture.close(); }
        }
    }

    private final boolean fullPrecision;
    private @Nullable Storage storage;

    public HierarchicalSceneDepth() { this(false); }
    private HierarchicalSceneDepth(boolean fullPrecision) { this.fullPrecision = fullPrecision; }
    public static HierarchicalSceneDepth fullPrecision() { return new HierarchicalSceneDepth(true); }

    public View buildFurthest(CommandEncoder encoder, GpuTextureView source) {
        RenderSystem.assertOnRenderThread();
        int width = source.getWidth(0), height = source.getHeight(0);
        int baseWidth = Integer.highestOneBit(Math.max(1, width - 1));
        int baseHeight = Integer.highestOneBit(Math.max(1, height - 1));
        var output = storage(baseWidth, baseHeight, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(baseWidth, baseHeight)));
        reduce(encoder, output, source, 0);
        return new View(output.full(), width / (2f * baseWidth), height / (2f * baseHeight));
    }

    public View buildFurthestFromBase(CommandEncoder encoder, int width, int height, Consumer<GpuTextureView> writeBase) {
        RenderSystem.assertOnRenderThread();
        int baseWidth = width == 1 ? 1 : Integer.highestOneBit(width - 1) << 1;
        int baseHeight = height == 1 ? 1 : Integer.highestOneBit(height - 1) << 1;
        int levels = Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(baseWidth, baseHeight)));
        var output = storage(baseWidth, baseHeight, levels);
        var base = output.mips().getFirst();
        writeBase.accept(base);
        reduce(encoder, output, base, 1);
        return new View(output.full(), (float)width / baseWidth, (float)height / baseHeight);
    }

    private void reduce(CommandEncoder encoder, Storage output, GpuTextureView source, int firstMip) {
        var point = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var previous = source;
        for (int i = firstMip; i < output.mips().size(); i++) {
            var mip = output.mips().get(i);
            try (var pass = encoder.createRenderPass(() -> "LumenFX furthest scene depth mip", mip, Optional.empty())) {
                pass.setPipeline(fullPrecision ? REDUCE_FULL : REDUCE);
                pass.bindTexture("ParentDepth", previous, point);
                pass.draw(3, 1, 0, 0);
            }
            previous = mip;
        }
    }

    private Storage storage(int width, int height, int levels) {
        var old = storage;
        if (old != null && old.full().getWidth(0) == width && old.full().getHeight(0) == height
                && old.mips().size() == levels) return old;
        var device = RenderSystem.getDevice();
        var texture = device.createTexture("LumenFX furthest scene depth", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                fullPrecision ? GpuFormat.R32_FLOAT : GpuFormat.R16_FLOAT, width, height, 1, levels);
        GpuTextureView full = null;
        var mips = new ArrayList<GpuTextureView>(levels);
        try {
            full = device.createTextureView(texture);
            for (int level = 0; level < levels; level++) mips.add(device.createTextureView(texture, level, 1));
            storage = new Storage(texture, full, List.copyOf(mips));
        } catch (RuntimeException | Error failure) {
            try {
                try { mips.forEach(GpuTextureView::close); }
                finally { if (full != null) full.close(); }
            } finally { texture.close(); }
            throw failure;
        }
        if (old != null) old.close();
        return storage;
    }

    @Override public void close() {
        var old = storage;
        storage = null;
        if (old != null) old.close();
    }
}
