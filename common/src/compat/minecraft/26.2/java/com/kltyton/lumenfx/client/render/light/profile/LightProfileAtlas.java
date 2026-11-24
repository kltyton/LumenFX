package com.kltyton.lumenfx.client.render.light.profile;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerViews;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** One mip of source-filtered IES data in a sampled R16F array. */
public final class LightProfileAtlas implements AutoCloseable {
    private static final GpuFormat FORMAT = GpuFormat.R16_FLOAT;
    private static final RenderPipeline UPLOAD = RenderPipeline.builder()
            .withLocation(LumenFx.id("pipeline/light/profile_atlas"))
            .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("lighting/profile_atlas"))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("ProfileSource").build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), FORMAT, ColorTargetState.WRITE_ALL))
            .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();

    public record Frame(GpuTextureView texture, Map<Identifier, Integer> slots) {
        public int index(Identifier id) { return id == null ? -1 : slots.getOrDefault(id, -1); }
    }

    private record Allocation(ComputeTexture image, GpuTextureView sampled, List<GpuTextureView> slices) implements AutoCloseable {
        @Override public void close() {
            try { slices.forEach(GpuTextureView::close); }
            finally {
                try { sampled.close(); }
                finally { image.close(); }
            }
        }
    }

    private Allocation allocation;
    private List<GpuTextureView> uploaded = List.of();

    public Frame prepare(CommandEncoder encoder, NiagaraPlatformEnvironment platform, List<ScenePointLight> visible) {
        RenderSystem.assertOnRenderThread();
        int maximum = Math.max(0, setting(platform, "r.IESAtlas.MaxProfileCount"));
        var slots = new LinkedHashMap<Identifier, Integer>();
        var views = new ArrayList<GpuTextureView>();
        if (setting(platform, "r.Translucent.UsesIESProfiles") > 0) for (var light : visible) {
            if (slots.size() == maximum) break;
            Identifier id = light.iesTexture();
            if (id == null || !light.data().shader().affectsTranslucency() || slots.containsKey(id)) continue;
            var profile = Objects.requireNonNull(EffectTextures.get(id), "IES profile was not prepared: " + id);
            slots.put(id, slots.size());
            views.add(profile.view());
        }
        int resolution = views.isEmpty() ? 1 : Math.max(1, setting(platform, "r.IESAtlas.Resolution"));
        int layers = Math.max(1, views.size());
        if (allocation == null || allocation.image().width() != resolution || allocation.image().layers() < layers) {
            var replacement = allocate(resolution, layers);
            if (allocation != null) allocation.close();
            allocation = replacement;
            uploaded = List.of();
        }
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        boolean force = setting(platform, "r.IESAtlas.ForceUpdate") != 0;
        for (int index = 0; index < views.size(); index++) {
            var source = views.get(index);
            if (!force && index < uploaded.size() && uploaded.get(index) == source) continue;
            var destination = allocation.slices().get(index);
            var descriptor = RenderPassDescriptor.create(() -> "LumenFX IES profile upload")
                    .withColorAttachment(destination)
                    .withRenderArea(new RenderPass.RenderArea(0, 0, destination.getWidth(0), destination.getHeight(0)));
            try (var pass = encoder.createRenderPass(descriptor)) {
                pass.setPipeline(UPLOAD);
                pass.bindTexture("ProfileSource", source, sampler);
                pass.draw(3, 1, 0, 0);
            }
        }
        uploaded = List.copyOf(views);
        return new Frame(allocation.sampled(), Map.copyOf(slots));
    }

    private static int setting(NiagaraPlatformEnvironment platform, String name) {
        return Objects.requireNonNull(platform.console(name), name).bits();
    }

    private static Allocation allocate(int resolution, int layers) {
        var device = RenderSystem.getDevice();
        try (var runtime = ComputeRuntime.create(device)) {
            if (resolution > device.getDeviceInfo().limits().maxTextureSizeForFormat(FORMAT)
                    || layers > runtime.maxTextureArrayLayers())
                throw new IllegalArgumentException("Source IES atlas dimensions exceed device limits");
            var image = runtime.createColorTargetArray("LumenFX IES profiles", FORMAT, resolution, resolution, layers, false, false);
            GpuTextureView sampled = null;
            var slices = new ArrayList<GpuTextureView>(layers);
            try {
                sampled = TextureLayerViews.createArray(device, image);
                for (int index = 0; index < layers; index++)
                    slices.add(TextureLayerViews.createLayer(device, image.texture(), index, 0));
                return new Allocation(image, sampled, List.copyOf(slices));
            } catch (RuntimeException | Error failure) {
                slices.forEach(GpuTextureView::close);
                if (sampled != null) sampled.close();
                image.close();
                throw failure;
            }
        }
    }

    @Override public void close() {
        if (allocation != null) { allocation.close(); allocation = null; }
        uploaded = List.of();
    }
}
