package com.kltyton.lumenfx.client.render.light.subsurface;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import com.kltyton.lumenfx.client.render.scene.surface.adaptive.AdaptiveParticleBuffer;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.subsurface.SubsurfaceProfiles;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Separates profile irradiance from sharp scene lighting until the screen-space diffusion pass. */
public final class LegacySubsurfaceScattering implements AutoCloseable {
    private static final GpuFormat FILTER_FORMAT = GpuFormat.RGBA16_FLOAT;
    private static final String VIEW_BLOCK = "LfxSubsurfaceView";

    private enum Pass { SETUP, HORIZONTAL, VERTICAL, RECOMBINE }
    private record Variant(Pass pass, boolean halfResolution, boolean burley, boolean velocity, boolean mips,
                           boolean adaptive, GpuFormat format) { }

    private final Map<Variant, RenderPipeline> pipelines = new HashMap<>();
    private final OwnedColorTarget[] filterTargets = new OwnedColorTarget[3];
    private final SubsurfaceQualityHistory quality = new SubsurfaceQualityHistory();
    private final BurleyIrradianceMips irradianceMips = new BurleyIrradianceMips();
    private @Nullable OwnedColorTarget profileSelection;

    public static boolean usesBurleyHistory(NiagaraPlatformEnvironment settings) {
        return Objects.requireNonNull(settings.console("r.SubsurfaceScattering")).bits() != 0
                && Float.intBitsToFloat(Objects.requireNonNull(settings.console("r.SSS.Scale")).bits()) > 0
                && Objects.requireNonNull(settings.console("r.SSS.Burley.Quality")).bits() != 0
                && (Objects.requireNonNull(settings.console("r.SSS.HalfRes")).bits() == 0
                    || Objects.requireNonNull(settings.console("r.SSS.HalfRes.ForceSeparable")).bits() == 0);
    }

