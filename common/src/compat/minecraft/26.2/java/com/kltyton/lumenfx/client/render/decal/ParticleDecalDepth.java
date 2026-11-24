package com.kltyton.lumenfx.client.render.decal;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.scene.surface.NativeMaterialResponse;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/** Composes a native decal projection depth from real world coverage and opaque particle geometry. */
public final class ParticleDecalDepth implements AutoCloseable {
    private record Variant(RenderPipeline base, boolean receivesDecals) { }
    private static final AtomicLong NEXT = new AtomicLong();
    private final Map<Variant, RenderPipeline> pipelines = new HashMap<>();
    private @Nullable TextureTarget geometry;
    private @Nullable OwnedColorTarget mask;

    public RenderPassDescriptor begin(CommandEncoder encoder, RenderTarget scene, Surfaces surfaces) {
        if (geometry == null || geometry.width != scene.width || geometry.height != scene.height) resize(scene.width, scene.height);
        var position = Objects.requireNonNull(geometry);
        var coverage = Objects.requireNonNull(mask);
        position.copyDepthFrom(scene);
        encoder.copyTextureToTexture(surfaces.positionDepth().texture(), position.getColorTexture(), 0, 0, 0, 0, 0, scene.width, scene.height);
        var response = Objects.requireNonNull(surfaces.materialResponse(), "Native decals require the world coverage mask");
        encoder.copyTextureToTexture(response.base().texture(), coverage.getColorTexture(), 0, 0, 0, 0, 0, scene.width, scene.height);
        return descriptor(scene);
    }

    public RenderPassDescriptor descriptor(RenderTarget scene) {
        var position = Objects.requireNonNull(geometry);
        var coverage = Objects.requireNonNull(mask);
        return RenderPassDescriptor.create(() -> "LumenFX particle decal receiver depth")
                .withColorAttachment(scene.getColorTextureView()).withColorAttachment(position.getColorTextureView())
                .withColorAttachment(coverage.getColorTextureView()).withDepthAttachment(Objects.requireNonNull(position.getDepthTextureView()))
                .withRenderArea(new RenderPass.RenderArea(0, 0, scene.width, scene.height));
    }

    public Surfaces projection(Surfaces world) {
        var response = Objects.requireNonNull(world.materialResponse());
        return new Surfaces(world.albedo(), world.normalAmbient(), Objects.requireNonNull(geometry).getColorTextureView(),
                world.width(), world.height(), new NativeMaterialResponse(Objects.requireNonNull(mask).getColorTextureView(),
                response.zero(), response.unit()));
    }

    public RenderTarget depthTarget() { return Objects.requireNonNull(geometry); }

    public RenderPipeline pipeline(RenderPipeline base, boolean receivesDecals, ShaderSource shaders) {
        return pipelines.computeIfAbsent(new Variant(base, receivesDecals), variant -> create(variant, shaders));
    }

    private static RenderPipeline create(Variant variant, ShaderSource shaders) {
        var base = variant.base();
        var id = LumenFx.id("pipeline/particle_decal_depth/" + NEXT.getAndIncrement());
        var pipeline = com.kltyton.lumenfx.client.platform.LumenClientPlatform.toBuilder(base).withLocation(id).withFragmentShader(id)
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), base.getColorTargetStates()[0].format(), 0))
                .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
                .withColorTargetState(2, new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
                .withShaderDefine("LFX_PARTICLE_DECAL_RECEIVER", variant.receivesDecals() ? 16392 : 16384)
                .build();
        String original = Objects.requireNonNull(shaders.get(base.getFragmentShader(), ShaderType.FRAGMENT));
        int insertion = GlslSourceScanner.header(original).insertionIndex();
        String source = original.substring(0, insertion) + """

                layout(location=1) out vec4 lfxDecalReceiverDepth;
                layout(location=2) out vec4 lfxDecalReceiverMask;
                #define main lfxDecalReceiverMain
                """ + original.substring(insertion) + """

                #undef main
                void main() {
                    lfxDecalReceiverMain();
                    float depth = gl_FragCoord.z;
                #if LFX_MATERIAL_WRITES_DEPTH
                    depth = lfxMaterialDeviceZ;
                #elif LFX_NANITE_MATERIAL
                    depth = lfxNaniteGeometry.deviceDepth;
                #endif
                    lfxDecalReceiverDepth = vec4(0.0, 0.0, float(LFX_PARTICLE_DECAL_RECEIVER), depth);
                    lfxDecalReceiverMask = vec4(0.0, 0.0, 0.0, 1.0);
                }
                """;
        if (!RenderSystem.getDevice().precompilePipeline(pipeline, (shader, stage) ->
                shader.equals(id) && stage == ShaderType.FRAGMENT ? source : shaders.get(shader, stage)).isValid())
            throw new IllegalStateException("Particle decal receiver depth pipeline failed to compile: " + base.getLocation());
        return pipeline;
    }

    private void resize(int width, int height) {
        var nextMask = new OwnedColorTarget("LumenFX particle decal receiver mask", width, height, GpuFormat.RGBA32_FLOAT);
        TextureTarget next;
        try { next = new TextureTarget("LumenFX particle decal receiver geometry", width, height, true, GpuFormat.RGBA32_FLOAT); }
        catch (RuntimeException | Error failure) { nextMask.closeOnFailure(failure); throw failure; }
        releaseTargets();
        geometry = next;
        mask = nextMask;
    }

    private void releaseTargets() {
        if (geometry != null) { geometry.destroyBuffers(); geometry = null; }
        if (mask != null) { mask.close(); mask = null; }
    }

    @Override public void close() {
        releaseTargets();
        pipelines.clear();
    }
}
