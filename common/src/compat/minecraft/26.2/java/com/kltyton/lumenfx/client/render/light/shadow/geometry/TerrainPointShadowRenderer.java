package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.light.shadow.ShadowRasterViews;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowCaster;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/** Submits the host's section buffers to light-relative depth views without camera occlusion filtering. */
public final class TerrainPointShadowRenderer implements AutoCloseable {
    private static final AtomicLong NEXT = new AtomicLong();
    private record Variant(RenderPipeline host, boolean spot, boolean directional) { }
    private final Map<Variant, RenderPipeline> pipelines = new HashMap<>();

    public void draw(TerrainShadowScene.Frame scene, ShadowDepthTarget target, ShadowRasterViews matrices,
                     @Nullable GpuTextureView blockAtlas, @Nullable GpuSampler sampler) {
        draw(scene, target, matrices, blockAtlas, sampler, true);
    }
    public void draw(TerrainShadowScene.Frame scene, ShadowDepthTarget target, ShadowRasterViews matrices,
                     @Nullable GpuTextureView blockAtlas, @Nullable GpuSampler sampler, boolean clear) {
        draw(scene, target, matrices, blockAtlas, sampler, clear, 0, target.viewCount());
    }
    public void drawView(TerrainShadowScene.Frame scene, ShadowDepthTarget target, ShadowRasterViews matrices,
                         @Nullable GpuTextureView blockAtlas, @Nullable GpuSampler sampler, int view) {
        draw(scene, target, matrices, blockAtlas, sampler, false, view, view + 1);
    }
    private void draw(TerrainShadowScene.Frame scene, ShadowDepthTarget target, ShadowRasterViews matrices,
                      @Nullable GpuTextureView blockAtlas, @Nullable GpuSampler sampler, boolean clear, int first, int end) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice(); var encoder = device.createCommandEncoder();
        var spotCaster = SpotShadowCaster.uniform(encoder, matrices);
        boolean directional = matrices.projectionType() == ProjectionType.ORTHOGRAPHIC;
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        int faceStride = Math.multiplyExact(Math.ceilDiv(64, alignment), alignment);
        int sectionStride = Math.multiplyExact(Math.ceilDiv(16, alignment), alignment);
        GpuBufferSlice faces, sections = null;
        try (var mapped = encoder.transientMemory().allocateGpuMapped((long) faceStride * (end - first), alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            for (int face = first; face < end; face++) matrices.raster(face).get((face - first) * faceStride, data);
            faces = mapped.slice();
        }
        if (!scene.entries().isEmpty()) {
            try (var mapped = encoder.transientMemory().allocateGpuMapped(Math.multiplyExact(sectionStride, scene.entries().size()), alignment, GpuBuffer.USAGE_UNIFORM)) {
                var data = mapped.data().order(ByteOrder.nativeOrder());
                for (int i = 0; i < scene.entries().size(); i++) {
                    var offset = scene.entries().get(i).sourceOffset(); data.position(i * sectionStride);
                    data.putFloat((float)offset.x).putFloat((float)offset.y).putFloat((float)offset.z).putFloat(0);
                }
                sections = mapped.slice();
            }
        }
        var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer defaultIndices = scene.maximumSequentialIndices() == 0 ? null : sequential.getBuffer(scene.maximumSequentialIndices());
        for (int face = first; face < end; face++) {
            int index = face;
            var directionalCaster = DirectionalShadowCaster.uniform(encoder, matrices, face);
            var descriptor = clear ? target.descriptor(face, 0) : target.descriptor(face, () -> "LumenFX appended terrain shadow face " + index);
            try (var pass = encoder.createRenderPass(descriptor)) {
                for (int i = 0; i < scene.entries().size(); i++) {
                    var draw = scene.entries().get(i);
                    pass.setPipeline(pipelines.computeIfAbsent(new Variant(draw.layer().pipeline(), spotCaster != null, directional),
                            TerrainPointShadowRenderer::pipeline));
                    if (spotCaster != null) pass.setUniform(SpotShadowCaster.UNIFORM, spotCaster);
                    if (directionalCaster != null) pass.setUniform(DirectionalShadowCaster.UNIFORM, directionalCaster);
                    pass.setUniform("PointShadowFace", faces.slice((long) (face - first) * faceStride, 64));
                    pass.setUniform("PointShadowSection", Objects.requireNonNull(sections).slice((long)i * sectionStride, 16));
                    if (draw.layer().pipeline().getShaderDefines().values().containsKey("ALPHA_CUTOUT"))
                        pass.bindTexture("ShadowBlockAtlas", Objects.requireNonNull(blockAtlas, "Cutout shadow casters need the block atlas"),
                                Objects.requireNonNull(sampler, "Cutout shadow casters need the host terrain sampler"));
                    pass.setVertexBuffer(0, draw.vertices().slice());
                    pass.setIndexBuffer(draw.indices() == null ? Objects.requireNonNull(defaultIndices) : draw.indices(),
                            draw.indexType() == null ? sequential.type() : draw.indexType());
                    pass.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
                }
            }
        }
    }

    private static RenderPipeline pipeline(Variant variant) {
        var host = variant.host();
        String alpha = host.getShaderDefines().values().get("ALPHA_CUTOUT");
        var bindings = BindGroupLayout.builder().withUniform("PointShadowFace", UniformType.UNIFORM_BUFFER)
                .withUniform("PointShadowSection", UniformType.UNIFORM_BUFFER);
        if (alpha != null) bindings.withSampler("ShadowBlockAtlas");
        var builder = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/point_shadow_terrain/" + NEXT.getAndIncrement()))
                .withVertexShader(LumenFx.id("core/point_shadow_terrain")).withFragmentShader(LumenFx.id("core/point_shadow_terrain"))
                .withVertexBinding(0, host.getVertexFormatBinding(0)).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(host.isCull()).withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, 0)).withBindGroupLayout(bindings.build());
        if (alpha != null) builder.withShaderDefine("ALPHA_CUTOUT", Float.parseFloat(alpha));
        if (variant.spot()) builder.withShaderDefine("LFX_SPOT_SHADOW_CASTER").withBindGroupLayout(SpotShadowCaster.LAYOUT);
        if (variant.directional())
            builder.withShaderDefine("LFX_DIRECTIONAL_SHADOW_CASTER").withBindGroupLayout(DirectionalShadowCaster.LAYOUT);
        return builder.build();
    }

    @Override public void close() { pipelines.clear(); }
}
