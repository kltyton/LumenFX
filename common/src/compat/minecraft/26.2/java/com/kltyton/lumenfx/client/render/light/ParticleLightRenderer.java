package com.kltyton.lumenfx.client.render.light;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.light.energy.RuntimeShadingEnergy;
import com.kltyton.lumenfx.client.render.light.toon.ToonDiffuseAccumulation;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import com.kltyton.lumenfx.client.render.scene.surface.adaptive.AdaptiveParticleBuffer;
import com.kltyton.lumenfx.client.render.decal.DecalBuffers;
import com.kltyton.lumenfx.client.render.decal.DecalReceiverView;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.client.render.light.scene.PointLightUniforms;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionAtlas;
import com.kltyton.lumenfx.client.render.light.shadow.contact.ContactShadowControls;
import com.kltyton.lumenfx.client.render.light.shadow.contact.ContactShadowView;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.energy.ShadingEnergyTable;
import com.kltyton.lumenfx.client.render.light.rect.RectLightLtcTables;
import com.kltyton.lumenfx.client.resource.texture.subsurface.SubsurfaceProfiles;
import com.kltyton.lumenfx.client.resource.texture.energy.SimpleVolumeLookup;
import com.kltyton.lumenfx.client.render.texture.SpecularProfileBindings;
import com.kltyton.lumenfx.client.render.texture.ToonProfileBindings;
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
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Particle-light shading using native diffuse or the shaderpack's captured material response. */
public final class ParticleLightRenderer implements AutoCloseable {
    private static final int LIGHT_VIEW_BYTES = 96;
    public static final String GGX_REFLECTION_SAMPLER = "LfxGgxReflectionEnergy";
    private static final String TOON_GGX_MAX_SAMPLER = "LfxToonGgxMaxEnergy";
    public static final String SHEEN_LTC_SAMPLER = "LfxSheenLtc";
    private static final String SUBSURFACE_COLOR_DISTANCE = "r.SSS.SubSurfaceColorAsTansmittanceAtDistance";

    private record SubstrateLighting(boolean slab, boolean eye, boolean conservation, boolean preservation,
                                     boolean accurate, boolean roughDiffuse, boolean fuzz, boolean wrap, boolean specularProfile,
                                     boolean adaptive, boolean volume, boolean diffusion) {
        static @Nullable SubstrateLighting forSurface(Inputs surface) {
            boolean slab = surface.hasFeature(MaterialDefinition.SUBSTRATE_SLAB);
            boolean eye = surface.hasFeature(MaterialDefinition.SUBSTRATE_EYE);
            if (!slab && !eye) return null;
            var settings = NiagaraPlatforms.snapshot();
            return new SubstrateLighting(slab, eye, Objects.requireNonNull(settings.console("r.Shading.EnergyConservation")).bits() > 0,
                    Objects.requireNonNull(settings.console("r.Shading.EnergyPreservation")).bits() > 0,
                    Objects.requireNonNull(settings.console("r.Substrate.ShadingQuality")).bits() > 0,
                    Objects.requireNonNull(settings.console("r.Material.RoughDiffuse")).bits() > 0
                            && Objects.requireNonNull(settings.console("r.Substrate.RoughDiffuse")).bits() > 0,
                    surface.hasFeature(MaterialDefinition.SUBSTRATE_FUZZ), surface.hasFeature(MaterialDefinition.SUBSTRATE_WRAP),
                    surface.hasFeature(MaterialDefinition.SUBSTRATE_SPECULAR_PROFILE), surface.adaptiveWords() != null,
                    surface.hasFeature(MaterialDefinition.SUBSTRATE_VOLUME), surface.hasFeature(MaterialDefinition.SUBSTRATE_DIFFUSION));
        }

        boolean energyTable() { return conservation || preservation; }
    }

    private record LegacyLighting(boolean energy, boolean conserve, boolean preserve, boolean roughDiffuse, boolean staticLighting) {
        static LegacyLighting forSurface(Inputs surface) {
            var settings = NiagaraPlatforms.snapshot();
            boolean energy = surface.particleMaterial()
                    && Objects.requireNonNull(settings.console("r.Material.EnergyConservation")).bits() != 0;
            return new LegacyLighting(energy, energy && Objects.requireNonNull(settings.console("r.Shading.EnergyConservation")).bits() > 0,
                    energy && Objects.requireNonNull(settings.console("r.Shading.EnergyPreservation")).bits() > 0,
                    surface.particleMaterial() && Objects.requireNonNull(settings.console("r.Material.RoughDiffuse")).bits() != 0,
                    surface.particleMaterial() && settings.staticLightingAllowed());
        }

        boolean energyTable() { return conserve || preserve; }
    }

