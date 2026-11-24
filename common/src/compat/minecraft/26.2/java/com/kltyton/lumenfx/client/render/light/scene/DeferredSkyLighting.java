package com.kltyton.lumenfx.client.render.light.scene;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.light.ParticleLightRenderer;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import com.kltyton.lumenfx.client.render.texture.ToonProfileBindings;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting;
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
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Applies the source deferred sky environment to the current opaque Niagara receivers. */
public final class DeferredSkyLighting {
    private static final String VIEW = "LfxDeferredSkyView";
    private record Variant(GpuFormat format, boolean custom, boolean bottomNormal, boolean separateDiffuse,
                           boolean ao, boolean preserveEnergy, boolean conserveEnergy, boolean zeroToOne,
                           boolean staticLighting, boolean diffuse, boolean reflection, boolean preintegrated,
                           boolean source, boolean anisotropic, boolean reflectionEnergy, boolean blend,
                           boolean skyReflection, boolean normalizeSky, boolean localCaptures,
                           boolean toon, boolean toonReflectionEffects) { }

    private final Map<Variant, RenderPipeline> pipelines = new HashMap<>();

    public void clear() { pipelines.clear(); }

    public static boolean active(SceneIndirectLighting indirect) {
        return SceneIndirectDiffuse.deferredSky(indirect) != null || hasReflection(indirect);
    }

    public static boolean hasReflection(SceneIndirectLighting indirect) {
        return indirect.reflectionCaptures() != null || hasSkyReflection(indirect);
    }

    private static boolean hasSkyReflection(SceneIndirectLighting indirect) {
        var sky = indirect.skyLight();
        return sky != null && !sky.staticLighting() && SkyReflectionBindings.reflection(sky) != null;
    }

    public void draw(CommandEncoder encoder, RenderTarget target, OpaqueParticleSurfaces.Frame frame,
                     Matrix4fc projection, Matrix4fc modelView, SceneIndirectLighting indirect,
                     NiagaraPlatformEnvironment platform, @Nullable GpuTextureView ao, ParticleLightRenderer directLights,
                     LocalReflectionBindings.@Nullable Frame captures) {
        var sky = SceneIndirectDiffuse.deferredSky(indirect);
        boolean reflection = hasReflection(indirect);
        if (sky == null && !reflection) return;
        var device = RenderSystem.getDevice().getDeviceInfo();
        int alignment = device.limits().minUniformOffsetAlignment();
        boolean energy = enabled(platform, "r.Material.EnergyConservation");
        boolean preserve = energy && enabled(platform, "r.Shading.EnergyPreservation");
        boolean reflectionEnergy = energy && reflection;
        var custom = frame.customData();
        var sourceMaterial = frame.sourceMaterial();
        var anisotropy = reflection ? frame.tangentAnisotropy() : null;
        var subsurface = frame.subsurfaceDiffuse();
        boolean toon = frame.hasLightingFeature(MaterialDefinition.SUBSTRATE_TOON);
        boolean toonReflectionEffects = toon && reflection && enabled(platform, "r.Substrate.Experimental.ToonGIReflection");
        var reflections = hasSkyReflection(indirect)
                ? SkyReflectionBindings.prepare(encoder, indirect.skyLight(), platform, alignment) : null;
        var variant = new Variant(target.getColorTexture().getFormat(), custom != null, frame.bottomNormal(),
                subsurface != null, ao != null, preserve,
                energy && enabled(platform, "r.Shading.EnergyConservation"), device.isZZeroToOne(), platform.staticLightingAllowed(),
                sky != null, reflection, reflections != null && reflections.preintegrated() != null,
                sourceMaterial != null, anisotropy != null, reflectionEnergy, reflections != null && reflections.blendDestination() != null,
                reflections != null, reflections != null && reflections.normalize(), captures != null, toon, toonReflectionEffects);
        var sample = SceneIndirectDiffuse.prepareDeferredSky(encoder, indirect, platform, alignment);
        var table = directLights.prepareReflectionEnergy(encoder, preserve || reflectionEnergy);
        var tint = sky != null ? sky.occlusionTint() : Vec3.ZERO;
        GpuBufferSlice view;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(144, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            projection.get(0, bytes);
            modelView.invert(new Matrix4f()).get(64, bytes);
            bytes.position(128);
            bytes.putFloat((float)tint.x).putFloat((float)tint.y).putFloat((float)tint.z).putFloat(0);
            view = mapped.slice();
        }
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX deferred skylight")
                .withColorAttachment(Objects.requireNonNull(target.getColorTextureView()))
                .withRenderArea(new RenderPass.RenderArea(0, 0, target.width, target.height));
        if (subsurface != null) descriptor.withColorAttachment(subsurface);
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipelines.computeIfAbsent(variant, DeferredSkyLighting::pipeline));
            pass.setUniform(VIEW, view);
            sample.bind(pass);
            pass.bindTexture("SkyParticleAlbedo", frame.scene().albedo(), sampler);
            pass.bindTexture("SkyParticleNormal", frame.scene().normalAmbient(), sampler);
            pass.bindTexture("SkyParticleGeometry", frame.scene().positionDepth(), sampler);
            pass.bindTexture("SkyParticleLighting", frame.f0Roughness(), sampler);
            pass.bindTexture("SkySceneDepth", Objects.requireNonNull(target.getDepthTextureView()), sampler);
            if (custom != null) pass.bindTexture("SkyParticleCustom", custom, sampler);
            if (sourceMaterial != null) pass.bindTexture("SkyParticleSource", sourceMaterial, sampler);
            if (anisotropy != null) pass.bindTexture("SkyParticleAnisotropy", anisotropy, sampler);
            if (ao != null) pass.bindTexture("SkySceneAO", ao, sampler);
            if (reflections != null) reflections.bind(pass);
            if (captures != null) captures.bind(pass);
            if (toon) ToonProfileBindings.bind(pass);
            if (preserve || reflectionEnergy) ParticleLightRenderer.bindReflectionEnergy(pass, table);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static boolean enabled(NiagaraPlatformEnvironment platform, String console) {
        return Objects.requireNonNull(platform.console(console)).bits() != 0;
    }