    public void resolve(CommandEncoder encoder, RenderTarget scene, OpaqueParticleSurfaces.Frame surfaces,
                        Matrix4fc projection, Matrix4fc modelView, Vec3 camera) {
        GpuTextureView diffuse = Objects.requireNonNull(surfaces.subsurfaceDiffuse());
        var platform = NiagaraPlatforms.snapshot();
        boolean half = Objects.requireNonNull(platform.console("r.SSS.HalfRes")).bits() != 0;
        boolean burley = usesBurleyHistory(platform);
        var adaptive = surfaces.hasLightingFeature(MaterialDefinition.SUBSTRATE_DIFFUSION)
                ? Objects.requireNonNull(surfaces.adaptive()).sampled() : null;
        boolean linear = Objects.requireNonNull(platform.console("r.SSS.Filter")).bits() != 0;
        int samples = Math.clamp(Objects.requireNonNull(platform.console("r.SSS.SampleSet")).bits(), 0, 2);
        float scale = Float.intBitsToFloat(Objects.requireNonNull(platform.console("r.SSS.Scale")).bits());
        int divisor = half ? 2 : 1;
        int width = Math.ceilDiv(scene.width, divisor), height = Math.ceilDiv(scene.height, divisor);
        var device = RenderSystem.getDevice();
        var capture = WorldHdrCapture.INSTANCE;
        var minecraft = Minecraft.getInstance();
        long sequence = capture.renderFrameSequence();
        float[] jitter = burley ? capture.temporalAAParameters() : null;
        // The native host projection flips the source screen-space Y axis before storing scene motion.
        float jitterX = jitter == null ? 0 : jitter[2] * 2f / scene.width;
        float jitterY = jitter == null ? 0 : jitter[3] * 2f / scene.height;
        SubsurfaceQualityHistory.Frame history = null;
        GpuTextureView velocity = null;
        GpuTextureView selection = null;
        if (burley) {
            history = quality.begin(new SubsurfaceQualityHistory.View(minecraft.level, capture.cameraOwner(), capture.cameraCutVersion(),
                    sequence, minecraft.isPaused(), camera, new Matrix4f(projection).mul(modelView), jitterX, jitterY), width, height);
            velocity = capture.prepareNiagaraSceneVelocity(scene.getDepthTextureView());
            selection = selection(width, height).getColorTextureView();
        } else quality.invalidate();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice parameters;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(192, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            projection.get(0, data);
            data.position(64);
            data.putFloat(Math.abs(projection.m00()) * scale / 6).putFloat(Math.abs(projection.m00()) * scale)
                    .putFloat(width).putFloat(height);
            data.putInt(samples).putInt(zeroToOne ? 1 : 0).putInt((int) (sequence - 1L))
                    .putInt(device.backend instanceof GlDevice ? 1 : 0);
            (history == null ? new Matrix4f() : history.previousClip()).get(96, data);
            data.position(160);
            data.putFloat(jitterX * 0.5f).putFloat(jitterY * 0.5f)
                    .putFloat(history == null ? 0 : history.jitterDeltaX()).putFloat(history == null ? 0 : history.jitterDeltaY());
            data.putInt(Objects.requireNonNull(platform.console("r.SSS.Burley.NumSamplesOverride")).bits())
                    .putInt(Objects.requireNonNull(platform.console("r.SSS.Burley.BilateralFilterKernelFunctionType")).bits())
                    .putInt(history != null && history.previous() != null ? 1 : 0)
                    .putInt(Objects.requireNonNull(platform.console("r.SSS.Burley.EnableProfileIdCache")).bits());
            parameters = mapped.slice();
        }

        var atlas = Objects.requireNonNull(EffectTextures.get(SubsurfaceProfiles.ATLAS), "Subsurface profile atlas is unavailable");
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var filteredSampler = RenderSystem.getSamplerCache().getClampToEdge(linear ? FilterMode.LINEAR : FilterMode.NEAREST);
        GpuTextureView input = diffuse;
        BurleyIrradianceMips.Frame mips = null;
        for (Pass phase : Pass.values()) {
            boolean setup = phase == Pass.SETUP, recombine = phase == Pass.RECOMBINE;
            boolean burleyPass = history != null && !setup && !recombine;
            boolean writeSelection = setup && selection != null;
            boolean writeHistory = burleyPass && phase == Pass.VERTICAL;
            RenderTarget destination = recombine ? scene : filterTarget(phase, width, height);
            boolean sampleMips = phase == Pass.HORIZONTAL && mips != null;
            var variant = new Variant(phase, half, burleyPass || writeSelection, burleyPass && velocity != null, sampleMips, adaptive != null,
                    destination.getColorTexture().getFormat());
            var descriptor = RenderPassDescriptor.create(() -> "LumenFX subsurface " + phase)
                    .withColorAttachment(destination.getColorTextureView())
                    .withRenderArea(new RenderPass.RenderArea(0, 0, destination.width, destination.height));
            if (writeHistory) descriptor.withColorAttachment(Objects.requireNonNull(history).output());
            if (writeSelection) descriptor.withColorAttachment(Objects.requireNonNull(selection));
            try (var pass = encoder.createRenderPass(descriptor)) {
                pass.setPipeline(pipelines.computeIfAbsent(variant, LegacySubsurfaceScattering::pipeline));
                pass.setUniform(VIEW_BLOCK, parameters);
                pass.bindTexture("LfxSubsurfaceInput", input, setup ? nearest : filteredSampler);
                pass.bindTexture("LfxSubsurfacePosition", surfaces.scene().positionDepth(), nearest);
                pass.bindTexture("LfxSubsurfaceDepth", Objects.requireNonNull(scene.getDepthTextureView()), nearest);
                if (adaptive != null) pass.bindTexture(AdaptiveParticleBuffer.SAMPLER, adaptive, nearest);
                if (!setup) {
                    pass.bindTexture("LfxSubsurfaceProfiles", atlas.view(), atlas.sampler());
                    pass.bindTexture("LfxSubsurfaceCustom", Objects.requireNonNull(surfaces.customData()), nearest);
                }
                if (writeSelection) pass.bindTexture("LfxSubsurfaceCustom", Objects.requireNonNull(surfaces.customData()), nearest);
                if (burleyPass) {
                    var profileHistory = Objects.requireNonNull(history);
                    var bilinear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
                    if (sampleMips) {
                        var pyramid = Objects.requireNonNull(mips);
                        pass.bindTexture("LfxBurleyRadiance", pyramid.irradiance(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true));
                        pass.bindTexture("AfisTileCount", pyramid.count(), nearest);
                        pass.setUniform("LfxBurleyMipSettings", pyramid.controls());
                    } else pass.bindTexture("LfxBurleyRadiance", input, writeHistory ? bilinear : nearest);
                    pass.bindTexture("LfxSubsurfaceSelection", Objects.requireNonNull(selection), nearest);
                    pass.bindTexture("LfxSubsurfaceQuality", profileHistory.previous() == null ? diffuse : profileHistory.previous(), nearest);
                    if (phase == Pass.HORIZONTAL) pass.bindTexture("LfxSubsurfaceNormal", surfaces.scene().normalAmbient(), nearest);
                    if (velocity != null) pass.bindTexture("LfxSubsurfaceVelocity", velocity, nearest);
                    if (writeHistory) {
                        var setupView = filterTargets[0].getColorTextureView();
                        pass.bindTexture("LfxSubsurfaceSetup", setupView, bilinear);
                        pass.bindTexture("LfxSubsurfaceSetupDepth", setupView, filteredSampler);
                    }
                }
                if (recombine) {
                    pass.bindTexture("LfxUnfilteredDiffuse", diffuse, nearest);
                    pass.bindTexture("LfxSubsurfaceMaterial", Objects.requireNonNull(surfaces.sourceMaterial()), nearest);
                }
                pass.draw(3, 1, 0, 0);
            }
            if (!recombine) input = destination.getColorTextureView();
            if (writeSelection) mips = irradianceMips.build(encoder, input, Objects.requireNonNull(selection), atlas.view(),
                    Math.max(0, Objects.requireNonNull(platform.console("r.SSS.Burley.MinGenerateMipsTileCount")).bits()));
        }
        if (history != null) quality.commit(history);
    }

