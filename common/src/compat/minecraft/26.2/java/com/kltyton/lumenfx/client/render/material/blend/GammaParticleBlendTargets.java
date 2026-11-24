package com.kltyton.lumenfx.client.render.material.blend;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Temporary encoded color and coverage for a consecutive run of Gamma particle draws. */
public final class GammaParticleBlendTargets implements AutoCloseable {
    // Integer attachments bypass normalized color blending in the host OpenGL backend.
    public static final GpuFormat COVERAGE_FORMAT = GpuFormat.R8_UINT;
    private final Map<GpuFormat, RenderPipeline> encodePipelines = new HashMap<>();
    private final Map<GpuFormat, RenderPipeline> decodePipelines = new HashMap<>();
    private @Nullable TextureTarget encoded;
    private @Nullable OwnedColorTarget coverage;

    public RenderTarget begin(RenderTarget linear, boolean floatingPoint) {
        RenderSystem.assertOnRenderThread();
        var format = floatingPoint ? GpuFormat.RGBA32_FLOAT : GpuFormat.RGBA8_UNORM;
        var target = encoded;
        if (target == null || target.width != linear.width || target.height != linear.height
                || target.getColorTexture().getFormat() != format) {
            releaseTargets();
            target = new TextureTarget("LumenFX Gamma blend color and depth", linear.width, linear.height, true, format);
            encoded = target;
            try {
                coverage = new OwnedColorTarget("LumenFX Gamma blend coverage", linear.width, linear.height, COVERAGE_FORMAT);
            } catch (RuntimeException | Error failure) {
                releaseTargets();
                throw failure;
            }
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        target.copyDepthFrom(linear);
        var pipeline = encodePipelines.computeIfAbsent(format, colorFormat -> transferPipeline(colorFormat, false));
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX encode Gamma blend input")
                .withColorAttachment(target.getColorTextureView())
                .withColorAttachment(Objects.requireNonNull(coverage).getColorTextureView())
                .withRenderArea(new RenderPass.RenderArea(0, 0, target.width, target.height));
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            pass.bindTexture("ParticleSceneSampler", linear.getColorTextureView(), sampler);
            pass.draw(3, 1, 0, 0);
        }
        return target;
    }

    public @Nullable GpuTextureView coverage(RenderTarget target) {
        return target == encoded ? Objects.requireNonNull(coverage).getColorTextureView() : null;
    }

    public void composite(RenderTarget encodedRun, RenderTarget linear, boolean depthWritten) {
        RenderSystem.assertOnRenderThread();
        var selected = Objects.requireNonNull(coverage(encodedRun), "Gamma blend run has no coverage");
        var format = linear.getColorTexture().getFormat();
        var pipeline = decodePipelines.computeIfAbsent(format, colorFormat -> transferPipeline(colorFormat, true));
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        PhotonFullscreenPass.draw("LumenFX compose Gamma particle pixels", pipeline, linear.getColorTextureView(), pass -> {
            pass.bindTexture("ParticleSceneSampler", encodedRun.getColorTextureView(), sampler);
            pass.bindTexture("ParticleCoverageSampler", selected, sampler);
        });
        if (depthWritten) linear.copyDepthFrom(encodedRun);
    }

    private static RenderPipeline transferPipeline(GpuFormat format, boolean decode) {
        var bindings = BindGroupLayout.builder().withSampler("ParticleSceneSampler");
        if (decode) bindings.withSampler("ParticleCoverageSampler");
        String operation = decode ? "gamma_blend_to_linear" : "linear_to_gamma_blend";
        var builder = PhotonFullscreenPass.builder(LumenFx.id("post/translucency/" + operation))
                .withLocation(LumenFx.id("pipeline/" + operation + "/" + format.name().toLowerCase(Locale.ROOT)))
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(PhotonFullscreenPass.opaqueTarget(format));
        if (!decode) builder.withColorTargetState(1, new ColorTargetState(Optional.empty(), COVERAGE_FORMAT, 1));
        return builder.build();
    }

    private void releaseTargets() {
        if (coverage != null) coverage.close();
        if (encoded != null) encoded.destroyBuffers();
        coverage = null;
        encoded = null;
    }

    @Override public void close() {
        releaseTargets();
        encodePipelines.clear();
        decodePipelines.clear();
    }
}