    private record Key(GpuFormat format, int encoding, boolean host, @Nullable String specular, boolean particleMaterial, boolean decals,
                       boolean point, boolean ies, boolean attenuation, boolean contact, boolean substrateTrace, boolean anisotropy,
                       boolean customData, boolean bottomNormal, @Nullable SubstrateLighting substrate, boolean preintegratedSkin,
                       boolean hair, boolean legacyProfile, boolean separateProfileDiffuse, boolean irisNormal, LegacyLighting legacy,
                       boolean sceneAo, boolean rect, boolean rectTexture, boolean lightFunctionAtlas, boolean toon,
                       boolean unifiedToonDiffuse) {
        boolean reflectionEnergy() { return needsReflection(substrate, legacy); }
        boolean clothEnergy() { return customData && legacy.preserve(); }
        boolean subsurfaceProfiles() { return legacyProfile || substrate != null && substrate.diffusion(); }
        boolean nativeBasis() {
            return !host && (decals || anisotropy || bottomNormal || !point && legacyProfile
                    || substrate != null && (substrate.eye() || substrate.adaptive()));
        }
    }
    public record PointDraw(ScenePointLight light, @Nullable GpuTextureView attenuation,
                            boolean shadowsPrepared, boolean functionPrepared, @Nullable Runnable prepareAttenuation,
                            com.kltyton.lumenfx.client.render.light.shadow.PointShadowResolution.@Nullable Plan shadowPlan, float colorFade,
                            LightFunctionAtlas.@Nullable Sample lightFunctionAtlas) {
        public PointDraw(ScenePointLight light, @Nullable GpuTextureView attenuation, boolean shadowsPrepared,
                         boolean functionPrepared, @Nullable Runnable prepareAttenuation,
                         com.kltyton.lumenfx.client.render.light.shadow.PointShadowResolution.@Nullable Plan shadowPlan, float colorFade) {
            this(light, attenuation, shadowsPrepared, functionPrepared, prepareAttenuation, shadowPlan, colorFade, null);
        }
        public PointDraw(ScenePointLight light, @Nullable GpuTextureView attenuation, boolean shadowsPrepared,
                         boolean functionPrepared, @Nullable Runnable prepareAttenuation,
                         com.kltyton.lumenfx.client.render.light.shadow.PointShadowResolution.@Nullable Plan shadowPlan) {
            this(light, attenuation, shadowsPrepared, functionPrepared, prepareAttenuation, shadowPlan, 1);
        }
        public PointDraw(ScenePointLight light, @Nullable GpuTextureView attenuation,
                         boolean shadowsPrepared, boolean functionPrepared, @Nullable Runnable prepareAttenuation) {
            this(light, attenuation, shadowsPrepared, functionPrepared, prepareAttenuation, null);
        }
        public PointDraw(ScenePointLight light, @Nullable GpuTextureView attenuation) {
            this(light, attenuation, attenuation != null, attenuation != null, null);
        }
    }
    private record Inputs(GpuTextureView albedo, GpuTextureView normal, GpuTextureView position,
                          @Nullable GpuTextureView lighting, @Nullable Matrix4fc materialProjection,
                          @Nullable String specular, int width, int height, DecalBuffers.@Nullable Frame decals,
                          @Nullable GpuTextureView materialBase, @Nullable GpuTextureView anisotropy,
                          OpaqueParticleSurfaces.@Nullable Frame materialFrame, @Nullable GpuTextureView sceneAo) {
        boolean particleMaterial() { return materialProjection == null && lighting != null; }
        boolean hasLighting() { return lighting != null && (particleMaterial() || specular != null); }
        boolean customData() { return materialFrame != null && materialFrame.customData() != null; }
        boolean bottomNormal() { return materialFrame != null && materialFrame.bottomNormal(); }
        boolean hasFeature(int feature) { return materialFrame != null && materialFrame.hasLightingFeature(feature); }
        @Nullable GpuTextureView subsurfaceDiffuse() { return materialFrame == null ? null : materialFrame.subsurfaceDiffuse(); }
        @Nullable GpuTextureView adaptiveWords() {
            return materialFrame == null || materialFrame.adaptive() == null ? null : materialFrame.adaptive().sampled();
        }
    }
    private static final java.util.concurrent.atomic.AtomicLong NEXT = new java.util.concurrent.atomic.AtomicLong();
    private final ParticleLightBuffers buffers = new ParticleLightBuffers();
    private final ParticleLightBuffers pointBuffers = new ParticleLightBuffers();
    private final ToonDiffuseAccumulation toonDiffuse = new ToonDiffuseAccumulation();
    private @Nullable RuntimeShadingEnergy runtimeShadingEnergy;
    private final DecalReceiverView decalView = new DecalReceiverView();
    private final Map<Key, RenderPipeline> pipelines = new HashMap<>();
    private final ContactShadowControls contactControls;

    public ParticleLightRenderer() { this(ContactShadowControls.DEFAULT); }
    public ParticleLightRenderer(ContactShadowControls contactControls) { this.contactControls = Objects.requireNonNull(contactControls); }

    public void draw(List<ParticleLight> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                     Matrix4fc modelView, Vec3 camera, float eyeExposure, float preExposure, int encoding) {
        draw(lights, surface, target, projection, modelView, camera, eyeExposure, preExposure, encoding, null);
    }

    public void draw(List<ParticleLight> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                     Matrix4fc modelView, Vec3 camera, float eyeExposure, float preExposure, int encoding,
                     DecalBuffers.@Nullable Frame decals) {
        draw(lights, surface, target, projection, modelView, camera, eyeExposure, preExposure, encoding, decals, null);
    }

    public void draw(List<ParticleLight> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                     Matrix4fc modelView, Vec3 camera, float eyeExposure, float preExposure, int encoding,
                     DecalBuffers.@Nullable Frame decals, @Nullable GpuTextureView particleLighting) {
        draw(lights, surface, target, projection, modelView, camera, eyeExposure, preExposure, encoding, decals, particleLighting, null);
    }

    public void draw(List<ParticleLight> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                     Matrix4fc modelView, Vec3 camera, float eyeExposure, float preExposure, int encoding,
                     DecalBuffers.@Nullable Frame decals, @Nullable GpuTextureView particleLighting, @Nullable GpuTextureView anisotropy) {
        draw(lights, surface, target, projection, modelView, camera, eyeExposure, preExposure, encoding,
                decals, particleLighting, anisotropy, null);
    }

    public void draw(List<ParticleLight> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                     Matrix4fc modelView, Vec3 camera, float eyeExposure, float preExposure, int encoding,
                     DecalBuffers.@Nullable Frame decals, @Nullable GpuTextureView particleLighting,
                     @Nullable GpuTextureView anisotropy, OpaqueParticleSurfaces.@Nullable Frame materialFrame) {
        draw(lights, surface, target, projection, modelView, camera, eyeExposure, preExposure, encoding,
                decals, particleLighting, anisotropy, materialFrame, null);
    }

    public void draw(List<ParticleLight> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                     Matrix4fc modelView, Vec3 camera, float eyeExposure, float preExposure, int encoding,
                     DecalBuffers.@Nullable Frame decals, @Nullable GpuTextureView particleLighting,
                     @Nullable GpuTextureView anisotropy, OpaqueParticleSurfaces.@Nullable Frame materialFrame,
                     @Nullable GpuTextureView sceneAo) {
        draw(lights, new Inputs(surface.albedo(), surface.normalAmbient(), surface.positionDepth(), particleLighting, null, null,
                surface.width(), surface.height(), decals, decals == null ? null : java.util.Objects.requireNonNull(surface.materialResponse(),
                        "Native decal lighting requires captured material responses").base(), anisotropy, materialFrame, sceneAo),
                target, projection, modelView, camera, eyeExposure, preExposure, encoding);
    }

