package com.kltyton.lumenfx.client.render.texture.virtual;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureMaterialPass.Raster;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer.Format;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer.Request;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/** Copies the blended material channels into their final uncompressed page storage. */
final class RuntimeVirtualTextureCopies {
    private record Key(Material material, int layer, GpuFormat format) { }
    private final Map<Key, RenderPipeline> pipelines = new HashMap<>();

    Raster pack(CommandEncoder encoder, Request request, List<GpuTextureView> inputs) {
        var material = request.definition().material();
        if (inputs.size() != RuntimeVirtualTextureWriterLayout.rasterLayers(material))
            throw new IllegalArgumentException("RVT source MRT count does not match " + material);
        if (request.layers().getFirst().format() == Format.G16) return new Raster(inputs, () -> { });
        var views = new ArrayList<GpuTextureView>(request.layers().size());
        Runnable release = () -> views.forEach(view -> {
            var texture = view.texture();
            try { view.close(); } finally { texture.close(); }
        });
        var device = RenderSystem.getDevice();
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        int size = request.view().pixelSize();
        boolean srgb = device.backend instanceof GlDevice && GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB);
        if (srgb) GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
        try {
            for (int layer = 0; layer < request.layers().size(); layer++) {
                GpuFormat format = request.layers().get(layer).format() == Format.RGB565 ? GpuFormat.R16_UINT : GpuFormat.RGBA8_UNORM;
                var target = device.createTexture("LumenFX RVT final material layer",
                        GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC, format, size, size, 1, 1);
                GpuTextureView view;
                try { view = device.createTextureView(target); }
                catch (RuntimeException | Error failure) { target.close(); throw failure; }
                views.add(view);
                var pipeline = pipelines.computeIfAbsent(new Key(material, layer, format), RuntimeVirtualTextureCopies::pipeline);
                try (var pass = encoder.createRenderPass(() -> "LumenFX RVT copy material channels", view, Optional.empty())) {
                    pass.setPipeline(pipeline);
                    for (int index = 0; index < inputs.size(); index++)
                        pass.bindTexture("RvtRaster" + index, inputs.get(index), sampler);
                    pass.draw(3, 1, 0, 0);
                }
            }
            return new Raster(views, release);
        } catch (RuntimeException | Error failure) {
            RenderSystem.queueFencedTask(release);
            throw failure;
        } finally {
            if (srgb) GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB);
        }
    }

    private static RenderPipeline pipeline(Key key) {
        var bindings = BindGroupLayout.builder();
        int layers = RuntimeVirtualTextureWriterLayout.rasterLayers(key.material());
        for (int layer = 0; layer < layers; layer++) bindings.withSampler("RvtRaster" + layer);
        return RenderPipeline.builder().withLocation(LumenFx.id("pipeline/rvt/material_copy_" + key.material().ordinal()
                        + "_" + key.layer() + "_" + (key.format() == GpuFormat.R16_UINT ? "565" : "rgba")))
                .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/rvt_material_copy"))
                .withShaderDefine("LFX_RVT_LAYOUT", key.material().ordinal()).withShaderDefine("LFX_RVT_COPY_LAYER", key.layer())
                .withShaderDefine("LFX_RVT_RASTER_LAYERS", layers).withShaderDefine("LFX_RVT_RGB565", key.format() == GpuFormat.R16_UINT ? 1 : 0)
                .withBindGroupLayout(bindings.build()).withCull(false).withDepthStencilState(Optional.empty())
                .withColorTargetState(new ColorTargetState(Optional.empty(), key.format(), ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    }

    void clear() { pipelines.clear(); }
}
