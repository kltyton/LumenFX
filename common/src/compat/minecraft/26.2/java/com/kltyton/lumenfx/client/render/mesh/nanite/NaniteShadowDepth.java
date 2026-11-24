package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowMatrices;
import com.kltyton.lumenfx.client.render.light.shadow.ShadowRasterViews;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowMatrices;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Optional;

/** Exports covered Nanite depths in the receiver's cube, spot or directional depth domain. */
final class NaniteShadowDepth {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(LumenFx.id("pipeline/nanite/shadow_depth"))
            .withVertexShader(LumenFx.id("post/fullscreen"))
            .withFragmentShader(LumenFx.id("post/nanite/shadow_depth"))
            .withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, 0))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("NaniteVisibleDepth")
                    .withUniform("NaniteShadowProjection", UniformType.UNIFORM_BUFFER).build()).build();

    private NaniteShadowDepth() { }

    static void resolve(CommandEncoder encoder, ShadowDepthTarget target, ShadowRasterViews matrices,
                        int face, GpuTextureView source) {
        var descriptor = target.descriptor(face, () -> "LumenFX Nanite shadow depth");
        float viewToClip22 = 0, bias = 0;
        int mode = 0, x = 0, y = 0;
        if (matrices instanceof DirectionalShadowMatrices directional) {
            var tile = directional.outerTile(face);
            descriptor.withRenderArea(tile);
            bias = directional.casterDepthBias(face);
            mode = 1;
            x = tile.x();
            y = tile.y();
        } else if (matrices instanceof PointShadowMatrices point && point.spot()) {
            viewToClip22 = point.deviceDepthAdd();
            bias = point.spotSettings().casterBias();
            mode = 2;
        }
        draw(encoder, descriptor, source, viewToClip22, bias, mode, x, y);
    }

    static void accumulate(CommandEncoder encoder, ShadowDepthTarget raw, GpuTextureView source) {
        accumulate(encoder, raw.descriptor(0, () -> "LumenFX Nanite raw shadow visibility"), source);
    }

    static void accumulate(CommandEncoder encoder, RenderPassDescriptor target, GpuTextureView source) {
        draw(encoder, target, source, 0, 0, 0, 0, 0);
    }

    private static void draw(CommandEncoder encoder, RenderPassDescriptor descriptor, GpuTextureView source,
                             float viewToClip22, float bias, int mode, int x, int y) {
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice parameters;
        try (var projection = encoder.transientMemory().allocateGpuMapped(32, alignment, GpuBuffer.USAGE_UNIFORM)) {
            projection.data().order(ByteOrder.nativeOrder()).putFloat(viewToClip22).putFloat(bias).putFloat(0f).putFloat(0f)
                    .putInt(x).putInt(y).putInt(mode).putInt(0);
            parameters = projection.slice();
        }
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("NaniteShadowProjection", parameters);
            pass.bindTexture("NaniteVisibleDepth", source, sampler);
            pass.draw(3, 1, 0, 0);
        }
    }
}
