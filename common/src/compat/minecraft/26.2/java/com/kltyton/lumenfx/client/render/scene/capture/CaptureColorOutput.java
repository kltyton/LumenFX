package com.kltyton.lumenfx.client.render.scene.capture;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.CaptureSource;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Packs the capture scene attachments into the selected Scene Capture 2D source layout. */
final class CaptureColorOutput {
    private final Map<GpuFormat, RenderPipeline> pipelines = new EnumMap<>(GpuFormat.class);

    void write(RenderTarget source, GpuTextureView output, CaptureSource mode, @Nullable Surfaces surfaces,
               Matrix4fc view, Matrix4fc projection, boolean flipY) {
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        var format = output.texture().getFormat();
        var pipeline = pipelines.computeIfAbsent(format, CaptureColorOutput::pipeline);
        GpuBufferSlice uniform;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(96, device.getDeviceInfo().limits().minUniformOffsetAlignment(),
                GpuBuffer.USAGE_UNIFORM)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            bytes.putFloat(projection.m22()).putFloat(projection.m23()).putFloat(projection.m32()).putFloat(projection.m33());
            bytes.putInt(mode.ordinal()).putInt(source.height).putInt(flipY ? 1 : 0).putInt(0);
            new Matrix4f(view).transpose().get(32, bytes);
            uniform = mapped.slice();
        }
        var color = source.getColorTextureView();
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = encoder.createRenderPass(() -> "Niagara Scene Capture 2D output", output, Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("CaptureOutput", uniform);
            pass.bindTexture("CaptureColor", color, sampler);
            pass.bindTexture("CaptureDepth", source.getDepthTextureView(), sampler);
            pass.bindTexture("CaptureBaseColor", surfaces == null ? color : surfaces.albedo(), sampler);
            pass.bindTexture("CaptureNormal", surfaces == null ? color : surfaces.normalAmbient(), sampler);
            pass.bindTexture("CaptureGeometry", surfaces == null ? color : surfaces.positionDepth(), sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static RenderPipeline pipeline(GpuFormat format) {
        return RenderPipeline.builder().withLocation(LumenFx.id("pipeline/niagara_scene_capture/" + format.name().toLowerCase(Locale.ROOT)))
                .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/niagara_scene_capture"))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("CaptureOutput", UniformType.UNIFORM_BUFFER)
                        .withSampler("CaptureColor").withSampler("CaptureDepth").withSampler("CaptureBaseColor")
                        .withSampler("CaptureNormal").withSampler("CaptureGeometry").build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    }
}
