package com.kltyton.lumenfx.client.render.light.toon;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.client.render.texture.ToonProfileBindings;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Accumulates component light irradiance and its weighted ramp position before resolving the Toon ramp. */
public final class ToonDiffuseAccumulation implements AutoCloseable {
    public static final ColorTargetState ADDITIVE_TARGET = new ColorTargetState(
            Optional.of(BlendFunction.ADDITIVE), GpuFormat.RGBA16_FLOAT, 7);

    public record Frame(GpuTextureView diffuse, GpuTextureView weight) {
        public void attach(RenderPassDescriptor descriptor) {
            descriptor.withColorAttachment(diffuse).withColorAttachment(weight);
        }
    }

    private record Targets(OwnedColorTarget diffuse, OwnedColorTarget weight) implements AutoCloseable {
        @Override
        public void close() {
            try { weight.close(); }
            finally { diffuse.close(); }
        }
    }

    private final Map<GpuFormat, RenderPipeline> pipelines = new HashMap<>();
    private @Nullable Targets targets;

    public Frame begin(CommandEncoder encoder, RenderTarget scene, boolean separateProfileDiffuse) {
        int attachments = separateProfileDiffuse ? 4 : 3;
        if (RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments() < attachments)
            throw new IllegalStateException("Unified Toon particle lighting requires " + attachments + " color attachments");
        Targets current = targets;
        if (current == null || scene.width != current.diffuse.width || scene.height != current.diffuse.height) {
            var diffuse = new OwnedColorTarget("LumenFX Toon accumulated diffuse", scene.width, scene.height, ADDITIVE_TARGET.format());
            try {
                var weight = new OwnedColorTarget("LumenFX Toon accumulated ramp weight", scene.width, scene.height, ADDITIVE_TARGET.format());
                targets = new Targets(diffuse, weight);
            } catch (RuntimeException | Error failure) {
                diffuse.closeOnFailure(failure);
                throw failure;
            }
            if (current != null) current.close();
            current = targets;
        }
        encoder.clearColorTexture(current.diffuse.getColorTexture(), new Vector4f());
        encoder.clearColorTexture(current.weight.getColorTexture(), new Vector4f());
        return new Frame(current.diffuse.getColorTextureView(), current.weight.getColorTextureView());
    }

    public void resolve(CommandEncoder encoder, RenderTarget scene, OpaqueParticleSurfaces.Frame material, Frame light) {
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX Toon unified diffuse")
                .withColorAttachment(Objects.requireNonNull(scene.getColorTextureView()))
                .withRenderArea(new RenderPass.RenderArea(0, 0, scene.width, scene.height));
        try (var pass = encoder.createRenderPass(descriptor)) {
            var format = scene.getColorTexture().getFormat();
            pass.setPipeline(pipelines.computeIfAbsent(format, ToonDiffuseAccumulation::pipeline));
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("ToonDiffuse", light.diffuse(), sampler);
            pass.bindTexture("ToonRampWeight", light.weight(), sampler);
            pass.bindTexture("ToonParticleGeometry", material.scene().positionDepth(), sampler);
            pass.bindTexture("ToonParticleCustom", Objects.requireNonNull(material.customData()), sampler);
            pass.bindTexture("ToonSceneDepth", Objects.requireNonNull(scene.getDepthTextureView()), sampler);
            ToonProfileBindings.bind(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static RenderPipeline pipeline(GpuFormat format) {
        var bindings = BindGroupLayout.builder().withSampler("ToonDiffuse").withSampler("ToonRampWeight")
                .withSampler("ToonParticleGeometry").withSampler("ToonParticleCustom").withSampler("ToonSceneDepth");
        ToonProfileBindings.layout(bindings);
        return RenderPipeline.builder().withLocation(LumenFx.id("pipeline/toon/diffuse/" + format.ordinal()))
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withFragmentShader(LumenFx.id("post/lighting/toon_unified_diffuse"))
                .withBindGroupLayout(bindings.build()).withCull(false).withDepthStencilState(Optional.empty())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), format, 7))
                .build();
    }

    @Override
    public void close() {
        if (targets != null) targets.close();
        targets = null;
        pipelines.clear();
    }
}
