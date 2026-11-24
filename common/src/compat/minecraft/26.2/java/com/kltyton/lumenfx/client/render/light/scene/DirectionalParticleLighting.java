package com.kltyton.lumenfx.client.render.light.scene;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Shades opaque Niagara receivers with a directional light in source working space. */
public final class DirectionalParticleLighting {
    private static final String VIEW = "LfxDirectionalParticleView";
    private static final int CASCADE_LIMIT = 4;
    private record Variant(GpuFormat format, boolean zeroToOne, boolean shadows, boolean linear, boolean customData) { }
    private final Map<Variant, RenderPipeline> pipelines = new HashMap<>();

    public void draw(CommandEncoder encoder, RenderTarget target, OpaqueParticleSurfaces.Frame receivers,
                     Matrix4fc projection, Matrix4fc modelView, SceneDirectionalLight light,
                     DirectionalShadowRenderer.@Nullable Frame shadows, boolean linear) {
        var device = RenderSystem.getDevice().getDeviceInfo();
        var viewToWorld = modelView.invert(new Matrix4f());
        GpuBufferSlice view;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(176,
                device.limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            projection.get(0, bytes);
            viewToWorld.get(64, bytes);
            bytes.position(128);
            bytes.putFloat((float) light.direction().x).putFloat((float) light.direction().y)
                    .putFloat((float) light.direction().z).putFloat(light.sourceRadius());
            bytes.putFloat((float) light.color().x).putFloat((float) light.color().y)
                    .putFloat((float) light.color().z).putFloat(light.softSourceRadius());
            bytes.putFloat(light.diffuseScale()).putFloat(light.specularScale()).putFloat(light.lightingChannels()).putFloat(0f);
            view = mapped.slice();
        }
        var shadowData = shadows == null ? null : DirectionalShadowRenderer.uniforms(encoder, shadows, CASCADE_LIMIT);
        var depth = Objects.requireNonNull(target.getDepthTextureView());
        var customData = receivers.customData();
        var variant = new Variant(target.getColorTexture().getFormat(), device.isZZeroToOne(), shadows != null, linear, customData != null);
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX directional particle lighting")
                .withColorAttachment(target.getColorTextureView())
                .withRenderArea(new RenderPass.RenderArea(0, 0, target.width, target.height));
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipelines.computeIfAbsent(variant, DirectionalParticleLighting::pipeline));
            pass.setUniform(VIEW, view);
            pass.bindTexture("DirectionalParticleAlbedo", receivers.scene().albedo(), sampler);
            pass.bindTexture("DirectionalParticleNormal", receivers.scene().normalAmbient(), sampler);
            pass.bindTexture("DirectionalParticleGeometry", receivers.scene().positionDepth(), sampler);
            pass.bindTexture("DirectionalParticleSpecular", receivers.f0Roughness(), sampler);
            pass.bindTexture("DirectionalSceneDepth", depth, sampler);
            if (customData != null) pass.bindTexture("DirectionalParticleCustomData", customData, sampler);
            if (shadowData != null) DirectionalShadowRenderer.bind(pass, shadowData, shadows, depth);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static RenderPipeline pipeline(Variant variant) {
        var bindings = BindGroupLayout.builder().withUniform(VIEW, UniformType.UNIFORM_BUFFER)
                .withSampler("DirectionalParticleAlbedo").withSampler("DirectionalParticleNormal")
                .withSampler("DirectionalParticleGeometry").withSampler("DirectionalParticleSpecular")
                .withSampler("DirectionalSceneDepth");
        if (variant.customData()) bindings.withSampler("DirectionalParticleCustomData");
        if (variant.shadows()) DirectionalShadowRenderer.layout(bindings);
        return RenderPipeline.builder().withLocation(LumenFx.id("pipeline/particle_directional_light/"
                        + Integer.toUnsignedString(variant.hashCode())))
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withFragmentShader(LumenFx.id("post/lighting/particle_directional_light"))
                .withShaderDefine("LFX_DIRECTIONAL_PARTICLE_ZERO_TO_ONE", variant.zeroToOne() ? 1 : 0)
                .withShaderDefine("LFX_DIRECTIONAL_PARTICLE_SHADOWS", variant.shadows() ? 1 : 0)
                .withShaderDefine("LFX_DIRECTIONAL_PARTICLE_LINEAR", variant.linear() ? 1 : 0)
                .withShaderDefine("LFX_DIRECTIONAL_PARTICLE_CUSTOM", variant.customData() ? 1 : 0)
                .withShaderDefine("LFX_DIRECTIONAL_CASCADE_LIMIT", CASCADE_LIMIT)
                .withBindGroupLayout(bindings.build()).withCull(false).withDepthStencilState(Optional.empty())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), variant.format(), 7))
                .build();
    }

    public void clear() { pipelines.clear(); }
}