    public void draw(List<ParticleLight> lights, ShaderpackMaterialSurfaces surface, RenderTarget target,
                     Matrix4fc projection, Matrix4fc modelView, Vec3 camera, float eyeExposure, float preExposure, int encoding) {
        Matrix4fc materialProjection = surface.materialProjection();
        if (materialProjection == null) throw new IllegalArgumentException("Shaderpack lighting requires its logical material projection");
        if (!Float.isFinite(eyeExposure)) {
            if (lights.stream().anyMatch(light -> light.inverseExposureBlend() != 0))
                throw new IllegalArgumentException("Shaderpack exposure is unavailable for inverse-exposure particle lights");
            eyeExposure = 1;
        }
        draw(lights, new Inputs(surface.baseDepth(), surface.geometry(), surface.material(), surface.lightingParameters(),
                materialProjection, surface.specularSource(), surface.width(), surface.height(), null, null, null, null, null), target, projection,
                modelView, camera, eyeExposure, preExposure, encoding);
    }

    public void drawPoints(List<PointDraw> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                           Matrix4fc modelView, Matrix4fc viewToSourceWorld, Vec3 camera,
                           float eyeExposure, float preExposure, int encoding, DecalBuffers.@Nullable Frame decals) {
        drawPoints(lights, surface, target, projection, modelView, viewToSourceWorld, camera, eyeExposure, preExposure, encoding, decals, null);
    }

    public void drawPoints(List<PointDraw> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                           Matrix4fc modelView, Matrix4fc viewToSourceWorld, Vec3 camera, float eyeExposure,
                           float preExposure, int encoding, DecalBuffers.@Nullable Frame decals, @Nullable GpuTextureView particleLighting) {
        drawPoints(lights, surface, target, projection, modelView, viewToSourceWorld, camera, eyeExposure,
                preExposure, encoding, decals, particleLighting, null);
    }

    public void drawPoints(List<PointDraw> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                           Matrix4fc modelView, Matrix4fc viewToSourceWorld, Vec3 camera, float eyeExposure,
                           float preExposure, int encoding, DecalBuffers.@Nullable Frame decals, @Nullable GpuTextureView particleLighting,
                           @Nullable GpuTextureView anisotropy) {
        drawPoints(lights, surface, target, projection, modelView, viewToSourceWorld, camera, eyeExposure,
                preExposure, encoding, decals, particleLighting, anisotropy, null);
    }

    public void drawPoints(List<PointDraw> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                           Matrix4fc modelView, Matrix4fc viewToSourceWorld, Vec3 camera, float eyeExposure,
                           float preExposure, int encoding, DecalBuffers.@Nullable Frame decals, @Nullable GpuTextureView particleLighting,
                           @Nullable GpuTextureView anisotropy, OpaqueParticleSurfaces.@Nullable Frame materialFrame) {
        drawPoints(lights, surface, target, projection, modelView, viewToSourceWorld, camera, eyeExposure,
                preExposure, encoding, decals, particleLighting, anisotropy, materialFrame, null);
    }

    public void drawPoints(List<PointDraw> lights, Surfaces surface, RenderTarget target, Matrix4fc projection,
                           Matrix4fc modelView, Matrix4fc viewToSourceWorld, Vec3 camera, float eyeExposure,
                           float preExposure, int encoding, DecalBuffers.@Nullable Frame decals, @Nullable GpuTextureView particleLighting,
                           @Nullable GpuTextureView anisotropy, OpaqueParticleSurfaces.@Nullable Frame materialFrame,
                           @Nullable GpuTextureView sceneAo) {
        drawPoints(lights, new Inputs(surface.albedo(), surface.normalAmbient(), surface.positionDepth(), particleLighting, null, null,
                surface.width(), surface.height(), decals, decals == null ? null : Objects.requireNonNull(surface.materialResponse()).base(),
                anisotropy, materialFrame, sceneAo),
                target, projection, modelView, viewToSourceWorld, camera, eyeExposure, preExposure, encoding);
    }

    public void drawPoints(List<PointDraw> lights, ShaderpackMaterialSurfaces surface, RenderTarget target, Matrix4fc projection,
                           Matrix4fc modelView, Matrix4fc viewToSourceWorld, Vec3 camera,
                           float eyeExposure, float preExposure, int encoding) {
        if (surface.materialProjection() == null) throw new IllegalArgumentException("Shaderpack point lighting needs its material projection");
        if (!Float.isFinite(eyeExposure)) {
            if (lights.stream().anyMatch(draw -> draw.light().data().shader().inverseExposureBlend() != 0))
                throw new IllegalArgumentException("Shaderpack exposure is unavailable for inverse-exposure point lights");
            eyeExposure = 1;
        }
        drawPoints(lights, new Inputs(surface.baseDepth(), surface.geometry(), surface.material(), surface.lightingParameters(),
                surface.materialProjection(), surface.specularSource(), surface.width(), surface.height(), null, null, null, null, null),
                target, projection, modelView, viewToSourceWorld, camera, eyeExposure, preExposure, encoding);
    }

