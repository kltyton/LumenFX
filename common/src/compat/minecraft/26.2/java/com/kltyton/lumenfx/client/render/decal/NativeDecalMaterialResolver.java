package com.kltyton.lumenfx.client.render.decal;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Resolves native decal material reception before additional direct lights and emissive decals. */
public final class NativeDecalMaterialResolver implements AutoCloseable {
    private record Formats(GpuFormat scene, GpuFormat albedo, GpuFormat normal) { }
    private record Variant(boolean screenOcclusion, boolean particleProjection) { }
    private final DecalReceiverView view = new DecalReceiverView();
    private final Map<Variant, RenderPipeline> pipelines = new HashMap<>();
    private @Nullable RenderTarget scratchScene, scratchAlbedo, scratchNormal;
    private @Nullable Formats formats;

    public void resolve(GpuTextureView encodedScene, Surfaces surface, DecalBuffers.Frame decals,
                        RenderTarget target, Matrix4fc modelView) {
        resolve(encodedScene, surface, decals, target, modelView, null);
    }

    public void resolve(GpuTextureView encodedScene, Surfaces surface, DecalBuffers.Frame decals,
                        RenderTarget target, Matrix4fc modelView, @Nullable GpuTextureView particleProjection) {
        resolve(encodedScene, surface, decals, target, modelView, particleProjection, null);
    }

    public void resolveAmbient(GpuTextureView encodedScene, Surfaces surface, DecalBuffers.Frame decals,
                               RenderTarget target, Matrix4fc modelView, @Nullable GpuTextureView particleProjection,
                               GpuTextureView ambient) {
        resolve(encodedScene, surface, decals, target, modelView, particleProjection, ambient);
    }

    private void resolve(GpuTextureView encodedScene, Surfaces surface, DecalBuffers.Frame decals,
                         RenderTarget target, Matrix4fc modelView, @Nullable GpuTextureView particleProjection,
                         @Nullable GpuTextureView ambient) {
        RenderSystem.assertOnRenderThread();
        var response = Objects.requireNonNull(surface.materialResponse(), "Native decals require captured material responses");
        if (surface.width() != target.width || surface.height() != target.height
                || decals.width() != target.width || decals.height() != target.height)
            throw new IllegalArgumentException("Native decal material and scene dimensions differ");
        prepareTargets(target.width, target.height, new Formats(target.getColorTexture().getFormat(),
                surface.albedo().texture().getFormat(), surface.normalAmbient().texture().getFormat()));
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        boolean screenOcclusion = ambient != null;
        var pipeline = pipelines.computeIfAbsent(new Variant(screenOcclusion, particleProjection != null), this::pipeline);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice uniformSlice;
        try (var uniforms = encoder.transientMemory().allocateGpuMapped(DecalReceiverView.UNIFORM_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            view.write(modelView, uniforms.data().order(ByteOrder.nativeOrder()), 0);
            uniformSlice = uniforms.slice();
        }
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX native decal material")
                .withColorAttachment(Objects.requireNonNull(scratchScene).getColorTextureView())
                .withRenderArea(new RenderPass.RenderArea(0, 0, target.width, target.height));
        if (!screenOcclusion) descriptor.withColorAttachment(Objects.requireNonNull(scratchAlbedo).getColorTextureView())
                .withColorAttachment(Objects.requireNonNull(scratchNormal).getColorTextureView());
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            pass.setUniform("DecalReceiverView", uniformSlice);
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("LinearScene", target.getColorTextureView(), sampler);
            pass.bindTexture("EncodedScene", encodedScene, sampler);
            pass.bindTexture("SurfaceAlbedo", surface.albedo(), sampler);
            if (!screenOcclusion) pass.bindTexture("SurfaceNormalAmbient", surface.normalAmbient(), sampler);
            pass.bindTexture("SurfaceGeometry", surface.positionDepth(), sampler);
            pass.bindTexture("MaterialBase", response.base(), sampler);
            pass.bindTexture("MaterialZero", response.zero(), sampler);
            pass.bindTexture("MaterialUnit", response.unit(), sampler);
            pass.bindTexture("DBufferColor", decals.color(), sampler);
            if (!screenOcclusion) pass.bindTexture("DBufferNormal", decals.normal(), sampler);
            if (particleProjection != null) pass.bindTexture("DecalProjectionGeometry", particleProjection, sampler);
            if (ambient != null) pass.bindTexture("DecalScreenOcclusion", ambient, sampler);
            pass.draw(3, 1, 0, 0);
        }
        encoder.copyTextureToTexture(Objects.requireNonNull(scratchScene).getColorTexture(), target.getColorTexture(), 0, 0, 0, 0, 0, target.width, target.height);
        if (!screenOcclusion) {
            encoder.copyTextureToTexture(Objects.requireNonNull(scratchAlbedo).getColorTexture(), surface.albedo().texture(), 0, 0, 0, 0, 0, target.width, target.height);
            encoder.copyTextureToTexture(Objects.requireNonNull(scratchNormal).getColorTexture(), surface.normalAmbient().texture(), 0, 0, 0, 0, 0, target.width, target.height);
        }
    }

