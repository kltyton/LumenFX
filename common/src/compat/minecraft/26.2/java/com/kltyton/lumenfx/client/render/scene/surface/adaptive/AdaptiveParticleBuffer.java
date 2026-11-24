package com.kltyton.lumenfx.client.render.scene.surface.adaptive;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerViews;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Per-pixel packed closure words. Color-attachment writes obey the same late depth test as scene color. */
public final class AdaptiveParticleBuffer implements AutoCloseable {
    public static final GpuFormat FORMAT = GpuFormat.RGBA32_UINT;
    public static final String SAMPLER = "LfxAdaptiveParticleWords";
    public static final int WORDS_PER_LAYER = 4;
    private static final RenderPipeline CLEAR = RenderPipeline.builder()
            .withLocation(LumenFx.id("pipeline/adaptive_substrate_clear"))
            .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/adaptive_substrate_clear"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(new ColorTargetState(Optional.empty(), FORMAT, ColorTargetState.WRITE_ALL)).build();

    public record Replay(int firstLayer, List<GpuTextureView> layers, GpuTextureView depth) {
        public RenderPassDescriptor descriptor() {
            var result = RenderPassDescriptor.create(() -> "LumenFX adaptive material words");
            for (var layer : layers) result.withColorAttachment(layer);
            return result.withDepthAttachment(depth)
                    .withRenderArea(new RenderPass.RenderArea(0, 0, depth.getWidth(0), depth.getHeight(0)));
        }
    }

    public record Frame(GpuTextureView sampled, List<Replay> replays) { }

    private record Allocation(ComputeTexture words, GpuTextureView sampled, List<GpuTextureView> layers,
                              GpuTexture depth, GpuTextureView depthView) implements AutoCloseable {
        boolean matches(GpuTexture sceneDepth, int layers) {
            return words.width() == sceneDepth.getWidth(0) && words.height() == sceneDepth.getHeight(0)
                    && words.layers() >= layers && depth.getFormat() == sceneDepth.getFormat();
        }

        @Override public void close() {
            depthView.close();
            depth.close();
            layers.forEach(GpuTextureView::close);
            sampled.close();
            words.close();
        }
    }

    private @Nullable Allocation allocation;

    public Frame begin(CommandEncoder encoder, RenderTarget scene, int wordCount) {
        var sceneDepth = Objects.requireNonNull(scene.getDepthTexture(), "Adaptive materials require the opaque depth target");
        int layers = Math.ceilDiv(wordCount, WORDS_PER_LAYER);
        if (allocation == null || !allocation.matches(sceneDepth, layers)) {
            Allocation next = create(sceneDepth, layers);
            if (allocation != null) allocation.close();
            allocation = next;
        }
        var storage = Objects.requireNonNull(allocation);
        var header = storage.layers().getFirst();
        try (var pass = encoder.createRenderPass(RenderPassDescriptor.create(() -> "LumenFX adaptive material header")
                .withColorAttachment(header)
                .withRenderArea(new RenderPass.RenderArea(0, 0, header.getWidth(0), header.getHeight(0))))) {
            pass.setPipeline(CLEAR);
            pass.draw(3, 1, 0, 0);
        }
        int limit = RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments();
        List<Replay> replays = new ArrayList<>(Math.ceilDiv(layers, limit));
        for (int layer = 0; layer < layers; layer += limit) {
            replays.add(new Replay(layer, storage.layers().subList(layer, Math.min(layers, layer + limit)), storage.depthView()));
        }
        return new Frame(storage.sampled(), List.copyOf(replays));
    }

    public static void copyDepth(CommandEncoder encoder, RenderTarget scene, Replay replay) {
        com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.INSTANCE.copyDepth(
                encoder, Objects.requireNonNull(scene.getDepthTexture()), replay.depth().texture());
    }

    private static Allocation create(GpuTexture sceneDepth, int layers) {
        var device = RenderSystem.getDevice();
        int width = sceneDepth.getWidth(0), height = sceneDepth.getHeight(0);
        try (var runtime = ComputeRuntime.create(device)) {
            var words = runtime.createColorTargetArray("LumenFX adaptive material words", FORMAT, width, height, layers, false, false);
            GpuTextureView sampled = null, depthView = null;
            GpuTexture depth = null;
            List<GpuTextureView> views = new ArrayList<>(layers);
            try {
                sampled = TextureLayerViews.createArray(device, words);
                for (int layer = 0; layer < layers; layer++) views.add(TextureLayerViews.createLayer(device, words.texture(), layer, 0));
                depth = device.createTexture("LumenFX adaptive material replay depth",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT,
                        sceneDepth.getFormat(), width, height, 1, 1);
                depthView = device.createTextureView(depth);
                return new Allocation(words, sampled, List.copyOf(views), depth, depthView);
            } catch (RuntimeException | Error failure) {
                if (depthView != null) depthView.close();
                if (depth != null) depth.close();
                views.forEach(GpuTextureView::close);
                if (sampled != null) sampled.close();
                words.close();
                throw failure;
            }
        }
    }

    @Override public void close() {
        if (allocation != null) {
            allocation.close();
            allocation = null;
        }
    }
}
