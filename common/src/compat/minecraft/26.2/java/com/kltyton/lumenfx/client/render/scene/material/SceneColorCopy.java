package com.kltyton.lumenfx.client.render.scene.material;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.texture.NativeColorFormats;
import com.kltyton.lumenfx.client.render.texture.NativeColorTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Encoded scene snapshot shared by material lookups throughout the transparency passes. */
public final class SceneColorCopy implements AutoCloseable {
    private @Nullable NativeColorTarget target;
    private @Nullable RenderPipeline linearPipeline, srgbPipeline;
    private int width, height;

    public void capture(CommandEncoder encoder, RenderTarget source, boolean srgb) {
        RenderSystem.assertOnRenderThread();
        if (target == null || width != source.width || height != source.height) {
            var next = new NativeColorTarget("LumenFX material scene color", source.width, source.height, false);
            if (target != null) target.close();
            target = next;
            width = source.width;
            height = source.height;
        }
        RenderPipeline pipeline = srgb ? srgbPipeline : linearPipeline;
        if (pipeline == null) {
            pipeline = RenderPipeline.builder()
                    .withLocation(LumenFx.id("pipeline/material_scene_color/" + (srgb ? "srgb" : "linear")))
                    .withVertexShader(LumenFx.id("post/fullscreen"))
                    .withFragmentShader(LumenFx.id("post/scene_color_copy"))
                    .withShaderDefine("LFX_SCENE_COLOR_SOURCE_SRGB", srgb ? 1 : 0)
                    .withBindGroupLayout(BindGroupLayout.builder().withSampler("SceneSampler").build())
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .withDepthStencilState(Optional.empty()).withCull(false)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
            NativeColorFormats.register(pipeline, target.storage());
            if (srgb) srgbPipeline = pipeline;
            else linearPipeline = pipeline;
        }
        try (var pass = encoder.createRenderPass(() -> "LumenFX material scene color copy", target.view(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("SceneSampler", source.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    public GpuTextureView view() { return Objects.requireNonNull(target, "Material scene color has not been captured").view(); }

    @Override public void close() {
        if (target != null) { target.close(); target = null; }
        linearPipeline = srgbPipeline = null;
        width = height = 0;
    }
}
