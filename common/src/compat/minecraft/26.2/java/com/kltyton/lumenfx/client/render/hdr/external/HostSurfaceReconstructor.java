package com.kltyton.lumenfx.client.render.hdr.external;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Reconstructs host receiver surfaces from linear scene color and depth for shaderpacks without Complementary GBuffer capture. */
public final class HostSurfaceReconstructor implements AutoCloseable {
    private static final ColorTargetState FLOAT = new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL);

    private @Nullable RenderPipeline pipeline;
    private @Nullable OwnedColorTarget baseDepth;
    private @Nullable OwnedColorTarget geometry;
    private @Nullable OwnedColorTarget material;

    public ShaderpackMaterialSurfaces reconstruct(RenderTarget linearScene, Matrix4fc materialProjection) {
        RenderSystem.assertOnRenderThread();
        int width = linearScene.width;
        int height = linearScene.height;
        GpuTextureView colorView = Objects.requireNonNull(linearScene.getColorTextureView(), "Host surface reconstruction requires scene color");
        GpuTextureView depthView = Objects.requireNonNull(linearScene.getDepthTextureView(), "Host surface reconstruction requires scene depth");
        ensureTargets(width, height);
        RenderPipeline activePipeline = ensurePipeline();
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice uniformSlice;
        try (var uniform = encoder.transientMemory().allocateGpuMapped(64, alignment, GpuBuffer.USAGE_UNIFORM)) {
            new Matrix4f(materialProjection).invert().get(uniform.data().order(ByteOrder.nativeOrder()));
            uniformSlice = uniform.slice();
        }
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX host surface reconstruction")
                .withColorAttachment(Objects.requireNonNull(baseDepth).getColorTextureView())
                .withColorAttachment(Objects.requireNonNull(geometry).getColorTextureView())
                .withColorAttachment(Objects.requireNonNull(material).getColorTextureView())
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(activePipeline);
            pass.setUniform("HostSurfaceReconstructView", uniformSlice);
            pass.bindTexture("SceneColor", colorView, nearest);
            pass.bindTexture("SceneDepth", depthView, nearest);
            pass.draw(3, 1, 0, 0);
        }
        return new ShaderpackMaterialSurfaces(
                baseDepth.getColorTextureView(),
                geometry.getColorTextureView(),
                material.getColorTextureView(),
                null,
                ShaderpackMaterialSurfaces.RawSpecularEncoding.NONE,
                width,
                height,
                materialProjection,
                null,
                ColourEncoding.LINEAR
        );
    }

    private RenderPipeline ensurePipeline() {
        if (pipeline == null) {
            var bindings = BindGroupLayout.builder()
                    .withUniform("HostSurfaceReconstructView", UniformType.UNIFORM_BUFFER)
                    .withSampler("SceneColor")
                    .withSampler("SceneDepth")
                    .build();
            pipeline = RenderPipeline.builder()
                    .withLocation(LumenFx.id("pipeline/host_surface_reconstruct"))
                    .withVertexShader(LumenFx.id("post/fullscreen"))
                    .withFragmentShader(LumenFx.id("post/host_surface_reconstruct"))
                    .withBindGroupLayout(bindings)
                    .withColorTargetState(0, FLOAT)
                    .withColorTargetState(1, FLOAT)
                    .withColorTargetState(2, FLOAT)
                    .withDepthStencilState(Optional.empty())
                    .withCull(false)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();
        }
        return pipeline;
    }

    private void ensureTargets(int width, int height) {
        if (baseDepth != null && baseDepth.width == width && baseDepth.height == height) return;
        closeTargets();
        OwnedColorTarget nextBase = null, nextGeo = null, nextMat = null;
        try {
            nextBase = new OwnedColorTarget("LumenFX reconstructed host base and depth", width, height, GpuFormat.RGBA32_FLOAT);
            nextGeo = new OwnedColorTarget("LumenFX reconstructed host geometry", width, height, GpuFormat.RGBA32_FLOAT);
            nextMat = new OwnedColorTarget("LumenFX reconstructed host material", width, height, GpuFormat.RGBA32_FLOAT);
            baseDepth = nextBase;
            geometry = nextGeo;
            material = nextMat;
        } catch (RuntimeException | Error failure) {
            if (nextMat != null) nextMat.closeOnFailure(failure);
            if (nextGeo != null) nextGeo.closeOnFailure(failure);
            if (nextBase != null) nextBase.closeOnFailure(failure);
            throw failure;
        }
    }

    private void closeTargets() {
        if (baseDepth != null) { baseDepth.close(); baseDepth = null; }
        if (geometry != null) { geometry.close(); geometry = null; }
        if (material != null) { material.close(); material = null; }
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        closeTargets();
        pipeline = null;
    }
}