    private OwnedColorTarget filterTarget(Pass phase, int width, int height) {
        int index = phase.ordinal();
        var previous = filterTargets[index];
        if (previous != null && previous.width == width && previous.height == height) return previous;
        var next = new OwnedColorTarget("LumenFX subsurface " + phase, width, height, FILTER_FORMAT);
        filterTargets[index] = next;
        if (previous != null) previous.close();
        return next;
    }

    private OwnedColorTarget selection(int width, int height) {
        var previous = profileSelection;
        if (previous != null && previous.width == width && previous.height == height) return previous;
        var next = new OwnedColorTarget("LumenFX subsurface profile selection", width, height, GpuFormat.RGBA32_FLOAT);
        profileSelection = next;
        if (previous != null) previous.close();
        return next;
    }

    private static RenderPipeline pipeline(Variant variant) {
        var bindings = BindGroupLayout.builder().withUniform(VIEW_BLOCK, UniformType.UNIFORM_BUFFER)
                .withSampler("LfxSubsurfaceInput").withSampler("LfxSubsurfacePosition").withSampler("LfxSubsurfaceDepth");
        if (variant.adaptive()) bindings.withSampler(AdaptiveParticleBuffer.SAMPLER);
        boolean recombine = variant.pass() == Pass.RECOMBINE;
        if (variant.pass() != Pass.SETUP) bindings.withSampler("LfxSubsurfaceProfiles").withSampler("LfxSubsurfaceCustom");
        if (recombine) bindings.withSampler("LfxUnfilteredDiffuse").withSampler("LfxSubsurfaceMaterial");
        if (variant.burley() && variant.pass() == Pass.SETUP) bindings.withSampler("LfxSubsurfaceCustom");
        else if (variant.burley()) {
            bindings.withSampler("LfxSubsurfaceQuality").withSampler("LfxBurleyRadiance").withSampler("LfxSubsurfaceSelection");
            if (variant.pass() == Pass.HORIZONTAL) bindings.withSampler("LfxSubsurfaceNormal");
            if (variant.pass() == Pass.VERTICAL) bindings.withSampler("LfxSubsurfaceSetup").withSampler("LfxSubsurfaceSetupDepth");
            if (variant.velocity()) bindings.withSampler("LfxSubsurfaceVelocity");
        }
        if (variant.mips()) bindings.withSampler("AfisTileCount").withUniform("LfxBurleyMipSettings", UniformType.UNIFORM_BUFFER);
        var builder = RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/subsurface/" + variant.pass().ordinal() + "/" + (variant.halfResolution() ? "half/" : "full/")
                        + (variant.burley() ? "burley/" : "") + (variant.velocity() ? "velocity/" : "")
                        + (variant.mips() ? "mips/" : "") + (variant.adaptive() ? "adaptive/" : "") + variant.format().ordinal()))
                .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/subsurface/separable"))
                .withShaderDefine("LFX_SSS_PASS", variant.pass().ordinal())
                .withShaderDefine("LFX_SSS_HALF_RES", variant.halfResolution() ? 1 : 0)
                .withShaderDefine("LFX_SSS_AFIS", variant.burley() ? 1 : 0)
                .withShaderDefine("LFX_SSS_VELOCITY", variant.velocity() ? 1 : 0)
                .withShaderDefine("LFX_SSS_MIPS", variant.mips() ? 1 : 0)
                .withShaderDefine("LFX_SSS_ADAPTIVE", variant.adaptive() ? 1 : 0)
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(new ColorTargetState(recombine ? Optional.of(BlendFunction.ADDITIVE) : Optional.empty(),
                        variant.format(), recombine ? 7 : ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (variant.burley() && variant.pass() == Pass.VERTICAL)
            builder.withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));
        if (variant.burley() && variant.pass() == Pass.SETUP)
            builder.withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL));
        return builder.build();
    }

    @Override public void close() {
        for (int index = 0; index < filterTargets.length; index++) {
            var target = filterTargets[index];
            filterTargets[index] = null;
            if (target != null) target.close();
        }
        pipelines.clear();
        if (profileSelection != null) { profileSelection.close(); profileSelection = null; }
        quality.close();
        irradianceMips.close();
    }
}