    private static RenderPipeline pipeline(Variant variant) {
        var bindings = BindGroupLayout.builder().withUniform(VIEW, UniformType.UNIFORM_BUFFER)
                .withSampler("SkyParticleAlbedo").withSampler("SkyParticleNormal").withSampler("SkyParticleGeometry")
                .withSampler("SkyParticleLighting").withSampler("SkySceneDepth");
        SceneIndirectDiffuse.layout(bindings, false);
        if (variant.custom()) bindings.withSampler("SkyParticleCustom");
        if (variant.source()) bindings.withSampler("SkyParticleSource");
        if (variant.anisotropic()) bindings.withSampler("SkyParticleAnisotropy");
        if (variant.ao()) bindings.withSampler("SkySceneAO");
        if (variant.skyReflection()) SkyReflectionBindings.layout(bindings, variant.preintegrated(), variant.blend());
        if (variant.localCaptures()) LocalReflectionBindings.layout(bindings);
        if (variant.toon()) ToonProfileBindings.layout(bindings);
        if (variant.preserveEnergy() || variant.reflectionEnergy()) bindings.withSampler("LfxGgxReflectionEnergy");
        var builder = RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/deferred_sky_lighting/" + Integer.toUnsignedString(variant.hashCode())))
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withFragmentShader(LumenFx.id("post/lighting/deferred_sky_lighting"))
                .withShaderDefine("LFX_DEFERRED_SKY_CUSTOM", variant.custom() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_BOTTOM_NORMAL", variant.bottomNormal() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_SEPARATE_DIFFUSE", variant.separateDiffuse() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_AO", variant.ao() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_ZERO_TO_ONE", variant.zeroToOne() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_STATIC_LIGHTING", variant.staticLighting() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_DIFFUSE", variant.diffuse() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_REFLECTION", variant.reflection() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_CUBEMAP", variant.skyReflection() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_NORMALIZE", variant.normalizeSky() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_LOCAL_CAPTURES", variant.localCaptures() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_PREINTEGRATED_GF", variant.preintegrated() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_BLEND", variant.blend() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_SOURCE", variant.source() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_ANISOTROPY", variant.anisotropic() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_REFLECTION_ENERGY", variant.reflectionEnergy() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_SKY_TOON", variant.toon() ? 1 : 0)
                .withShaderDefine("LFX_DEFERRED_TOON_REFLECTION_EFFECTS", variant.toonReflectionEffects() ? 1 : 0)
                .withShaderDefine("LFX_LEGACY_ENERGY_CONSERVE", variant.conserveEnergy() ? 1 : 0)
                .withShaderDefine("LFX_LEGACY_ENERGY_PRESERVE", variant.preserveEnergy() ? 1 : 0)
                .withBindGroupLayout(bindings.build()).withCull(false).withDepthStencilState(Optional.empty())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), variant.format(), 7));
        if (variant.preserveEnergy() || variant.reflectionEnergy()) builder.withShaderDefine("LFX_REFLECTION_ENERGY");
        if (variant.separateDiffuse())
            builder.withColorTargetState(1, new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), GpuFormat.RGBA32_FLOAT, 7));
        return builder.build();
    }
}
