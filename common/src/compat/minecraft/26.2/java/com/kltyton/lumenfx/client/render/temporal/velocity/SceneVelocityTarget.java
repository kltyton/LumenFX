package com.kltyton.lumenfx.client.render.temporal.velocity;

import com.kltyton.lumenfx.LumenFx;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Optional;

/** Resolves visible animated surfaces into the desktop source velocity encoding. */
public final class SceneVelocityTarget implements AutoCloseable {
    private final String label;
    private TextureTarget target;
    private RenderPipeline pipeline;

    public SceneVelocityTarget() { this("LumenFX scene velocity"); }
    public SceneVelocityTarget(String label) { this.label = label; }

    public GpuTextureView resolve(GpuTextureView sceneDepth, GpuTextureView previousNdc,
                                  GpuTextureView coverageDepth, boolean historyValid,
                                  float jitterX, float jitterY, float previousJitterX, float previousJitterY) {
        RenderSystem.assertOnRenderThread();
        int width = sceneDepth.texture().getWidth(0), height = sceneDepth.texture().getHeight(0);
        if (previousNdc.texture().getWidth(0) != width || previousNdc.texture().getHeight(0) != height
                || coverageDepth.texture().getWidth(0) != width || coverageDepth.texture().getHeight(0) != height)
            throw new IllegalArgumentException("Motion coverage and scene depth must share the current viewport");
        if (target == null) target = new TextureTarget(label, width, height, false, GpuFormat.RGBA16_UNORM);
        else if (target.width != width || target.height != height) target.resize(width, height);
        if (pipeline == null) pipeline = pipeline();
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice uniforms;
        try (var allocation = encoder.transientMemory().allocateGpuMapped(32, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = allocation.data().order(ByteOrder.nativeOrder());
            data.putFloat(width).putFloat(height).putFloat(device.getDeviceInfo().isZZeroToOne() ? 1 : 0).putFloat(historyValid ? 1 : 0);
            data.putFloat(jitterX).putFloat(jitterY).putFloat(previousJitterX).putFloat(previousJitterY);
            uniforms = allocation.slice();
        }
        try (var pass = encoder.createRenderPass(() -> label, target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("SceneMotion", uniforms);
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("SceneDepthSampler", sceneDepth, sampler);
            pass.bindTexture("PreviousNdcSampler", previousNdc, sampler);
            pass.bindTexture("CoverageDepthSampler", coverageDepth, sampler);
            pass.draw(3, 1, 0, 0);
        }
        return target.getColorTextureView();
    }

    private static RenderPipeline pipeline() {
        var bindings = BindGroupLayout.builder().withUniform("SceneMotion", UniformType.UNIFORM_BUFFER)
                .withSampler("SceneDepthSampler").withSampler("PreviousNdcSampler").withSampler("CoverageDepthSampler");
        return RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/temporal/scene_velocity"))
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withFragmentShader(LumenFx.id("temporal/scene_velocity"))
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_UNORM, ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (target != null) target.destroyBuffers();
        target = null;
    }
}