    private RenderPipeline pipeline(Variant variant) {
        Formats layout = Objects.requireNonNull(formats);
        var bindings = BindGroupLayout.builder().withUniform("DecalReceiverView", UniformType.UNIFORM_BUFFER)
                .withSampler("LinearScene").withSampler("EncodedScene").withSampler("SurfaceAlbedo").withSampler("SurfaceGeometry")
                .withSampler("MaterialBase").withSampler("MaterialZero").withSampler("MaterialUnit")
                .withSampler("DBufferColor");
        if (variant.screenOcclusion()) bindings.withSampler("DecalScreenOcclusion");
        else bindings.withSampler("SurfaceNormalAmbient").withSampler("DBufferNormal");
        if (variant.particleProjection()) bindings.withSampler("DecalProjectionGeometry");
        String path = (variant.screenOcclusion() ? "screen_ao" : "base") + (variant.particleProjection() ? "_particle_depth" : "");
        var builder = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/native_decal_material/" + path))
                .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/native_decal_material"))
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), layout.scene(), ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (variant.screenOcclusion()) builder.withShaderDefine("LFX_DECAL_SCREEN_AO");
        else builder.withColorTargetState(1, new ColorTargetState(Optional.empty(), layout.albedo(), ColorTargetState.WRITE_ALL))
                .withColorTargetState(2, new ColorTargetState(Optional.empty(), layout.normal(), ColorTargetState.WRITE_ALL));
        if (variant.particleProjection()) builder.withShaderDefine("LFX_DECAL_PARTICLE_PROJECTION");
        return builder.build();
    }

    private void prepareTargets(int width, int height, Formats layout) {
        boolean layoutChanged = !layout.equals(formats);
        if (!layoutChanged && scratchScene != null && scratchScene.width == width && scratchScene.height == height) return;
        OwnedColorTarget nextScene = null, nextAlbedo = null, nextNormal = null;
        try {
            nextScene = new OwnedColorTarget("LumenFX received scene", width, height, layout.scene());
            nextAlbedo = new OwnedColorTarget("LumenFX received material", width, height, layout.albedo());
            nextNormal = new OwnedColorTarget("LumenFX received normal and material AO", width, height, layout.normal());
        }
        catch (RuntimeException | Error error) {
            if (nextScene != null) nextScene.closeOnFailure(error);
            if (nextAlbedo != null) nextAlbedo.closeOnFailure(error);
            if (nextNormal != null) nextNormal.closeOnFailure(error);
            throw error;
        }
        destroyTargets();
        scratchScene = nextScene; scratchAlbedo = nextAlbedo; scratchNormal = nextNormal; formats = layout;
        if (layoutChanged) pipelines.clear();
    }

    private void destroyTargets() {
        if (scratchScene != null) scratchScene.destroyBuffers();
        if (scratchAlbedo != null) scratchAlbedo.destroyBuffers();
        if (scratchNormal != null) scratchNormal.destroyBuffers();
        scratchScene = scratchAlbedo = scratchNormal = null;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        destroyTargets(); formats = null; pipelines.clear();
    }
}