    private void drawPoints(List<PointDraw> drawing, Inputs surface, RenderTarget target, Matrix4fc projection,
                            Matrix4fc modelView, Matrix4fc viewToSourceWorld, Vec3 camera,
                            float eyeExposure, float preExposure, int encoding) {
        RenderSystem.assertOnRenderThread();
        if (drawing.isEmpty()) return;
        if (surface.width() != target.width || surface.height() != target.height || target.getDepthTextureView() == null)
            throw new IllegalArgumentException("Point lighting requires matching opaque surfaces and depth");
        if (surface.decals() != null && (surface.decals().width() != target.width || surface.decals().height() != target.height))
            throw new IllegalArgumentException("Point lighting and decal material dimensions differ");
        List<ParticleLight> common = new ArrayList<>(drawing.size());
        for (PointDraw draw : drawing) {
            ScenePointLight light = draw.light();
            var data = light.data(); var shader = data.shader(); var position = shader.worldPosition();
            boolean shadowsRequired = draw.shadowPlan() == null ? data.castDynamicShadow() || data.castStaticShadow() : draw.shadowPlan().required();
            if (shadowsRequired && (!draw.shadowsPrepared() || draw.attenuation() == null))
                throw new IllegalArgumentException("Point light requires its scene shadow attenuation surface: " + light.identity());
            if (light.lightFunctionMaterial() != null
                    && (!draw.functionPrepared() || draw.attenuation() == null && draw.lightFunctionAtlas() == null))
                throw new IllegalArgumentException("Point light requires its scene shadow/light-function attenuation surface: " + light.identity());
            if (light.iesTexture() != null && EffectTextures.get(light.iesTexture()) == null)
                throw new IllegalArgumentException("Point light IES texture was not prepared: " + light.iesTexture());
            Vec3 color = shader.color(); float fade = draw.colorFade();
            Vec3 viewColor = new Vec3((float)color.x * fade, (float)color.y * fade, (float)color.z * fade);
            common.add(new ParticleLight(light.identity(), 0, new Vec3(position.x * .01, position.z * .01, position.y * .01),
                    data.radius(), viewColor, shader.falloffExponent(), shader.inverseExposureBlend(), shader.affectsTranslucency(),
                    data.allowMegaLights(), data.castDynamicShadow(), data.volumetricScattering(), shader.specularScale(), shader.diffuseScale()));
        }
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        var batches = pointBuffers.uploadView(encoder, common, camera, modelView);
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        int stride = Math.multiplyExact(Math.ceilDiv(PointLightUniforms.BYTES, alignment), alignment);
        var pointWriter = new PointLightUniforms(viewToSourceWorld, camera, contactControls);
        GpuBufferSlice pointUniforms;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(Math.multiplyExact(stride, drawing.size()), alignment, GpuBuffer.USAGE_UNIFORM)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            int global = 0;
            for (var batch : batches) for (int local = 0; local < batch.lights(); local++, global++) {
                bytes.position(global * stride);
                var draw = drawing.get(global);
                pointWriter.write(bytes, draw.light(), local, -1, 0, draw.lightFunctionAtlas() != null);
            }
            pointUniforms = mapped.slice();
        }
        boolean host = surface.materialProjection() != null;
        boolean hair = surface.hasFeature(MaterialDefinition.LEGACY_HAIR | MaterialDefinition.SUBSTRATE_HAIR);
        boolean legacyProfile = surface.hasFeature(MaterialDefinition.LEGACY_SUBSURFACE_PROFILE);
        GpuBufferSlice contactView = drawing.stream().anyMatch(draw -> tracesContact(draw.light(), hair, legacyProfile))
                ? ContactShadowView.write(encoder, host ? Objects.requireNonNull(surface.materialProjection()) : projection,
                        viewToSourceWorld, host, device.getDeviceInfo().isZZeroToOne(),
                        com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.INSTANCE.outputFrameIndex()) : null;
        var decals = surface.decals();
        int materialOffset = Math.multiplyExact(Math.ceilDiv(LIGHT_VIEW_BYTES, alignment), alignment);
        var substrate = SubstrateLighting.forSurface(surface);
        boolean nativeBasis = !host && (decals != null || surface.anisotropy() != null || surface.bottomNormal()
                || substrate != null && (substrate.eye() || substrate.adaptive()));
        int receiverBytes = host ? 64 : nativeBasis ? DecalReceiverView.UNIFORM_BYTES : 0;
        GpuBufferSlice viewUniforms;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(receiverBytes == 0 ? LIGHT_VIEW_BYTES : materialOffset + receiverBytes,
                alignment, GpuBuffer.USAGE_UNIFORM)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            writeView(bytes, projection, target, eyeExposure, preExposure);
            if (host) new Matrix4f(surface.materialProjection()).invert().get(materialOffset, bytes);
            else if (nativeBasis) decalView.write(modelView, bytes, materialOffset);
            viewUniforms = mapped.slice();
        }
        int global = 0;
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var legacy = LegacyLighting.forSurface(surface);
        var reflection = prepareReflectionEnergy(encoder, needsReflection(substrate, legacy));
        var cloth = generatedEnergy(ShadingEnergyTable.Kind.SHEEN_LEGACY, surface.customData() && legacy.preserve(), encoder);
        boolean toon = surface.hasFeature(MaterialDefinition.SUBSTRATE_TOON);
        var toonGgxMax = generatedEnergy(ShadingEnergyTable.Kind.GGX_MAX_SPECULAR, toon, encoder);
        boolean unifiedToon = toon && Objects.requireNonNull(
                NiagaraPlatforms.snapshot().console("r.Substrate.Experimental.ToonUnifiedDiffuse")).bits() > 0;
        var accumulatedToon = unifiedToon ? toonDiffuse.begin(encoder, target, surface.subsurfaceDiffuse() != null) : null;
        boolean preintegratedSkin = surface.hasFeature(MaterialDefinition.PREINTEGRATED_SKIN);
        boolean irisNormal = legacyProfile
                && Objects.requireNonNull(NiagaraPlatforms.snapshot().console("r.IrisNormal")).bits() != 0;
        for (var batch : batches) for (int local = 0; local < batch.lights(); local++, global++) {
            PointDraw draw = drawing.get(global);
            if (draw.prepareAttenuation() != null) draw.prepareAttenuation().run();
            var atlas = draw.lightFunctionAtlas();
            var atlasUniforms = atlas == null ? null : atlas.uniforms(encoder);
            try (var pass = lightPass(encoder, target, surface, accumulatedToon, () -> "LumenFX component point light")) {
                var light = draw.light();
                var rect = light.data().shader().rect();
                var key = new Key(target.getColorTexture().getFormat(), encoding, host,
                        surface.lighting() == null ? null : surface.specular(), surface.particleMaterial(), decals != null, true,
                        light.iesTexture() != null, draw.attenuation() != null, tracesContact(light, hair, legacyProfile),
                        contactControls.substrateTrace(), surface.anisotropy() != null, surface.customData(), surface.bottomNormal(), substrate,
                        preintegratedSkin, hair, legacyProfile, surface.subsurfaceDiffuse() != null, irisNormal, legacy,
                        surface.sceneAo() != null, rect != null, rect != null && rect.texture() != null, atlas != null, toon, unifiedToon);
                pass.setPipeline(pipelines.computeIfAbsent(key, ParticleLightRenderer::pipeline));
                pass.setUniform("ParticleLightView", viewUniforms.slice(0, LIGHT_VIEW_BYTES));
                pass.setUniform("PointLightComponent", pointUniforms.slice((long)global * stride, PointLightUniforms.BYTES));
                if (key.contact()) pass.setUniform("PointContactView", Objects.requireNonNull(contactView));
                if (host) pass.setUniform("HostMaterialView", viewUniforms.slice(materialOffset, 64));
                else if (nativeBasis) pass.setUniform("DecalReceiverView", viewUniforms.slice(materialOffset, DecalReceiverView.UNIFORM_BYTES));
                pass.bindTexture("SurfaceAlbedo", surface.albedo(), nearest); pass.bindTexture("SurfaceNormal", surface.normal(), nearest);
                pass.bindTexture("SurfacePosition", surface.position(), nearest); pass.bindTexture("SceneDepth", target.getDepthTextureView(), nearest);
                if (surface.hasLighting()) pass.bindTexture("SurfaceLighting", Objects.requireNonNull(surface.lighting()), nearest);
                if (key.sceneAo()) pass.bindTexture("ScreenSpaceAo", Objects.requireNonNull(surface.sceneAo()), nearest);
                if (key.anisotropy()) pass.bindTexture("SurfaceAnisotropy", Objects.requireNonNull(surface.anisotropy()), nearest);
                if (key.customData()) {
                    var material = Objects.requireNonNull(surface.materialFrame());
                    pass.bindTexture("SurfaceCustomData", Objects.requireNonNull(material.customData()), nearest);
                    pass.bindTexture("SurfaceSourceMaterial", Objects.requireNonNull(material.sourceMaterial()), nearest);
                }
                bindEnergy(pass, key, reflection, cloth, toonGgxMax);
                if (preintegratedSkin) bindPreintegratedSkin(pass);
                if (key.subsurfaceProfiles()) bindSubsurfaceProfiles(pass);
                SpecularProfileBindings.bind(pass, substrate != null && substrate.specularProfile());
                bindAdaptiveSlabs(pass, substrate, surface, nearest);
                if (decals != null) {
                    pass.bindTexture("LfxDBufferColor", decals.color(), nearest); pass.bindTexture("LfxDBufferNormal", decals.normal(), nearest);
                    pass.bindTexture("NativeMaterialBase", Objects.requireNonNull(surface.materialBase()), nearest);
                }
                if (light.iesTexture() != null) pass.bindTexture("PointIESProfile", Objects.requireNonNull(EffectTextures.get(light.iesTexture())).view(), linear);
                if (key.rect()) {
                    RectLightLtcTables.resolve().bind(pass);
                    if (rect != null && rect.texture() != null) {
                        var source = Objects.requireNonNull(EffectTextures.get(rect.texture()), "Rect light source texture was not prepared");
                        pass.bindTexture("LfxRectSourceTexture", source.view(), source.sampler());
                    }
                }
                if (draw.attenuation() != null) pass.bindTexture("PointAttenuation", draw.attenuation(), nearest);
                if (atlas != null) atlas.bind(pass, Objects.requireNonNull(atlasUniforms));
                pass.setUniform("ParticleLights", batch.buffer());
                pass.draw(6, 1, 0, 0);
            }
        }
        if (accumulatedToon != null) toonDiffuse.resolve(encoder, target, Objects.requireNonNull(surface.materialFrame()), accumulatedToon);
    }

    private void draw(List<ParticleLight> lights, Inputs surface, RenderTarget target, Matrix4fc projection,
                      Matrix4fc modelView, Vec3 camera, float eyeExposure, float preExposure, int encoding) {
        RenderSystem.assertOnRenderThread();
        if (lights.isEmpty()) return;
        if (surface.width() != target.width || surface.height() != target.height || target.getDepthTextureView() == null)
            throw new IllegalArgumentException("Particle lighting requires matching opaque surface and scene depth dimensions");
        var decals = surface.decals();
        if (decals != null && (decals.width() != target.width || decals.height() != target.height))
            throw new IllegalArgumentException("Particle lighting and decal material dimensions differ");
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        var batches = buffers.uploadView(encoder, lights, camera, modelView);
        var substrate = SubstrateLighting.forSurface(surface);
        var legacy = LegacyLighting.forSurface(surface);
        var reflection = prepareReflectionEnergy(encoder, needsReflection(substrate, legacy));
        var cloth = generatedEnergy(ShadingEnergyTable.Kind.SHEEN_LEGACY, surface.customData() && legacy.preserve(), encoder);
        boolean toon = surface.hasFeature(MaterialDefinition.SUBSTRATE_TOON);
        var toonGgxMax = generatedEnergy(ShadingEnergyTable.Kind.GGX_MAX_SPECULAR, toon, encoder);
        boolean preintegratedSkin = surface.hasFeature(MaterialDefinition.PREINTEGRATED_SKIN);
        boolean hair = surface.hasFeature(MaterialDefinition.LEGACY_HAIR | MaterialDefinition.SUBSTRATE_HAIR);
        boolean legacyProfile = surface.hasFeature(MaterialDefinition.LEGACY_SUBSURFACE_PROFILE);
        boolean irisNormal = legacyProfile
                && Objects.requireNonNull(NiagaraPlatforms.snapshot().console("r.IrisNormal")).bits() != 0;
        boolean contact = contactControls.enabled() && (hair || legacyProfile);
        var key = new Key(target.getColorTexture().getFormat(), encoding,
                surface.materialProjection() != null, surface.lighting() == null ? null : surface.specular(),
                surface.particleMaterial(), decals != null, false, false, false, contact, contactControls.substrateTrace(),
                surface.anisotropy() != null, surface.customData(), surface.bottomNormal(), substrate, preintegratedSkin,
                hair, legacyProfile, surface.subsurfaceDiffuse() != null, irisNormal, legacy, surface.sceneAo() != null, false, false, false, toon, false);
        var pipeline = pipelines.computeIfAbsent(key, ParticleLightRenderer::pipeline);
        var contactView = contact ? ContactShadowView.write(encoder, projection, new Matrix4f().scaling(100f), false,
                device.getDeviceInfo().isZZeroToOne(), WorldHdrCapture.INSTANCE.outputFrameIndex()) : null;
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        boolean host = surface.materialProjection() != null;
        int materialOffset = Math.multiplyExact(Math.ceilDiv(LIGHT_VIEW_BYTES, alignment), alignment);
        boolean nativeBasis = key.nativeBasis();
        int receiverBytes = host ? 64 : nativeBasis ? DecalReceiverView.UNIFORM_BYTES : 0;
        GpuBufferSlice uniforms;
        try (var view = encoder.transientMemory().allocateGpuMapped(receiverBytes == 0 ? LIGHT_VIEW_BYTES : materialOffset + receiverBytes,
                alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = view.data().order(ByteOrder.nativeOrder());
            writeView(data, projection, target, eyeExposure, preExposure);
            if (host) new Matrix4f(surface.materialProjection()).invert().get(materialOffset, data);
            else if (nativeBasis) {
                decalView.write(modelView, data, materialOffset);
            }
            uniforms = view.slice();
        }
        try (var pass = lightPass(encoder, target, surface, null, () -> "LumenFX particle diffuse lights")) {
            pass.setPipeline(pipeline); pass.setUniform("ParticleLightView", uniforms.slice(0, LIGHT_VIEW_BYTES));
            if (contactView != null) pass.setUniform("PointContactView", contactView);
            if (host) pass.setUniform("HostMaterialView", uniforms.slice(materialOffset, 64));
            else if (nativeBasis) pass.setUniform("DecalReceiverView", uniforms.slice(materialOffset, DecalReceiverView.UNIFORM_BYTES));
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("SurfaceAlbedo", surface.albedo(), sampler); pass.bindTexture("SurfaceNormal", surface.normal(), sampler);
            pass.bindTexture("SurfacePosition", surface.position(), sampler); pass.bindTexture("SceneDepth", target.getDepthTextureView(), sampler);
            if (surface.hasLighting()) pass.bindTexture("SurfaceLighting", Objects.requireNonNull(surface.lighting()), sampler);
            if (key.sceneAo()) pass.bindTexture("ScreenSpaceAo", Objects.requireNonNull(surface.sceneAo()), sampler);
            if (surface.anisotropy() != null) pass.bindTexture("SurfaceAnisotropy", surface.anisotropy(), sampler);
            if (surface.customData()) {
                var material = Objects.requireNonNull(surface.materialFrame());
                pass.bindTexture("SurfaceCustomData", Objects.requireNonNull(material.customData()), sampler);
                pass.bindTexture("SurfaceSourceMaterial", Objects.requireNonNull(material.sourceMaterial()), sampler);
            }
            bindEnergy(pass, key, reflection, cloth, toonGgxMax);
            if (preintegratedSkin) bindPreintegratedSkin(pass);
            if (key.subsurfaceProfiles()) bindSubsurfaceProfiles(pass);
            SpecularProfileBindings.bind(pass, substrate != null && substrate.specularProfile());
            bindAdaptiveSlabs(pass, substrate, surface, sampler);
            if (decals != null) {
                pass.bindTexture("LfxDBufferColor", decals.color(), sampler);
                pass.bindTexture("LfxDBufferNormal", decals.normal(), sampler);
                pass.bindTexture("NativeMaterialBase", java.util.Objects.requireNonNull(surface.materialBase()), sampler);
            }
            for (var batch : batches) { pass.setUniform("ParticleLights", batch.buffer()); pass.draw(6, batch.lights(), 0, 0); }
        }
    }

    private boolean tracesContact(ScenePointLight light, boolean hair, boolean legacyProfile) {
        return contactControls.traces(light)
                || contactControls.enabled() && (legacyProfile || hair && contactControls.shadowedBits(light) < 2);
    }

    private static boolean needsReflection(@Nullable SubstrateLighting substrate, LegacyLighting legacy) {
        return legacy.energyTable() || substrate != null && substrate.energyTable();
    }

    public @Nullable GpuTextureView prepareReflectionEnergy(CommandEncoder encoder, boolean needed) {
        return generatedEnergy(ShadingEnergyTable.Kind.GGX_REFLECTION, needed, encoder);
    }

    public static void bindReflectionEnergy(RenderPass pass, @Nullable GpuTextureView reflection) {
        bindTable(pass, GGX_REFLECTION_SAMPLER, ShadingEnergyTable.Kind.GGX_REFLECTION, reflection);
    }

    public static void bindSheenLtc(RenderPass pass) {
        bindTable(pass, SHEEN_LTC_SAMPLER, ShadingEnergyTable.Kind.SHEEN_LTC, null);
    }

    private @Nullable GpuTextureView generatedEnergy(ShadingEnergyTable.Kind kind, boolean needed, CommandEncoder encoder) {
        if (!needed || !RuntimeShadingEnergy.enabled()) return null;
        if (runtimeShadingEnergy == null)
            runtimeShadingEnergy = new RuntimeShadingEnergy(Objects.requireNonNull(VfxEngine.INSTANCE.shadingEnergyShaders(),
                    "Runtime shading energy requires the pinned shader bundle"));
        return runtimeShadingEnergy.texture(kind, encoder,
                WorldHdrCapture.INSTANCE.renderFrameSequence()).sampledView();
    }

    private static void bindEnergy(RenderPass pass, Key key, @Nullable GpuTextureView reflection, @Nullable GpuTextureView cloth,
                                   @Nullable GpuTextureView toonGgxMax) {
        if (key.reflectionEnergy()) bindReflectionEnergy(pass, reflection);
        if (key.clothEnergy()) bindTable(pass, "LfxLegacyClothEnergy", ShadingEnergyTable.Kind.SHEEN_LEGACY, cloth);
        if (key.substrate() != null && key.substrate().fuzz()) bindSheenLtc(pass);
        if (key.toon()) {
            ToonProfileBindings.bind(pass);
            bindTable(pass, TOON_GGX_MAX_SAMPLER, ShadingEnergyTable.Kind.GGX_MAX_SPECULAR, toonGgxMax);
        }
    }

    private static void bindTable(RenderPass pass, String sampler, ShadingEnergyTable.Kind kind, @Nullable GpuTextureView generated) {
        if (generated != null) {
            pass.bindTexture(sampler, generated, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        } else {
            var id = kind.resource(LumenFx.MOD_ID);
            var table = EffectTextures.get(id);
            if (table == null) throw new IllegalStateException("Native material lighting requires shading table " + id);
            pass.bindTexture(sampler, table.view(), table.sampler());
        }
    }

    private static void writeView(ByteBuffer data, Matrix4fc projection, RenderTarget target, float eyeExposure, float preExposure) {
        float distance = Float.intBitsToFloat(Objects.requireNonNull(NiagaraPlatforms.snapshot().console(SUBSURFACE_COLOR_DISTANCE)).bits());
        projection.get(0, data);
        data.position(64);
        data.putFloat(target.width).putFloat(target.height).putFloat(eyeExposure).putFloat(preExposure);
        data.putFloat(Math.clamp(distance, 0.05f, 1f)).putFloat(0).putFloat(0).putFloat(0);
    }

    private static void bindPreintegratedSkin(RenderPass pass) {
        var lookup = Objects.requireNonNull(EffectTextures.get(ShadingEnergyTable.PREINTEGRATED_SKIN),
                "Preintegrated Skin BRDF was not prepared");
        pass.bindTexture("LfxPreintegratedSkin", lookup.view(), lookup.sampler());
    }

    public static void bindSubsurfaceProfiles(RenderPass pass) {
        var profiles = Objects.requireNonNull(EffectTextures.get(SubsurfaceProfiles.ATLAS), "Native subsurface profiles were not prepared");
        pass.bindTexture("LfxSubsurfaceProfiles", profiles.view(), profiles.sampler());
    }

    private static void bindAdaptiveSlabs(RenderPass pass, @Nullable SubstrateLighting substrate, Inputs surface, GpuSampler nearest) {
        if (substrate == null || !substrate.adaptive()) return;
        pass.bindTexture(AdaptiveParticleBuffer.SAMPLER, Objects.requireNonNull(surface.adaptiveWords()), nearest);
        if (substrate.volume()) {
            var lookup = Objects.requireNonNull(EffectTextures.get(SimpleVolumeLookup.PUNCTUAL),
                    "Adaptive Substrate requires the native Simple Volume punctual LUT");
            pass.bindTexture("LfxSlabPunctualVolume", lookup.view(), lookup.sampler());
        }
    }

    private static RenderPass lightPass(CommandEncoder encoder, RenderTarget scene, Inputs surface,
                                        ToonDiffuseAccumulation.@Nullable Frame toon, Supplier<String> label) {
        var descriptor = RenderPassDescriptor.create(label).withColorAttachment(scene.getColorTextureView())
                .withRenderArea(new RenderPass.RenderArea(0, 0, scene.width, scene.height));
        if (surface.subsurfaceDiffuse() != null) descriptor.withColorAttachment(surface.subsurfaceDiffuse());
        if (toon != null) toon.attach(descriptor);
        return encoder.createRenderPass(descriptor);
    }

    private static RenderPipeline pipeline(Key key) {
        var bindings = BindGroupLayout.builder().withUniform("ParticleLightView", UniformType.UNIFORM_BUFFER)
                .withUniform("ParticleLights", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
                .withSampler("SurfaceAlbedo").withSampler("SurfaceNormal").withSampler("SurfacePosition").withSampler("SceneDepth");
        if (key.host()) bindings.withUniform("HostMaterialView", UniformType.UNIFORM_BUFFER);
        if (key.specular() != null || key.particleMaterial()) bindings.withSampler("SurfaceLighting");
        if (key.nativeBasis()) bindings.withUniform("DecalReceiverView", UniformType.UNIFORM_BUFFER);
        if (key.decals()) bindings.withSampler("LfxDBufferColor").withSampler("LfxDBufferNormal").withSampler("NativeMaterialBase");
        if (key.anisotropy()) bindings.withSampler("SurfaceAnisotropy");
        if (key.customData()) bindings.withSampler("SurfaceCustomData").withSampler("SurfaceSourceMaterial");
        if (key.reflectionEnergy()) bindings.withSampler(GGX_REFLECTION_SAMPLER);
        if (key.clothEnergy()) bindings.withSampler("LfxLegacyClothEnergy");
        if (key.substrate() != null && key.substrate().fuzz()) bindings.withSampler(SHEEN_LTC_SAMPLER);
        SpecularProfileBindings.layout(bindings, key.substrate() != null && key.substrate().specularProfile());
        if (key.toon()) {
            ToonProfileBindings.layout(bindings);
            bindings.withSampler(TOON_GGX_MAX_SAMPLER);
        }
        if (key.substrate() != null && key.substrate().adaptive()) bindings.withSampler(AdaptiveParticleBuffer.SAMPLER);
        if (key.substrate() != null && key.substrate().volume()) bindings.withSampler("LfxSlabPunctualVolume");
        if (key.preintegratedSkin()) bindings.withSampler("LfxPreintegratedSkin");
        if (key.subsurfaceProfiles()) bindings.withSampler("LfxSubsurfaceProfiles");
        if (key.sceneAo()) bindings.withSampler("ScreenSpaceAo");
        if (key.point()) bindings.withUniform("PointLightComponent", UniformType.UNIFORM_BUFFER);
        if (key.rect()) RectLightLtcTables.layout(bindings);
        if (key.rectTexture()) bindings.withSampler("LfxRectSourceTexture");
        if (key.lightFunctionAtlas()) LightFunctionAtlas.Sample.layout(bindings);
        if (key.ies()) bindings.withSampler("PointIESProfile");
        if (key.attenuation()) bindings.withSampler("PointAttenuation");
        if (key.contact()) bindings.withUniform("PointContactView", UniformType.UNIFORM_BUFFER);
        var builder = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/particle_light/" + NEXT.getAndIncrement()))
                .withVertexShader(LumenFx.id("core/particle_light_volume")).withFragmentShader(LumenFx.id("post/particle_light"))
                .withShaderDefine("LFX_WORLD_ENCODING", key.encoding()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withDepthStencilState(Optional.empty()).withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), key.format(), 7))
                .withBindGroupLayout(bindings.build());
        if (key.host()) builder.withShaderDefine("LFX_HOST_MATERIAL");
        else builder.withShaderDefine("LFX_LIGHT_ZERO_TO_ONE", RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
        if (key.rect()) builder.withShaderDefine("LFX_RECT_LIGHT");
        if (key.rectTexture()) builder.withShaderDefine("LFX_RECT_LIGHT_TEXTURE");
        if (key.lightFunctionAtlas()) builder.withShaderDefine("LFX_LOCAL_LIGHT_FUNCTION");
        if (key.specular() != null) builder.withShaderDefine("LFX_HOST_SPECULAR");
        if (key.particleMaterial()) {
            builder.withShaderDefine("LFX_NATIVE_PARTICLE_MATERIAL")
                    .withShaderDefine("LFX_LEGACY_ENERGY", key.legacy().energy() ? 1 : 0)
                    .withShaderDefine("LFX_LEGACY_ENERGY_CONSERVE", key.legacy().conserve() ? 1 : 0)
                    .withShaderDefine("LFX_LEGACY_ENERGY_PRESERVE", key.legacy().preserve() ? 1 : 0)
                    .withShaderDefine("LFX_LEGACY_STATIC_LIGHTING", key.legacy().staticLighting() ? 1 : 0)
                    .withShaderDefine("LFX_LEGACY_ROUGH_DIFFUSE", key.legacy().roughDiffuse() ? 1 : 0);
            if (key.reflectionEnergy()) builder.withShaderDefine("LFX_REFLECTION_ENERGY");
            if (key.clothEnergy()) builder.withShaderDefine("LFX_LEGACY_CLOTH_ENERGY");
        }
        if (key.anisotropy()) builder.withShaderDefine("LFX_NATIVE_PARTICLE_ANISOTROPY");
        if (key.customData()) builder.withShaderDefine("LFX_NATIVE_PARTICLE_CUSTOM_DATA");
        if (key.preintegratedSkin()) builder.withShaderDefine("LFX_NATIVE_PREINTEGRATED_SKIN");
        if (key.hair()) builder.withShaderDefine("LFX_NATIVE_PARTICLE_HAIR");
        if (key.toon()) builder.withShaderDefine("LFX_NATIVE_PARTICLE_TOON");
        if (key.legacyProfile()) builder.withShaderDefine("LFX_NATIVE_LEGACY_SUBSURFACE_PROFILE")
                .withShaderDefine("LFX_LEGACY_IRIS_NORMAL", key.irisNormal() ? 1 : 0);
        if (key.separateProfileDiffuse()) {
            builder.withShaderDefine("LFX_SEPARATE_PROFILE_DIFFUSE");
            builder.withColorTargetState(1, new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), GpuFormat.RGBA32_FLOAT, 7));
        }
        if (key.unifiedToonDiffuse()) {
            int diffuseAttachment = key.separateProfileDiffuse() ? 2 : 1;
            builder.withShaderDefine("LFX_TOON_UNIFIED_DIFFUSE_TARGET", diffuseAttachment)
                    .withShaderDefine("LFX_TOON_UNIFIED_WEIGHT_TARGET", diffuseAttachment + 1)
                    .withColorTargetState(diffuseAttachment, ToonDiffuseAccumulation.ADDITIVE_TARGET)
                    .withColorTargetState(diffuseAttachment + 1, ToonDiffuseAccumulation.ADDITIVE_TARGET);
        }
        if (key.substrate() != null) {
            var substrate = key.substrate();
            if (substrate.slab()) builder.withShaderDefine("LFX_NATIVE_PARTICLE_SUBSTRATE");
            if (substrate.eye()) builder.withShaderDefine("LFX_NATIVE_PARTICLE_SUBSTRATE_EYE");
            builder.withShaderDefine("LFX_SUBSTRATE_CONSERVATION", substrate.conservation() ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_PRESERVATION", substrate.preservation() ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_ACCURATE", substrate.accurate() ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_ROUGH_DIFFUSE", substrate.roughDiffuse() ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_FUZZ", substrate.fuzz() ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_WRAP", substrate.wrap() ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_SPECULAR_PROFILE", substrate.specularProfile() ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_ADAPTIVE_LIGHT", substrate.adaptive() ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_VOLUME", substrate.volume() ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_DIFFUSION", substrate.diffusion() ? 1 : 0);
        }
        if (key.bottomNormal()) builder.withShaderDefine("LFX_CLEAR_COAT_BOTTOM_NORMAL");
        if (key.decals()) builder.withShaderDefine("LFX_DECAL_RECEIVER");
        if (key.point()) builder.withShaderDefine("LFX_POINT_COMPONENT");
        if (key.sceneAo()) builder.withShaderDefine("LFX_SCREEN_SPACE_AO");
        if (key.ies()) builder.withShaderDefine("LFX_POINT_IES");
        if (key.attenuation()) builder.withShaderDefine("LFX_POINT_ATTENUATION");
        if (key.contact()) builder.withShaderDefine("LFX_POINT_CONTACT");
        if (key.contact() && key.substrateTrace()) builder.withShaderDefine("LFX_CONTACT_SUBSTRATE_TRACE");
        RenderPipeline pipeline = builder.build();
        if (key.specular() != null) {
            var manager = Minecraft.getInstance().getShaderManager();
            String fragment = manager.getShader(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
            if (fragment == null) throw new IllegalArgumentException("Missing particle light fragment source");
            int insertion = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.header(fragment).insertionIndex();
            String resolved = fragment.substring(0, insertion) + "\n" + key.specular() + "\n" + fragment.substring(insertion);
            if (!RenderSystem.getDevice().precompilePipeline(pipeline, (id, type) ->
                    id.equals(pipeline.getFragmentShader()) && type == ShaderType.FRAGMENT ? resolved : manager.getShader(id, type)).isValid())
                throw new IllegalArgumentException("Host particle light shader failed to compile");
        }
        return pipeline;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        buffers.close(); pointBuffers.close(); toonDiffuse.close(); pipelines.clear();
        if (runtimeShadingEnergy != null) { runtimeShadingEnergy.close(); runtimeShadingEnergy = null; }
    }
}
