package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.depth.HierarchicalSceneDepth;
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
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Builds a current-view furthest HZB from the coarse Nanite depth pass. */
final class NanitePrimedHzb implements AutoCloseable {
    private static final RenderPipeline BASE = RenderPipeline.builder()
            .withLocation(LumenFx.id("pipeline/nanite/primed_hzb"))
            .withVertexShader(LumenFx.id("post/fullscreen"))
            .withFragmentShader(LumenFx.id("post/nanite/primed_hzb"))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("CoarseDepth").withSampler("SceneDepth")
                    .withUniform("NanitePrimeProjection", UniformType.UNIFORM_BUFFER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R16_FLOAT, ColorTargetState.WRITE_ALL))
            .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();

    private record Target(GpuTexture depth, GpuTextureView view, OwnedColorTarget color) implements AutoCloseable {
        @Override public void close() {
            try { view.close(); }
            finally {
                try { depth.close(); }
                finally { color.close(); }
            }
        }
    }

    private final HierarchicalSceneDepth hierarchy = new HierarchicalSceneDepth();
    private @Nullable Target coarse;

    GpuTextureView begin(CommandEncoder encoder, int width, int height) {
        var target = coarse;
        if (target == null || target.view().getWidth(0) != width || target.view().getHeight(0) != height) {
            var device = RenderSystem.getDevice();
            var depth = device.createTexture("LumenFX Nanite prime depth", GpuTexture.USAGE_RENDER_ATTACHMENT
                    | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, width, height, 1, 1);
            GpuTextureView view = null;
            try {
                view = device.createTextureView(depth);
                coarse = new Target(depth, view, new OwnedColorTarget("LumenFX Nanite prime raster", width, height, GpuFormat.R8_UNORM));
            } catch (RuntimeException | Error failure) {
                if (view != null) view.close();
                depth.close();
                throw failure;
            }
            if (target != null) target.close();
            target = coarse;
        }
        encoder.clearDepthTexture(target.depth(), 0);
        return target.view();
    }

    void accumulate(CommandEncoder encoder, GpuTextureView depth) {
        var target = Objects.requireNonNull(coarse);
        var color = target.color();
        NaniteShadowDepth.accumulate(encoder, RenderPassDescriptor.create(() -> "LumenFX Nanite prime depth accumulation")
                .withColorAttachment(Objects.requireNonNull(color.getColorTextureView())).withDepthAttachment(target.view())
                .withRenderArea(new RenderPass.RenderArea(0, 0, color.width, color.height)), depth);
    }

    HierarchicalSceneDepth.View finish(CommandEncoder encoder, GpuTextureView sceneDepth, Matrix4fc projection,
                                       boolean zeroToOne, boolean topDown, boolean sampleScene, float biasCm) {
        var source = Objects.requireNonNull(coarse).view();
        var point = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice uniform;
        try (var block = encoder.transientMemory().allocateGpuMapped(32, alignment, GpuBuffer.USAGE_UNIFORM)) {
            block.data().order(ByteOrder.nativeOrder()).putFloat(projection.m22()).putFloat(projection.m32())
                    .putFloat(projection.m23()).putFloat(projection.m33())
                    .putFloat(biasCm * 0.01f).putFloat(zeroToOne ? 1 : 0).putFloat(sampleScene ? 1 : 0)
                    .putFloat(topDown ? 1 : 0);
            uniform = block.slice();
        }
        return hierarchy.buildFurthestFromBase(encoder, source.getWidth(0), source.getHeight(0), base -> {
            try (var pass = encoder.createRenderPass(() -> "LumenFX Nanite primed HZB base", base, Optional.empty())) {
                pass.setPipeline(BASE);
                pass.setUniform("NanitePrimeProjection", uniform);
                pass.bindTexture("CoarseDepth", source, point);
                pass.bindTexture("SceneDepth", sampleScene ? sceneDepth : source, point);
                pass.draw(3, 1, 0, 0);
            }
        });
    }

    @Override public void close() {
        var target = coarse;
        coarse = null;
        try { hierarchy.close(); }
        finally { if (target != null) target.close(); }
    }
}
