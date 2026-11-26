package com.kltyton.lumenfx.client.render;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.light.subsurface.LegacySubsurfaceScattering;

import com.kltyton.lumenfx.client.render.texture.virtual.MaterialVirtualTextureBindings;
import com.kltyton.lumenfx.client.render.texture.SpecularProfileBindings;
import com.kltyton.lumenfx.client.render.texture.ToonProfileBindings;
import com.kltyton.lumenfx.client.render.scene.surface.adaptive.AdaptiveParticleBuffer;
import com.kltyton.lumenfx.client.render.scene.surface.adaptive.AdaptiveParticleReplayPipelines;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead;

import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.ParticleCasters;
import com.kltyton.lumenfx.client.render.light.shadow.ShadowRasterViews;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowCaster;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowPipelines;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowPipelines.Winding;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.SpotShadowCaster;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.PointShadowParticleView;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.mojang.blaze3d.ProjectionType;
import com.kltyton.lumenfx.effect.render.SpriteCoverage;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
import com.kltyton.lumenfx.client.render.decal.ParticleDecalDepth;
import com.kltyton.lumenfx.client.render.depth.OpaqueParticleDepthPass;
import com.kltyton.lumenfx.client.render.depth.ParticleCustomDepthPipelines;
import com.kltyton.lumenfx.client.render.depth.ParticleCustomDepthTargets;
import com.kltyton.lumenfx.client.render.depth.SceneDepthDefaults;
import com.kltyton.lumenfx.client.render.light.ParticleLightRenderer.PointDraw;
import com.kltyton.lumenfx.client.render.light.function.PointLightFunctionRenderer;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting;
import com.kltyton.lumenfx.client.render.light.scene.SceneIndirectDiffuse;
import com.kltyton.lumenfx.client.render.light.scene.DeferredSkyLighting;
import com.kltyton.lumenfx.client.render.light.scene.DirectionalParticleLighting;
import com.kltyton.lumenfx.client.render.light.scene.LocalReflectionBindings;
import com.kltyton.lumenfx.client.render.light.ParticleLightRenderer;
import com.kltyton.lumenfx.client.render.light.scene.PointLightProjection;
import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveFrame;
import com.kltyton.lumenfx.effect.render.light.PrimitiveLighting;
import com.mojang.blaze3d.textures.GpuTextureView;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.RenderSettings;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding;
import com.kltyton.lumenfx.client.render.scene.material.MaterialInstanceUniforms;
import com.kltyton.lumenfx.client.render.scene.material.MaterialCollectionUniforms;
import com.kltyton.lumenfx.effect.render.MaterialDepth;
import com.kltyton.lumenfx.effect.render.MaterialFog;
import com.kltyton.lumenfx.effect.render.TranslucencyPass;
import com.kltyton.lumenfx.client.render.material.blend.ParticleDualSourceBlend;
import com.kltyton.lumenfx.client.render.material.blend.GammaParticleBlendTargets;
import com.kltyton.lumenfx.effect.render.vertex.VertexMaterialSettings;
import com.kltyton.lumenfx.effect.render.decal.DBufferTexture;
import com.kltyton.lumenfx.effect.render.fog.ExponentialHeightFog;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AerialPerspectiveBinding;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialLighting;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialTextures;
import com.kltyton.lumenfx.client.render.fog.HeightFogUniforms;
import com.kltyton.lumenfx.client.render.fog.FogView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRibbonFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs;
import com.lowdragmc.photon.client.render.ParticleRasterPass;
import com.lowdragmc.photon.client.render.PhotonRenderOutput;
import com.lowdragmc.photon.client.render.PhotonUploads;
import com.lowdragmc.photon.client.render.PremultipliedBlendPlan;
import com.kltyton.lumenfx.client.render.mesh.MeshRendererUniforms;
import com.kltyton.lumenfx.client.render.mesh.MeshGeometryCache;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawChunk;
import com.kltyton.lumenfx.client.render.mesh.nanite.NaniteMaterialGeometry;
import com.kltyton.lumenfx.client.render.mesh.nanite.NaniteWorldRenderer;
import com.kltyton.lumenfx.client.render.mesh.nanite.NaniteProgrammableRasterPipelines;
import com.kltyton.lumenfx.client.resource.texture.lighting.BlueNoiseTextures;
import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic.NiagaraDynamicDrawGeometry;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory;
import com.kltyton.lumenfx.client.render.translucency.TranslucencyLightingVolume;
import com.kltyton.lumenfx.client.render.translucency.TranslucencyLightingVolume.LocalLights;
import com.kltyton.lumenfx.client.render.translucency.TranslucencyLightingVolume.DirectionalLights;
import com.kltyton.lumenfx.client.render.light.forward.ForwardAtmosphereTransmittanceBinding;
import com.kltyton.lumenfx.client.render.light.forward.ForwardTranslucentLights;
import com.kltyton.lumenfx.client.render.light.forward.ForwardTranslucentLights.Features;
import com.kltyton.lumenfx.client.render.light.forward.ForwardTranslucentLights.LegacyEnergy;
import com.kltyton.lumenfx.client.render.light.forward.SurfaceTranslucencyEnvironment;
import com.mojang.blaze3d.opengl.GlDevice;
import com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter;
import com.kltyton.lumenfx.client.render.motion.shader.MotionShaderContractException;
import com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan;
import com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionUniforms;
import com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionLogLayout;
import com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog;
import com.kltyton.lumenfx.client.render.material.vertex.MaterialVertexShaders;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.kltyton.lumenfx.effect.render.MaterialBlendOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.Comparator;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Frame-owned instance uploads use the host's fenced transient allocator. */
public final class EffectGpuRenderer implements AutoCloseable {
    public enum Pass { SCENE, POINT_SHADOW, CUSTOM_DEPTH }
    private record ShadowPass(PointShadowParticleView view, ShadowDepthTarget target, ShadowRasterViews matrices,
                              int firstView, int endView) { }
    private record ShadowDraw(GpuBufferSlice clip, @Nullable GpuBufferSlice spotCaster, @Nullable GpuBufferSlice directionalCaster) { }
    private record CustomDepthPass(ParticleCustomDepthTargets targets, Matrix4fc projection, boolean stencil) { }
    private record CustomDepthDraw(RenderPipeline pipeline, @Nullable GpuBufferSlice stencil) { }
    private final boolean pointShadow;
    private final boolean customDepth;
    private final boolean depthOnly;
    private final TranslucencyLightingVolume translucencyLighting = new TranslucencyLightingVolume();
    private TranslucencyLightingVolume.@Nullable Sample translucencyLightingSample;
    private SceneIndirectDiffuse.@Nullable Sample vertexTranslucencyIndirect;
    private final ForwardTranslucentLights forwardTranslucency = new ForwardTranslucentLights();
    private final DirectionalShadowRenderer directionalShadows = new DirectionalShadowRenderer();
    private final DirectionalShadowRenderer volumeDirectionalShadows =
            new DirectionalShadowRenderer(DirectionalShadowRenderer.VOLUME_CASCADE_LIMIT);
    private ForwardTranslucentLights.@Nullable Sample forwardTranslucencySample;
    private @Nullable SurfaceTranslucencyEnvironment surfaceTranslucencyEnvironment;
    private @Nullable AerialPerspectiveBinding aerialPerspective;
    private NiagaraSceneInputs.@Nullable Atmosphere materialAtmosphere;
    private final ParticleShadowPipelines shadowPipelines = new ParticleShadowPipelines();
    private final ParticleCustomDepthPipelines customDepthPipelines = new ParticleCustomDepthPipelines();
    private final SceneDepthDefaults sceneDepthDefaults = new SceneDepthDefaults();
    private @Nullable GpuTextureView materialCustomDepth, materialCustomStencil;
    private @Nullable OwnedColorTarget blackMaterialScene, whiteMaterialScene;
    private static final NiagaraSceneInputs.Texture[] MATERIAL_DEFERRED_TEXTURES = {
            NiagaraSceneInputs.Texture.GBUFFER_A, NiagaraSceneInputs.Texture.GBUFFER_B, NiagaraSceneInputs.Texture.GBUFFER_C,
            NiagaraSceneInputs.Texture.SCENE_COLOR, NiagaraSceneInputs.Texture.SCREEN_SPACE_AO, NiagaraSceneInputs.Texture.GBUFFER_F,
            NiagaraSceneInputs.Texture.GBUFFER_D, NiagaraSceneInputs.Texture.PARTICLE_DIFFUSE,
            NiagaraSceneInputs.Texture.PARTICLE_F0, NiagaraSceneInputs.Texture.PARTICLE_SOURCE_MATERIAL
    };
    private static final String[] MATERIAL_DEFERRED_SAMPLERS = {
            "LfxMaterialGBufferA", "LfxMaterialGBufferB", "LfxMaterialGBufferC", "LfxMaterialRawSceneColor", "LfxMaterialSceneAO",
            "LfxMaterialGBufferF", "LfxMaterialGBufferD", "LfxMaterialParticleDiffuse",
            "LfxMaterialParticleF0", "LfxMaterialParticleSource"
    };
    private final GpuTextureView[] materialDeferred = new GpuTextureView[MATERIAL_DEFERRED_TEXTURES.length];
    private @Nullable OwnedColorTarget shadowSceneTexture;

    public EffectGpuRenderer() { this(Pass.SCENE); }
    public EffectGpuRenderer(Pass pass) {
        pointShadow = pass == Pass.POINT_SHADOW;
        customDepth = pass == Pass.CUSTOM_DEPTH;
        depthOnly = pointShadow || customDepth;
    }

    private final com.kltyton.lumenfx.client.render.niagara.ParticleSceneDepth sceneDepth =
            new com.kltyton.lumenfx.client.render.niagara.ParticleSceneDepth();
    private final com.kltyton.lumenfx.client.render.scene.material.SceneColorCopy isolatedSceneColor =
            new com.kltyton.lumenfx.client.render.scene.material.SceneColorCopy();
    private GpuBufferSlice materialViewUniforms;
    private static final VertexFormat SPRITES = VertexFormat.builder(1)
            .addAttribute("Center", GpuFormat.RGB32_FLOAT).addAttribute("SizeRotation", GpuFormat.RGB32_FLOAT)
            .addAttribute("AtlasUV", GpuFormat.RGBA32_FLOAT).addAttribute("Color", GpuFormat.RGBA32_FLOAT)
            .addAttribute("ParticleData", GpuFormat.RGBA32_FLOAT).addAttribute("MaterialData", GpuFormat.RGBA32_FLOAT)
            .addAttribute("AtlasNextUV", GpuFormat.RGBA32_FLOAT).addAttribute("SubUVControl", GpuFormat.RGBA32_FLOAT)
            .addAttribute("DynamicValid", GpuFormat.R32_FLOAT)
            .addAttribute("SpriteVelocity", GpuFormat.RGB32_FLOAT).addAttribute("SpriteFacing", GpuFormat.RGB32_FLOAT)
            .addAttribute("SpriteAlignment", GpuFormat.RGB32_FLOAT).addAttribute("SpriteConfig", GpuFormat.RGBA32_FLOAT)
            .addAttribute("SpritePivotBlend", GpuFormat.RGB32_FLOAT)
            .addAttribute("SpriteSubImage", GpuFormat.R32_FLOAT).build();
    private static final VertexFormat RIBBONS = VertexFormat.builder(1)
            .addAttribute("CenterA", GpuFormat.RGB32_FLOAT).addAttribute("CenterB", GpuFormat.RGB32_FLOAT)
            .addAttribute("OffsetA", GpuFormat.RGB32_FLOAT).addAttribute("OffsetB", GpuFormat.RGB32_FLOAT)
            .addAttribute("UvRanges", GpuFormat.RGBA32_FLOAT)
            .addAttribute("UvTransform", GpuFormat.RGBA32_FLOAT)
            .addAttribute("ColorA", GpuFormat.RGBA32_FLOAT).addAttribute("ParticleA", GpuFormat.RGBA32_FLOAT)
            .addAttribute("CustomA", GpuFormat.RGBA32_FLOAT).addAttribute("ColorB", GpuFormat.RGBA32_FLOAT)
            .addAttribute("ParticleB", GpuFormat.RGBA32_FLOAT).addAttribute("CustomB", GpuFormat.RGBA32_FLOAT)
            // Pack control, two UV ranges and the first center into three attributes, keeping the existing stream offsets.
            .addAttribute("RibbonControlVRange", GpuFormat.RGBA32_FLOAT)
            .addAttribute("RibbonVRange", GpuFormat.RGBA32_FLOAT)
            .addAttribute("RibbonVRangeCenterA", GpuFormat.RGBA32_FLOAT)
            .addAttribute("ParticleCenterB", GpuFormat.RGB32_FLOAT).build();
    private static final VertexFormat MESHES = VertexFormat.builder(1)
            .addAttribute("Center", GpuFormat.RGB32_FLOAT).addAttribute("Orientation", GpuFormat.RGBA32_FLOAT)
            .addAttribute("ScaleCamera", GpuFormat.RGBA32_FLOAT).addAttribute("Velocity", GpuFormat.RGBA32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA32_FLOAT).addAttribute("MaterialData", GpuFormat.RGBA32_FLOAT)
            .addAttribute("ParticleData", GpuFormat.RGBA32_FLOAT).addAttribute("OwnerPositionScale", GpuFormat.RGBA32_FLOAT)
            .addAttribute("MeshOwnerRotation", GpuFormat.RGBA32_FLOAT).build();
    private enum FogKind { HOST, EXPONENTIAL, VOLUMETRIC }

    private record SlabTreeLighting(boolean conserve, boolean preserve, boolean fuzz) {
        private static final SlabTreeLighting NONE = new SlabTreeLighting(false, false, false);

        static SlabTreeLighting forMaterial(MaterialDefinition material) {
            int features = material.particleLightingFeatures();
            if (material.adaptiveSubstrateWords() == 0 || (features & MaterialDefinition.SUBSTRATE_VOLUME) == 0)
                return NONE;
            var platform = NiagaraPlatforms.snapshot();
            return new SlabTreeLighting(
                    java.util.Objects.requireNonNull(platform.console("r.Shading.EnergyConservation")).bits() > 0,
                    java.util.Objects.requireNonNull(platform.console("r.Shading.EnergyPreservation")).bits() > 0,
                    (features & MaterialDefinition.SUBSTRATE_FUZZ) != 0);
        }

        boolean reflection() { return conserve || preserve; }
    }

    private record PipelineMaterial(net.minecraft.resources.Identifier fragmentShader, Set<String> samplers,
                                    MaterialDefinition.Blend blend, MaterialBlendOp blendOp, MaterialDepth depth, boolean cull, boolean hdr,
                                    MaterialFog fog, FogKind fogKind,
                                    int dynamicSlots, boolean ribbonParticleData, boolean depthProjection,
                                    boolean sceneDepth, boolean macroUV, int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                                    int sgUv4Mask, int customSceneTextures, int deferredSceneTextures,
                                    @Nullable VertexMaterialSettings vertexMaterial, boolean instanceParameters, List<String> materialCollections,
                                    List<VirtualTextureRead> virtualTextureReads, boolean virtualTextureFeedback,
                                    List<String> specularProfiles, List<String> toonProfiles, List<String> slabSubsurfaceProfiles,
                                    boolean adaptiveProfile, SlabTreeLighting slabTreeLighting,
                                    MaterialDefinition.TranslucencyVolume translucencyVolume, Features forwardFeatures,
                                    boolean singleLocalCapture, boolean usesAerialPerspective) {
        boolean usesTranslucencyVolume() { return translucencyVolume.usesVolume(); }
        boolean samplesTranslucencyPerPixel() { return translucencyVolume.samplesPerPixel(); }
        boolean usesForwardTranslucency() { return translucencyVolume == MaterialDefinition.TranslucencyVolume.SURFACE_PER_PIXEL; }
        boolean usesForwardAtmosphere() {
            return usesForwardTranslucency() && forwardFeatures.atmosphereTransmittance()
                    && fog.enabled() && fog.perPixel() && !sceneColor;
        }
        boolean usesSurfaceVolumeEnvironment() { return translucencyVolume == MaterialDefinition.TranslucencyVolume.SURFACE_VOLUME; }
        boolean usesVolumeIndirect() {
            return translucencyVolume.vertexMode() > 0 || translucencyVolume == MaterialDefinition.TranslucencyVolume.PIXEL;
        }

        static PipelineMaterial from(MaterialDefinition material, Features forwardFeatures, boolean hasAerialPerspective) {
            FogKind fog = material.renderSettings().fog() instanceof VolumetricFog ? FogKind.VOLUMETRIC
                    : material.renderSettings().fog() instanceof ExponentialHeightFog ? FogKind.EXPONENTIAL : FogKind.HOST;
            return new PipelineMaterial(material.fragmentShader(), Set.copyOf(material.textures().keySet()), material.blend(), material.blendOp(),
                    material.depth(), material.cull(), material.renderSettings().hdr(),
                    material.appliesBasePassFog() ? material.fog() : MaterialFog.NONE, fog,
                    material.dynamicSlots(), material.ribbonParticleData(), material.depthProjection(), material.sceneDepth(), material.macroUV(),
                    material.dbufferTextures(), material.receivesDecals(), material.sceneColor(), material.materialView(),
                    material.sgUv4Mask(), material.customSceneTextures(),
                    material.deferredSceneTextures(), material.vertexMaterial(),
                    material.instanceParameters().byteSize() != 0, material.materialCollections().stream().map(value -> value.uniform()).toList(),
                    material.virtualTextureReads(), material.virtualTextureFeedback(), material.specularProfiles(), material.toonProfiles(),
                    material.slabSubsurfaceProfiles(), (material.particleLightingFeatures() & MaterialDefinition.SUBSTRATE_DIFFUSION) != 0
                            && !material.slabSubsurfaceProfiles().isEmpty(), SlabTreeLighting.forMaterial(material),
                    material.translucencyVolume(), material.translucencyVolume().usesSurfaceEnvironment() || material.translucencyVolume().vertexMode() > 0
                            || material.translucencyVolume() == MaterialDefinition.TranslucencyVolume.PIXEL
                            ? forwardFeatures : Features.NONE,
                    material.translucencyVolume().usesSurfaceEnvironment() && forwardFeatures.localCaptures()
                            && !forwardFeatures.blendsLocalCaptures(material.highQualityForwardReflections()),
                    hasAerialPerspective && material.usesAerialPerspectiveFog());
        }
    }
    private record Key(PipelineMaterial material, EffectMesh.Kind kind, GpuFormat format, boolean linearTarget,
                        boolean storesTransmission, boolean gpuSprite, boolean gpuRibbon, boolean disableCull, boolean cutout, boolean dbufferLookup,
                        boolean receivesDecals, boolean meshScene, boolean meshSceneCulling, boolean dynamicMesh,
                        boolean modulationOnly, boolean gammaTarget) {
        Key(MaterialDefinition material, EffectMesh.Kind kind, GpuFormat format, boolean linearTarget,
             boolean storesTransmission, boolean gpuSprite, boolean gpuRibbon, boolean disableCull, boolean cutout, boolean dbufferLookup,
             boolean receivesDecals, Features forwardFeatures, boolean hasAerialPerspective) {
            this(material, kind, format, linearTarget, storesTransmission, gpuSprite, gpuRibbon, disableCull, cutout, dbufferLookup,
                    receivesDecals, false, false, false, forwardFeatures, hasAerialPerspective);
        }
        Key(MaterialDefinition material, EffectMesh.Kind kind, GpuFormat format, boolean linearTarget,
             boolean storesTransmission, boolean gpuSprite, boolean gpuRibbon, boolean disableCull, boolean cutout, boolean dbufferLookup,
             boolean receivesDecals, boolean meshScene, boolean meshSceneCulling, boolean dynamicMesh, Features forwardFeatures,
             boolean hasAerialPerspective) {
            this(PipelineMaterial.from(material, forwardFeatures, hasAerialPerspective), kind, format, linearTarget, storesTransmission, gpuSprite, gpuRibbon, disableCull, cutout,
                    dbufferLookup, receivesDecals, meshScene, meshSceneCulling, dynamicMesh, false, false);
        }

        Key modulation() {
            return new Key(material, kind, format, linearTarget, storesTransmission, gpuSprite, gpuRibbon, disableCull, cutout,
                    dbufferLookup, receivesDecals, meshScene, meshSceneCulling, dynamicMesh, true, false);
        }

        Key withDBuffer() {
            return new Key(material, kind, format, linearTarget, storesTransmission, gpuSprite, gpuRibbon, disableCull, cutout,
                    true, receivesDecals, meshScene, meshSceneCulling, dynamicMesh, modulationOnly, gammaTarget);
        }

        Key directlyTo(GpuFormat colorFormat, boolean gamma) {
            return new Key(material, kind, colorFormat, !gamma, false, gpuSprite, gpuRibbon, disableCull, cutout,
                    dbufferLookup, receivesDecals, meshScene, meshSceneCulling, dynamicMesh, false, gamma);
        }
    }
    static final class Batch {
        final EffectMesh.Key key;
        final List<EffectMesh> parts = new ArrayList<>();
        int bytes, instances, offset, uniformOffset;
        DrawState state;
        @Nullable DrawState modulationState;
        double sortDistance;
        int sortLayer, sortPriority, sortQueue = 3000;
        UnityRendererBinding.@Nullable Sorting unitySort;
        long unityInstanceId;
        NiagaraGpuSpriteFrame gpuSprite;
        NiagaraGpuRibbonFrame gpuRibbon;
        NiagaraGpuMeshFrame gpuMesh;
        com.kltyton.lumenfx.client.render.niagara.SpriteCutoutBuffers cutoutBuffers;
        List<? extends MeshDrawChunk> meshChunks = List.of();
        final Map<MeshDrawChunk, GpuBuffer> reversedMeshIndices = new HashMap<>();
        GpuBufferSlice meshUniforms;
        GpuBufferSlice primitiveUniforms;
        @Nullable GpuBufferSlice primitiveCapture;
        SceneIndirectDiffuse.@Nullable Sample opaqueIndirectDiffuse;
        SceneIndirectDiffuse.@Nullable Sample vertexTranslucencyIndirect;
        ForwardTranslucentLights.@Nullable Sample forwardTranslucencySample;
        @Nullable SurfaceTranslucencyEnvironment surfaceTranslucencyEnvironment;
        GpuBufferSlice instanceUniforms;
        Map<String, GpuBufferSlice> collectionUniforms = Map.of();
        RenderPipeline motionPipeline;
        boolean motionZeroClear;
        @Nullable GpuTextureView motionColorDepth;
        final Map<MeshDrawChunk, GpuBufferSlice> meshCommands = new HashMap<>();
        List<NiagaraMaterialExtras.Chunk> extraChunks = List.of();
        com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms.Output meshScene;
        final Map<NiagaraMaterialExtras.Chunk, com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms.Output> meshSceneChunks = new HashMap<>();
        @Nullable GpuFormat cachedFormat;
        boolean cachedLinearTarget;
        boolean cachedStoresTransmission;
        boolean cachedDBufferLookup;
        @Nullable Features cachedForwardFeatures;
        boolean cachedAerialPerspective;
        @Nullable DrawState cachedBaseState;
        @Nullable DrawState cachedDBufferState;
        @Nullable DrawState cachedModulationState;
        Batch(EffectMesh.Key key) { this.key = key; }
        void reset() { parts.clear(); bytes = 0; instances = 0; gpuSprite = null; gpuRibbon = null; gpuMesh = null; modulationState = null; primitiveCapture = null; opaqueIndirectDiffuse = null; vertexTranslucencyIndirect = null; forwardTranslucencySample = null; surfaceTranslucencyEnvironment = null; unitySort = null; unityInstanceId = 0; motionColorDepth = null; meshCommands.clear(); reversedMeshIndices.clear(); extraChunks = List.of(); meshScene = null; meshSceneChunks.clear(); }
        void add(EffectMesh mesh) {
            parts.add(mesh);
            bytes = Math.addExact(bytes, mesh.byteSize());
            instances = Math.addExact(instances, mesh.instanceCount());
            setSortOrder(mesh.depth(), mesh.materialPrimitive());
        }
        boolean niagaraTranslucent() {
            return key.material().blend().translucent()
                    && (gpuSprite != null || gpuRibbon != null || gpuMesh != null
                            || !parts.isEmpty() && parts.getFirst().niagaraRenderer());
        }
        boolean orderedMultiply() {
            return key.material().blend() == MaterialDefinition.Blend.MODULATE
                    && key.material().blendOp() == MaterialBlendOp.ADD
                    && !parts.isEmpty() && parts.getFirst().unityRenderer() != null;
        }
        void setSortOrder(double distanceKey, @Nullable MaterialPrimitiveFrame primitive) {
            boolean niagara = niagaraTranslucent();
            var sort = niagara && primitive != null ? primitive.translucencySort() : null;
            var mesh = parts.isEmpty() ? null : parts.getFirst();
            var unity = !niagara && key.material().blend().translucent() && mesh != null
                    ? mesh.unityRenderer() : null;
            unitySort = unity == null ? null : unity.sorting();
            unityInstanceId = unitySort != null && unitySort.grouped() && mesh != null ? mesh.unityInstanceId() : 0;
            if (unitySort != null) {
                var rootGroup = unitySort.rootGroup();
                sortLayer = rootGroup != null ? rootGroup.layer() : unitySort.layer();
                sortPriority = rootGroup != null ? rootGroup.order() : unitySort.order();
                sortQueue = unitySort.queue();
                sortDistance = rootGroup != null ? distanceKey : unitySort.applyDistanceBias(distanceKey);
            } else {
                sortLayer = 0;
                sortQueue = 3000;
                sortPriority = sort != null ? sort.priority() : niagara ? 0 : key.sortOrder();
                sortDistance = sort == null ? distanceKey : sort.adjustedDistanceKey(distanceKey);
            }
        }
        int rendererOrder() { return niagaraTranslucent() ? key.sortOrder() : 0; }
        long rendererSubmission() { return niagaraTranslucent() ? key.primitive() : 0; }
        boolean readsPartialDepth() {
            if (gpuSprite != null) return gpuSprite.readsPartialDepth();
            if (gpuRibbon != null) return gpuRibbon.readsPartialDepth();
            return gpuMesh != null && gpuMesh.readsPartialDepth();
        }
        boolean firstPerson() {
            return gpuSprite != null ? gpuSprite.firstPerson() : gpuRibbon != null ? gpuRibbon.firstPerson()
                    : gpuMesh != null ? gpuMesh.firstPerson() : parts.getFirst().firstPerson();
        }
        int materialViewInputs() {
            return key.material().materialView(firstPerson());
        }
        boolean needsMaterialView() {
            var vertex = key.material().vertexMaterial();
            return key.material().materialView() != 0 || vertex != null && vertex.firstPersonInputs() != 0;
        }
        boolean needsMeshScene() {
            return key.mesh() != null && key.mesh().renderer().sceneTransforms()
                    != com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings.SceneTransforms.LEGACY;
        }
    }

    private static int compareUnityGroups(Batch a, Batch b) {
        var sortA = a.unitySort;
        var sortB = b.unitySort;
        boolean groupedA = sortA != null && sortA.grouped();
        boolean groupedB = sortB != null && sortB.grouped();
        if (groupedA != groupedB) return Boolean.compare(groupedA, groupedB);
        if (!groupedA) return 0;
        int instance = Long.compare(a.unityInstanceId, b.unityInstanceId);
        if (instance != 0) return instance;
        var groupsA = sortA.groups();
        var groupsB = sortB.groups();
        var rootA = groupsA.getFirst();
        var rootB = groupsB.getFirst();
        if (!rootA.id().equals(rootB.id()) || rootA.hierarchyOrder() != rootB.hierarchyOrder()) {
            int rootHierarchy = Integer.compare(rootA.hierarchyOrder(), rootB.hierarchyOrder());
            if (rootHierarchy != 0) return rootHierarchy;
            return rootA.id().compareTo(rootB.id());
        }
        int maxDepth = Math.max(groupsA.size(), groupsB.size());
        for (int depth = 1; depth <= maxDepth; depth++) {
            boolean hasGroupA = depth < groupsA.size();
            boolean hasGroupB = depth < groupsB.size();
            int layerA = hasGroupA ? groupsA.get(depth).layer() : sortA.layer();
            int layerB = hasGroupB ? groupsB.get(depth).layer() : sortB.layer();
            int layerCmp = Integer.compare(layerA, layerB);
            if (layerCmp != 0) return layerCmp;
            int orderA = hasGroupA ? groupsA.get(depth).order() : sortA.order();
            int orderB = hasGroupB ? groupsB.get(depth).order() : sortB.order();
            int orderCmp = Integer.compare(orderA, orderB);
            if (orderCmp != 0) return orderCmp;
            int hierarchyA = hasGroupA ? groupsA.get(depth).hierarchyOrder() : sortA.hierarchyOrder();
            int hierarchyB = hasGroupB ? groupsB.get(depth).hierarchyOrder() : sortB.hierarchyOrder();
            int hierarchyCmp = Integer.compare(hierarchyA, hierarchyB);
            if (hierarchyCmp != 0) return hierarchyCmp;
            if (hasGroupA != hasGroupB) return Boolean.compare(hasGroupA, hasGroupB);
            if (!hasGroupA) return 0;
            int groupIdCmp = groupsA.get(depth).id().compareTo(groupsB.get(depth).id());
            if (groupIdCmp != 0) return groupIdCmp;
        }
        return 0;
    }

    private static final Comparator<Batch> DRAW_ORDER =
            Comparator.<Batch>comparingInt(batch -> batch.key.material().translucencyPass().ordinal())
                    .thenComparingInt(batch -> batch.sortLayer)
                    .thenComparingInt(batch -> batch.sortPriority)
                    .thenComparingInt(batch -> batch.sortQueue)
                    .thenComparing(Comparator.comparingDouble((Batch batch) -> batch.sortDistance).reversed())
                    .thenComparing(EffectGpuRenderer::compareUnityGroups)
                    // Niagara's sort hint orders renderers submitted by one primitive, after the view's primitive sort.
                    .thenComparingInt(Batch::rendererOrder)
                    .thenComparingLong(Batch::rendererSubmission)
                    .thenComparingInt(batch -> batch.key.drawRun());

    static List<EffectMesh> sortMeshesForDraw(List<EffectMesh> meshes) {
        var ordered = new ArrayList<Batch>(meshes.size());
        for (EffectMesh mesh : meshes) {
            var batch = new Batch(mesh.key());
            batch.add(mesh);
            ordered.add(batch);
        }
        ordered.sort(DRAW_ORDER);
        var result = new ArrayList<EffectMesh>(ordered.size());
        for (Batch batch : ordered) {
            result.add(batch.parts.getFirst());
        }
        return result;
    }
    private final Map<Key, DrawState> states = new HashMap<>();
    private final Map<Key, DrawState> naniteStates = new HashMap<>();
    private final NaniteProgrammableRasterPipelines naniteProgrammable = new NaniteProgrammableRasterPipelines();
    private final MaterialVertexShaders vertexMaterials = new MaterialVertexShaders(
            (id, type) -> Minecraft.getInstance().getShaderManager().getShader(id, type));
    private final Map<com.kltyton.lumenfx.effect.render.SpriteCutout, com.kltyton.lumenfx.client.render.niagara.SpriteCutoutBuffers> cutouts = new HashMap<>();
    private final MeshGeometryCache staticMeshes = new MeshGeometryCache();
    private final ParticleLightRenderer lights = new ParticleLightRenderer();
    private final DeferredSkyLighting deferredSkyLighting = new DeferredSkyLighting();
    private final DirectionalParticleLighting directionalParticleLighting = new DirectionalParticleLighting();
    private final LocalReflectionBindings localReflections = new LocalReflectionBindings();
    private @Nullable GpuTextureView materialReflectionEnergy;
    private final OpaqueParticleSurfaces opaqueParticleSurfaces = new OpaqueParticleSurfaces();
    private final LegacySubsurfaceScattering subsurfaceScattering = new LegacySubsurfaceScattering();
    private final AdaptiveParticleReplayPipelines adaptiveParticleReplays = new AdaptiveParticleReplayPipelines();
    private final ParticleDecalDepth particleDecalDepth = new ParticleDecalDepth();
    private final PointLightFunctionRenderer pointFunctions = new PointLightFunctionRenderer();
    private final com.kltyton.lumenfx.client.render.decal.ParticleDecalRenderer decals =
            new com.kltyton.lumenfx.client.render.decal.ParticleDecalRenderer();
    private final com.kltyton.lumenfx.client.render.decal.NativeDecalMaterialResolver nativeDecalMaterials =
            new com.kltyton.lumenfx.client.render.decal.NativeDecalMaterialResolver();
    private final com.kltyton.lumenfx.client.render.decal.ShaderpackDecalColorResolver shaderpackDecalColors =
            new com.kltyton.lumenfx.client.render.decal.ShaderpackDecalColorResolver();
    private long maximumMeshTexels;
    private final Map<ExponentialHeightFog, GpuBufferSlice> fogUniforms = new HashMap<>();
    private final FogView fogView = new FogView();
    private static final net.minecraft.resources.Identifier UNITY_PARTICLE_SHADER = LumenFx.id("core/unity_particle");
    private static final net.minecraft.resources.Identifier UNITY_PARTICLE_LIT_VERTEX = LumenFx.id("core/unity_particle_lit");
    private static final Vector4f UNIT_COLOR = new Vector4f(1, 1, 1, 1);

    private static SceneIndirectLighting materialIndirectLighting(MaterialDefinition material, boolean sceneTarget) {
        return sceneTarget ? material.renderSettings().indirectLighting() : SceneIndirectLighting.NONE;
    }

    private static SceneIndirectLighting resolveOpaqueIndirectLighting(EffectFrame frame, NaniteWorldRenderer.@Nullable Frame nanite,
                                                                       SceneIndirectLighting fallback) {
        for (var mesh : frame.meshes()) {
            var mat = mesh.key().material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE && !mat.renderSettings().indirectLighting().equals(SceneIndirectLighting.NONE))
                return mat.renderSettings().indirectLighting();
        }
        for (var sprite : frame.gpuSprites()) {
            var mat = sprite.material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE && !mat.renderSettings().indirectLighting().equals(SceneIndirectLighting.NONE))
                return mat.renderSettings().indirectLighting();
        }
        for (var ribbon : frame.gpuRibbons()) {
            var mat = ribbon.material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE && !mat.renderSettings().indirectLighting().equals(SceneIndirectLighting.NONE))
                return mat.renderSettings().indirectLighting();
        }
        for (var mesh : frame.gpuMeshes()) {
            var mat = mesh.material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE && !mat.renderSettings().indirectLighting().equals(SceneIndirectLighting.NONE))
                return mat.renderSettings().indirectLighting();
        }
        if (nanite != null) {
            for (var surface : nanite.surfaces()) {
                var mat = surface.material();
                if (surface.drawsInMainPass() && mat.blend() == MaterialDefinition.Blend.OPAQUE
                        && !mat.renderSettings().indirectLighting().equals(SceneIndirectLighting.NONE))
                    return mat.renderSettings().indirectLighting();
            }
        }
        return fallback;
    }

    private static boolean hasOpaqueIndirectLighting(EffectFrame frame, NaniteWorldRenderer.@Nullable Frame nanite,
                                                     SceneIndirectLighting fallback, NiagaraPlatformEnvironment platform) {
        if (SceneIndirectDiffuse.hasOpaqueBasePassLighting(fallback, platform)) return true;
        for (var mesh : frame.meshes()) {
            var mat = mesh.key().material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE
                    && SceneIndirectDiffuse.hasOpaqueBasePassLighting(mat.renderSettings().indirectLighting(), platform))
                return true;
        }
        for (var sprite : frame.gpuSprites()) {
            var mat = sprite.material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE
                    && SceneIndirectDiffuse.hasOpaqueBasePassLighting(mat.renderSettings().indirectLighting(), platform))
                return true;
        }
        for (var ribbon : frame.gpuRibbons()) {
            var mat = ribbon.material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE
                    && SceneIndirectDiffuse.hasOpaqueBasePassLighting(mat.renderSettings().indirectLighting(), platform))
                return true;
        }
        for (var mesh : frame.gpuMeshes()) {
            var mat = mesh.material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE
                    && SceneIndirectDiffuse.hasOpaqueBasePassLighting(mat.renderSettings().indirectLighting(), platform))
                return true;
        }
        if (nanite != null) {
            for (var surface : nanite.surfaces()) {
                if (surface.drawsInMainPass() && surface.material().blend() == MaterialDefinition.Blend.OPAQUE
                        && SceneIndirectDiffuse.hasOpaqueBasePassLighting(surface.material().renderSettings().indirectLighting(), platform))
                    return true;
            }
        }
        return false;
    }

    private static boolean hasOpaqueDeferredSkyLighting(EffectFrame frame, NaniteWorldRenderer.@Nullable Frame nanite,
                                                        SceneIndirectLighting fallback) {
        if (DeferredSkyLighting.active(fallback)) return true;
        for (var mesh : frame.meshes()) {
            var mat = mesh.key().material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE
                    && DeferredSkyLighting.active(mat.renderSettings().indirectLighting()))
                return true;
        }
        for (var sprite : frame.gpuSprites()) {
            var mat = sprite.material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE
                    && DeferredSkyLighting.active(mat.renderSettings().indirectLighting()))
                return true;
        }
        for (var ribbon : frame.gpuRibbons()) {
            var mat = ribbon.material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE
                    && DeferredSkyLighting.active(mat.renderSettings().indirectLighting()))
                return true;
        }
        for (var mesh : frame.gpuMeshes()) {
            var mat = mesh.material();
            if (mat.blend() == MaterialDefinition.Blend.OPAQUE
                    && DeferredSkyLighting.active(mat.renderSettings().indirectLighting()))
                return true;
        }
        if (nanite != null) {
            for (var surface : nanite.surfaces()) {
                if (surface.drawsInMainPass() && surface.material().blend() == MaterialDefinition.Blend.OPAQUE
                        && DeferredSkyLighting.active(surface.material().renderSettings().indirectLighting()))
                    return true;
            }
        }
        return false;
    }

    private static boolean isSeparateLayerSafe(MaterialDefinition material) {
        if (material.blend() == MaterialDefinition.Blend.OPAQUE) return false;
        if (material.blend() == MaterialDefinition.Blend.MODULATE || material.blend() == MaterialDefinition.Blend.HOLDOUT) return true;
        boolean dualSource = (material.blend() == MaterialDefinition.Blend.COLORED_TRANSMITTANCE
                || material.blend() == MaterialDefinition.Blend.SOFT_ADDITIVE
                    && material.blendOp() == MaterialBlendOp.ADD
                    && material.fragmentShader().equals(UNITY_PARTICLE_SHADER)
                    && material.translucencyPass() != TranslucencyPass.AFTER_MOTION_BLUR)
                && ParticleDualSourceBlend.supported(RenderSystem.getDevice());
        if (dualSource) return material.blendOp() == MaterialBlendOp.ADD;
        if (material.blend() == MaterialDefinition.Blend.COLORED_TRANSMITTANCE)
            return PremultipliedBlendPlan.isLayerSafe(authoredBlend(MaterialDefinition.Blend.PREMULTIPLIED, material.blendOp()).orElse(null));
        return PremultipliedBlendPlan.isLayerSafe(authoredBlend(material.blend(), material.blendOp()).orElse(null));
    }

    private static Optional<BlendFunction> authoredBlend(MaterialDefinition.Blend mode, MaterialBlendOp op) {
        BlendOp gpuOp = op.gpu();
        if (mode == MaterialDefinition.Blend.OPAQUE) return Optional.empty();
        if (gpuOp == BlendOp.ADD) {
            return switch (mode) {
                case ADD -> Optional.of(BlendFunction.ADDITIVE);
                case ADDITIVE -> Optional.of(BlendFunction.LIGHTNING);
                case SOFT_ADDITIVE -> Optional.of(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_COLOR,
                        BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA));
                case ALPHA -> Optional.of(BlendFunction.TRANSLUCENT);
                case PREMULTIPLIED, COLORED_TRANSMITTANCE -> Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA);
                case HOLDOUT -> Optional.of(new BlendFunction(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA,
                        BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA));
                case MODULATE -> Optional.of(new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ZERO));
                case OPAQUE -> Optional.empty();
            };
        }
        return switch (mode) {
            case ADD -> Optional.of(blendWithOp(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ONE, gpuOp));
            case ADDITIVE -> Optional.of(blendWithOp(BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ONE, gpuOp));
            case SOFT_ADDITIVE -> Optional.of(blendWithOp(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_COLOR,
                    BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, gpuOp));
            case ALPHA -> Optional.of(blendWithOp(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
                    BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, gpuOp));
            case PREMULTIPLIED, COLORED_TRANSMITTANCE -> Optional.of(blendWithOp(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA,
                    BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, gpuOp));
            case HOLDOUT -> Optional.of(blendWithOp(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA,
                    BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, gpuOp));
            case MODULATE -> Optional.of(blendWithOp(BlendFactor.DST_COLOR, BlendFactor.ZERO,
                    BlendFactor.ZERO, BlendFactor.ONE, gpuOp));
            case OPAQUE -> Optional.empty();
        };
    }

    private static BlendFunction blendWithOp(BlendFactor srcRgb, BlendFactor dstRgb,
                                             BlendFactor srcAlpha, BlendFactor dstAlpha, BlendOp op) {
        return new BlendFunction(new BlendEquation(srcRgb, dstRgb, op),
                new BlendEquation(srcAlpha, dstAlpha, BlendOp.ADD));
    }
    private static final Vector3f ZERO_VEC3 = new Vector3f();
    private static final Matrix4f IDENTITY_MATRIX = new Matrix4f();
    private final Map<EffectMesh.Key, Batch> batches = new LinkedHashMap<>();
    private final List<Batch> drawing = new ArrayList<>();
    private final Map<MaterialDefinition, GpuBufferSlice> instanceParameters = new java.util.IdentityHashMap<>();
    private final Map<Integer, GpuBufferSlice> captureSelections = new HashMap<>();
    private final List<Batch> opaqueBatches = new ArrayList<>();
    private final List<Batch> translucentBatches = new ArrayList<>();
    private final List<Batch> receiverBatches = new ArrayList<>();
    private final List<Batch> stageBatches = new ArrayList<>();
    private final List<Batch> modulationBatches = new ArrayList<>();
    private final List<Batch> orderedBeforeDof = new ArrayList<>();
    private final GammaParticleBlendTargets gammaBlendTargets = new GammaParticleBlendTargets();
    private final List<Batch> motionBatches = new ArrayList<>();
    private final List<Batch> motionOpaque = new ArrayList<>();
    private final List<Batch> motionTranslucent = new ArrayList<>();
    private final Vector3f scratchRight = new Vector3f(), scratchUp = new Vector3f();
    private final Matrix4f scratchDepthProjection = new Matrix4f(), scratchMacroProjection = new Matrix4f(),
            scratchTranslucencyProjection = new Matrix4f(), scratchDynamicModelView = new Matrix4f(),
            scratchUnjitteredProjection = new Matrix4f(), scratchVolumeView = new Matrix4f(),
            scratchMaterialProjection = new Matrix4f(), scratchDecalProjection = new Matrix4f(),
            scratchLightProjection = new Matrix4f();
    private final List<Batch> depthAwareGroup = new ArrayList<>();
    private long generation = Long.MIN_VALUE;
    private int nextPipeline;
    private final NiagaraMaterialExtras materialExtras = new NiagaraMaterialExtras();
    private final VertexStreamRing vertexStreamRing = new VertexStreamRing();
    private MotionShaderAdapter motionShaders;
    private GpuBufferSlice motionUniforms;
    private @Nullable GpuTexture opaqueMotionDepthTexture;
    private @Nullable GpuTextureView opaqueMotionDepthView;
    private boolean motionHistoryValid;
    private double frameMotionRealTime = Double.NaN, frameMotionEngineTime = Double.NaN;
    private boolean frameMotionProjectionKnown;
    private boolean frameMotionMatricesKnown;
    private boolean frameMotionFirstPersonKnown;

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target) {
        requireClientThread();
        draw(frame, modelView, camera, target, Minecraft.getInstance().gameRenderer.mainCamera().position());
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target, Vec3 cameraWorldPosition) {
        draw(frame, modelView, camera, target, cameraWorldPosition, true);
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                     Vec3 cameraWorldPosition, boolean sceneOutputs) {
        draw(frame, modelView, camera, target, cameraWorldPosition, sceneOutputs, null);
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                     Vec3 cameraWorldPosition, boolean sceneOutputs, @Nullable ParticleCasters particleCasters) {
        draw(frame, modelView, camera, target, cameraWorldPosition, sceneOutputs, particleCasters, NiagaraSceneInputs.EMPTY);
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                     Vec3 cameraWorldPosition, boolean sceneOutputs, @Nullable ParticleCasters particleCasters,
                     NiagaraSceneInputs sceneInputs) {
        draw(frame, modelView, camera, target, cameraWorldPosition, sceneOutputs, particleCasters, sceneInputs, null);
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                     Vec3 cameraWorldPosition, boolean sceneOutputs, @Nullable ParticleCasters particleCasters,
                     NiagaraSceneInputs sceneInputs, @Nullable Supplier<NiagaraSceneInputs> afterBaseDepth) {
        draw(frame, modelView, camera, target, cameraWorldPosition, sceneOutputs, particleCasters, sceneInputs,
                afterBaseDepth, frame.usesTranslucencyVolume());
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                     Vec3 cameraWorldPosition, boolean sceneOutputs, @Nullable ParticleCasters particleCasters,
                     NiagaraSceneInputs sceneInputs, @Nullable Supplier<NiagaraSceneInputs> afterBaseDepth, boolean usesTranslucencyVolume) {
        draw(frame, modelView, camera, target, cameraWorldPosition, sceneOutputs, particleCasters, sceneInputs, afterBaseDepth,
                usesTranslucencyVolume, frame.usesForwardTranslucency());
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                     Vec3 cameraWorldPosition, boolean sceneOutputs, @Nullable ParticleCasters particleCasters,
                     NiagaraSceneInputs sceneInputs, @Nullable Supplier<NiagaraSceneInputs> afterBaseDepth,
                     boolean usesTranslucencyVolume, boolean usesForwardTranslucency) {
        draw(frame, modelView, camera, target, cameraWorldPosition, sceneOutputs, particleCasters, sceneInputs,
                afterBaseDepth, usesTranslucencyVolume, usesForwardTranslucency, null);
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                     Vec3 cameraWorldPosition, boolean sceneOutputs, @Nullable ParticleCasters particleCasters,
                     NiagaraSceneInputs sceneInputs, @Nullable Supplier<NiagaraSceneInputs> afterBaseDepth,
                     boolean usesTranslucencyVolume, boolean usesForwardTranslucency,
                     DirectionalShadowRenderer.@Nullable ParticleCasters directionalCasters) {
        draw(frame, modelView, camera, target, cameraWorldPosition, sceneOutputs, particleCasters, sceneInputs,
                afterBaseDepth, usesTranslucencyVolume, usesForwardTranslucency, directionalCasters, null);
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                     Vec3 cameraWorldPosition, boolean sceneOutputs, @Nullable ParticleCasters particleCasters,
                     NiagaraSceneInputs sceneInputs, @Nullable Supplier<NiagaraSceneInputs> afterBaseDepth,
                     boolean usesTranslucencyVolume, boolean usesForwardTranslucency,
                     DirectionalShadowRenderer.@Nullable ParticleCasters directionalCasters, NaniteWorldRenderer.@Nullable Frame nanite) {
        draw(frame, modelView, camera, target, cameraWorldPosition, sceneOutputs, particleCasters, sceneInputs,
                afterBaseDepth, usesTranslucencyVolume, usesForwardTranslucency, directionalCasters, nanite, false);
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                     Vec3 cameraWorldPosition, boolean sceneOutputs, @Nullable ParticleCasters particleCasters,
                     NiagaraSceneInputs sceneInputs, @Nullable Supplier<NiagaraSceneInputs> afterBaseDepth,
                     boolean usesTranslucencyVolume, boolean usesForwardTranslucency,
                     DirectionalShadowRenderer.@Nullable ParticleCasters directionalCasters, NaniteWorldRenderer.@Nullable Frame nanite,
                     boolean splitOpaqueDepth) {
        draw(frame.mainPassGeometry(), modelView, camera, target, cameraWorldPosition, sceneOutputs, particleCasters, sceneInputs,
                afterBaseDepth, null, null, usesTranslucencyVolume, usesForwardTranslucency, directionalCasters, nanite, splitOpaqueDepth);
    }

    public void drawParticleShadow(EffectFrame frame, PointShadowParticleView view, RenderTarget scene,
                                    ShadowDepthTarget target, ShadowRasterViews matrices, int firstView, int endView,
                                    NiagaraSceneInputs sceneInputs) {
        withProjection(view.projection(), () -> draw(frame, view.modelView(), view.orientation(), scene, view.camera(), false,
                null, sceneInputs, null, new ShadowPass(view, target, matrices, firstView, endView), null, false, false, null, null, false));
    }

    public void drawNaniteShadows(NaniteWorldRenderer.Frame frame, PointShadowParticleView view, RenderTarget scene,
                                  ShadowDepthTarget target, ShadowRasterViews matrices, int firstView, int endView,
                                  NiagaraSceneInputs.@Nullable Atmosphere atmosphere) {
        withProjection(view.projection(), () -> {
            materialAtmosphere = atmosphere;
            int customSceneTextures = 0;
            int deferredSceneTextures = 0;
            for (var surface : frame.surfaces()) {
                customSceneTextures |= surface.material().customSceneTextures();
                deferredSceneTextures |= surface.material().deferredSceneTextures();
            }
            bindMaterialCustomTextures(NiagaraSceneInputs.EMPTY, scene, customSceneTextures, false);
            bindMaterialDeferredTextures(NiagaraSceneInputs.EMPTY, scene, deferredSceneTextures);
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            var transform = RenderSystem.getDynamicUniforms().writeTransform(scratchDynamicModelView.set(view.modelView()),
                    UNIT_COLOR, ZERO_VEC3, IDENTITY_MATRIX);
            var sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR, true);
            frame.drawShadows(encoder, view, matrices, target, firstView, endView,
                    rasterNaniteMaterials(encoder, GpuFormat.R8_UNORM, false, false, sampler, transform));
        });
    }

    public void drawCustomDepth(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, Vec3 cameraWorld, RenderTarget scene,
                                Matrix4fc projection, ParticleCustomDepthTargets targets, NiagaraSceneInputs sceneInputs,
                                boolean stencil, NaniteWorldRenderer.@Nullable Frame nanite) {
        withProjection(projection, () -> draw(frame, modelView, camera, scene, cameraWorld, false,
                null, sceneInputs, null, null, new CustomDepthPass(targets, projection, stencil), false, false, null, nanite, false));
    }

    private static void withProjection(Matrix4fc matrix, Runnable draw) {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice projection;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(64, alignment, GpuBuffer.USAGE_UNIFORM)) {
            matrix.get(0, mapped.data().order(ByteOrder.nativeOrder()));
            projection = mapped.slice();
        }
        var oldProjection = java.util.Objects.requireNonNull(RenderSystem.getProjectionMatrixBuffer());
        var oldType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projection, matrix.m33() < 1 ? ProjectionType.PERSPECTIVE : ProjectionType.ORTHOGRAPHIC);
        try { draw.run(); }
        finally { RenderSystem.setProjectionMatrix(oldProjection, oldType); }
    }

    private void draw(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                      Vec3 cameraWorldPosition, boolean sceneOutputs, @Nullable ParticleCasters particleCasters,
                      NiagaraSceneInputs sceneInputs, @Nullable Supplier<NiagaraSceneInputs> afterBaseDepth,
                      @Nullable ShadowPass shadowPass, @Nullable CustomDepthPass customPass,
                      boolean usesTranslucencyVolume, boolean usesForwardTranslucency,
                      DirectionalShadowRenderer.@Nullable ParticleCasters directionalCasters,
                      NaniteWorldRenderer.@Nullable Frame nanite, boolean splitOpaqueDepth) {
        requireClientThread();
        translucencyLightingSample = null;
        vertexTranslucencyIndirect = null;
        forwardTranslucencySample = null;
        surfaceTranslucencyEnvironment = null;
        aerialPerspective = null;
        if (pointShadow != (shadowPass != null) || customDepth != (customPass != null))
            throw new IllegalArgumentException("Renderer pass does not match its buffer ownership");
        selectGeneration(frame.generation());
        staticMeshes.reap();
        fogUniforms.clear();
        var worldCapture = WorldHdrCapture.INSTANCE;
        boolean sceneTarget = !depthOnly && worldCapture.composing() && target == worldCapture.linearTarget();
        boolean sceneFog = sceneTarget && worldCapture.usesVolumeSceneLighting();
        var platform = NiagaraPlatforms.snapshot();
        var volumeSettings = usesTranslucencyVolume ? TranslucencyLightingVolume.Settings.read(platform) : null;
        boolean translucentLocalLights = sceneTarget && volumeSettings != null && volumeSettings.enabled();
        boolean forwardLightFunctions = sceneTarget && usesForwardTranslucency
                && ForwardTranslucentLights.lightFunctionsEnabled(platform);
        boolean forwardLocalShadows = sceneTarget && usesForwardTranslucency;
        var indirect = sceneTarget ? resolveOpaqueIndirectLighting(frame, nanite, worldCapture.sceneIndirectLighting())
                : SceneIndirectLighting.NONE;
        var atmosphere = sceneInputs.atmosphere();
        materialAtmosphere = atmosphere;
        boolean hasAerialPerspective = !depthOnly && atmosphere != null && atmosphere.aerialPerspective() != null;
        boolean hasForwardAtmosphere = ForwardAtmosphereTransmittanceBinding.available(atmosphere);
        if (sceneOutputs) pointFunctions.clearVolumeShadows(sceneTarget && !frame.pointLights().isEmpty()
                && (!frame.volumes().isEmpty() || sceneFog || translucentLocalLights || forwardLocalShadows));
        var surfaces = sceneTarget ? worldCapture.sceneSurfaces() : null;
        boolean nativeDecals = sceneOutputs && sceneTarget && worldCapture.shaderpackFrame() == null && !frame.decals().isEmpty();
        boolean deferredDecals = nativeDecals && frame.hasOpaqueDepthWriters();
        boolean opaqueDirectional = sceneTarget && sceneOutputs && frame.hasOpaqueDepthWriters()
                && !frame.directionalLights().isEmpty();
        if (sceneTarget && sceneOutputs) {
            worldCapture.sceneLights(frame.lights(), frame.pointLights(), frame.directionalLights());
            worldCapture.translucencyLocalLights(LocalLights.EMPTY);
            if ((usesForwardTranslucency || translucentLocalLights || sceneFog || opaqueDirectional)
                    && !frame.directionalLights().isEmpty()) {
                var unjittered = scratchUnjitteredProjection;
                worldCapture.copyUnjitteredProjection(unjittered);
                var main = SceneDirectionalLight.selectForward(frame.directionalLights());
                boolean shadowMain = main != null && (opaqueDirectional || usesForwardTranslucency && main.affectsTranslucency()
                        || sceneFog && main.volumetricScattering() > 0 && main.castVolumetricShadow());
                float near = worldCapture.nearClipCm();
                var forwardShadows = directionalShadows.prepare(platform, shadowMain ? main : null, target, unjittered,
                        modelView, cameraWorldPosition, near, directionalCasters);
                worldCapture.forwardDirectionalShadows(forwardShadows);
                if (translucentLocalLights) {
                    boolean injectShadows = Objects.requireNonNull(
                            platform.console("r.TranslucencyLightingVolume.InjectDirectionalLightCSM")).bits() > 0;
                    var volumeView = scratchVolumeView.set(modelView);
                    worldCapture.translucencyDirectionalLights(new DirectionalLights(frame.directionalLights(), Map.of(), light -> {
                        if (injectShadows && light == main && shadowMain && (forwardShadows == null || forwardShadows.allCascades()))
                            return forwardShadows;
                        return volumeDirectionalShadows.prepare(platform, light, target, unjittered,
                                volumeView, cameraWorldPosition, near, directionalCasters, injectShadows);
                    }));
                }
            }
        }
        if (sceneOutputs && !frame.decals().isEmpty()) {
            if (!sceneTarget)
                throw new IllegalArgumentException("Decal rendering requires a captured scene projection and depth");
            if (deferredDecals) worldCapture.sceneDecals(null);
            else prepareSceneDecals(frame, modelView, camera, target, cameraWorldPosition, surfaces, null, sceneInputs);
        } else if (sceneTarget && sceneOutputs) {
            decals.resetScene();
            worldCapture.sceneDecals(null);
        }
        boolean sceneLighting = sceneOutputs && sceneTarget && (!frame.lights().isEmpty() || !frame.pointLights().isEmpty());
        boolean sceneLightFunctions = sceneOutputs && sceneTarget
                && PointLightFunctionRenderer.hasDirectionalFunctions(frame, sceneFog, translucentLocalLights, forwardLightFunctions);
        boolean decalNormalReprojection = sceneOutputs && sceneTarget && worldCapture.decalNormalReprojection();
        boolean captureGBuffer = sceneOutputs && sceneTarget && worldCapture.capturesNiagaraGBuffer();
        boolean captureSubsurface = sceneOutputs && sceneTarget && worldCapture.capturesSubsurfaceHistory();
        boolean opaqueIndirect = sceneOutputs && sceneTarget && hasOpaqueIndirectLighting(frame, nanite, indirect, platform);
        boolean deferredSky = sceneOutputs && sceneTarget && hasOpaqueDeferredSkyLighting(frame, nanite, indirect);
        boolean nativeSurfaceCapture = worldCapture.shaderpackFrame() == null && surfaces != null;
        boolean externalParticleGBuffer = worldCapture.shaderpackFrame() != null && captureGBuffer;
        var opaqueRequirements = ((sceneLighting || nativeDecals || decalNormalReprojection || captureGBuffer
                || captureSubsurface || opaqueIndirect || deferredSky) && nativeSurfaceCapture)
                || externalParticleGBuffer || opaqueDirectional
                ? frame.opaqueSurfaceRequirements() : null;
        if (opaqueRequirements != null && nanite != null) for (var surface : nanite.surfaces())
            if (surface.drawsInMainPass() && EffectFrame.writesOpaqueDepth(surface.material()))
                opaqueRequirements = opaqueRequirements.include(surface.material(), surface.primitive());
        boolean captureParticleMaterials = opaqueRequirements != null
                && (decalNormalReprojection || captureGBuffer || captureSubsurface || opaqueRequirements.defaultLit()
                    || opaqueRequirements.contactCasters() && !frame.pointLights().isEmpty());
        boolean lateLighting = nativeSurfaceCapture && (sceneLighting && (captureParticleMaterials || nativeDecals || afterBaseDepth != null)
                || deferredSky && captureParticleMaterials && java.util.Objects.requireNonNull(opaqueRequirements).defaultLit());
        if ((sceneLighting || sceneLightFunctions) && !lateLighting)
            drawSceneLights(frame, modelView, camera, target, cameraWorldPosition, surfaces, null, 1, particleCasters,
                    translucentLocalLights, forwardLightFunctions, forwardLocalShadows, sceneInputs);
        if (nanite == null && frame.meshes().isEmpty() && frame.gpuSprites().isEmpty() && frame.gpuRibbons().isEmpty() && frame.gpuMeshes().isEmpty()) {
            batches.clear(); drawing.clear();
            if (sceneOutputs && sceneTarget) finishSceneDecals(target, modelView, surfaces, null, sceneInputs, afterBaseDepth);
            if (lateLighting) drawSceneLights(frame, modelView, camera, target, cameraWorldPosition, surfaces, null, 1, particleCasters,
                    translucentLocalLights, forwardLightFunctions, forwardLocalShadows, sceneInputs);
            return;
        }
        for (var existing : batches.values()) existing.reset();
        drawing.clear();
        for (var mesh : frame.meshes()) {
            var batch = batches.computeIfAbsent(mesh.key(), Batch::new);
            if (batch.instances == 0) drawing.add(batch);
            batch.add(mesh);
        }
        int bytes = 0;
        var minecraft = Minecraft.getInstance();
        var capture = WorldHdrCapture.INSTANCE;
        boolean separate = sceneTarget;
        GpuFormat format = depthOnly ? GpuFormat.R8_UNORM : target.getColorTexture().getFormat();
        boolean linearTarget = !depthOnly && capture.composing();
        boolean opaqueDepthChanged = nanite != null && nanite.surfaces().stream()
                .anyMatch(surface -> surface.drawsInMainPass() && EffectFrame.writesOpaqueDepth(surface.material()));
        for (int i = 0, n = drawing.size(); i < n; i++) {
            var batch = drawing.get(i);
            batch.offset = bytes;
            bytes = Math.addExact(bytes, batch.bytes);
            var material = batch.key.material();
            boolean opaque = material.blend() == MaterialDefinition.Blend.OPAQUE;
            opaqueDepthChanged |= EffectFrame.writesOpaqueDepth(material);
            boolean storesTransmission = separate && isSeparateLayerSafe(material);
            boolean dbufferLookup = hasDBuffer(material, separate, batch.key.receivesDecals());
            var batchForwardFeatures = depthOnly ? Features.NONE
                    : ForwardTranslucentLights.features(platform, materialIndirectLighting(material, sceneTarget), hasForwardAtmosphere);
            if (batch.cachedBaseState != null && batch.cachedFormat == format && batch.cachedLinearTarget == linearTarget
                    && batch.cachedStoresTransmission == storesTransmission && batch.cachedDBufferLookup == dbufferLookup
                    && batch.cachedAerialPerspective == hasAerialPerspective
                    && Objects.equals(batch.cachedForwardFeatures, batchForwardFeatures)) {
                batch.state = batch.cachedBaseState;
            } else {
                var stateKey = new Key(material, batch.key.kind(), format, linearTarget, storesTransmission, false, false,
                        batch.key.disableCull(), batch.key.cutout() != null, dbufferLookup, batch.key.receivesDecals(),
                        batch.needsMeshScene(), false,
                        batch.key.mesh() != null && batch.key.mesh().geometry() instanceof NiagaraDynamicDrawGeometry,
                        batchForwardFeatures, hasAerialPerspective);
                batch.state = states.computeIfAbsent(stateKey, this::createState);
                batch.cachedFormat = format;
                batch.cachedLinearTarget = linearTarget;
                batch.cachedStoresTransmission = storesTransmission;
                batch.cachedDBufferLookup = dbufferLookup;
                batch.cachedForwardFeatures = batchForwardFeatures;
                batch.cachedAerialPerspective = hasAerialPerspective;
                batch.cachedBaseState = batch.state;
                batch.cachedDBufferState = null;
                batch.cachedModulationState = null;
            }
        }
        for (var sprite : frame.gpuSprites()) {
            var key = new EffectMesh.Key(sprite.material(), EffectMesh.Kind.SPRITE, 1, sprite.sortOrder(), 0, sprite.coverage(), false,
                    sprite.cutout(), null, false, true, sprite.receivesDecals());
            var batch = new Batch(key);
            batch.gpuSprite = sprite;
            batch.setSortOrder(sprite.depth(), sprite.materialPrimitive());
            boolean opaque = sprite.material().blend() == MaterialDefinition.Blend.OPAQUE;
            opaqueDepthChanged |= EffectFrame.writesOpaqueDepth(sprite.material());
            var batchForwardFeatures = depthOnly ? Features.NONE
                    : ForwardTranslucentLights.features(platform, materialIndirectLighting(sprite.material(), sceneTarget), hasForwardAtmosphere);
            batch.state = states.computeIfAbsent(new Key(sprite.material(), EffectMesh.Kind.SPRITE, format, linearTarget,
                    separate && isSeparateLayerSafe(sprite.material()), true, false, false, sprite.cutout() != null, hasDBuffer(sprite.material(), separate, sprite.receivesDecals()),
                    sprite.receivesDecals(), batchForwardFeatures, hasAerialPerspective), this::createState);
            drawing.add(batch);
        }
        for (var ribbon : frame.gpuRibbons()) {
            var key = new EffectMesh.Key(ribbon.material(), EffectMesh.Kind.RIBBON, 1, ribbon.sortOrder(), 0, ribbon.coverage(),
                    false, null, null, false, true, ribbon.receivesDecals());
            var batch = new Batch(key);
            batch.gpuRibbon = ribbon;
            batch.setSortOrder(ribbon.depth(), ribbon.materialPrimitive());
            boolean opaque = ribbon.material().blend() == MaterialDefinition.Blend.OPAQUE;
            opaqueDepthChanged |= EffectFrame.writesOpaqueDepth(ribbon.material());
            var batchForwardFeatures = depthOnly ? Features.NONE
                    : ForwardTranslucentLights.features(platform, materialIndirectLighting(ribbon.material(), sceneTarget), hasForwardAtmosphere);
            batch.state = states.computeIfAbsent(new Key(ribbon.material(), EffectMesh.Kind.RIBBON, format, linearTarget,
                    separate && isSeparateLayerSafe(ribbon.material()), false, true, ribbon.disableCull(), false, hasDBuffer(ribbon.material(), separate, ribbon.receivesDecals()),
                    ribbon.receivesDecals(), batchForwardFeatures, hasAerialPerspective), this::createState);
            drawing.add(batch);
        }
        for (var mesh : frame.gpuMeshes()) {
            var key = new EffectMesh.Key(mesh.material(), EffectMesh.Kind.MESH, 1, mesh.sortOrder(), 0,
                    com.kltyton.lumenfx.effect.render.SpriteCoverage.DISABLED, false, null, mesh.geometry(), false, true, mesh.receivesDecals());
            var batch = new Batch(key);
            batch.gpuMesh = mesh;
            batch.setSortOrder(mesh.depth(), mesh.materialPrimitive());
            boolean opaque = mesh.material().blend() == MaterialDefinition.Blend.OPAQUE;
            opaqueDepthChanged |= EffectFrame.writesOpaqueDepth(mesh.material());
            var batchForwardFeatures = depthOnly ? Features.NONE
                    : ForwardTranslucentLights.features(platform, materialIndirectLighting(mesh.material(), sceneTarget), hasForwardAtmosphere);
            batch.state = states.computeIfAbsent(new Key(mesh.material(), EffectMesh.Kind.MESH, format, linearTarget,
                    separate && isSeparateLayerSafe(mesh.material()), true, false, false, false, hasDBuffer(mesh.material(), separate, mesh.receivesDecals()),
                    mesh.receivesDecals(), batch.needsMeshScene(), mesh.preparedScene() != null,
                    mesh.geometry().geometry() instanceof NiagaraDynamicDrawGeometry, batchForwardFeatures, hasAerialPerspective), this::createState);
            drawing.add(batch);
        }
        if (drawing.isEmpty() && nanite == null) {
            if (deferredDecals) prepareSceneDecals(frame, modelView, camera, target, cameraWorldPosition, surfaces, null, sceneInputs);
            if (sceneOutputs && sceneTarget) finishSceneDecals(target, modelView, surfaces, null, sceneInputs, afterBaseDepth);
            if (lateLighting) drawSceneLights(frame, modelView, camera, target, cameraWorldPosition, surfaces, null, 1, particleCasters,
                    translucentLocalLights, forwardLightFunctions, forwardLocalShadows, sceneInputs);
            return;
        }
        int materialViewInputs = 0;
        int customSceneTextures = 0;
        int deferredSceneTextures = 0;
        boolean needsMaterialView = false;
        boolean needsAerialPerspective = false;
        boolean needsReflectionEnergy = false;
        boolean needsHeightFog = false;
        boolean needsMeshScene = false;
        boolean hasExtraCpuData = false;
        boolean needsMacroDepthView = false;
        boolean needsDepthProjection = false;
        boolean needsMacroUv = false;
        boolean needsSceneDepth = false;
        boolean needsSceneColor = false;
        for (int i = 0, n = drawing.size(); i < n; i++) {
            var batch = drawing.get(i);
            var material = batch.key.material();
            materialViewInputs |= batch.materialViewInputs();
            customSceneTextures |= material.customSceneTextures();
            deferredSceneTextures |= material.deferredSceneTextures();
            if (batch.needsMaterialView()) needsMaterialView = true;
            if (batch.state.key.material().usesAerialPerspective()) needsAerialPerspective = true;
            if (batch.state.reflectionEnergy()) needsReflectionEnergy = true;
            if (!depthOnly && hasHeightFog(material)) needsHeightFog = true;
            if (batch.needsMeshScene()) needsMeshScene = true;
            boolean cpuBatch = batch.gpuSprite == null && batch.gpuRibbon == null && batch.gpuMesh == null;
            if (cpuBatch && (batch.key.kind() == EffectMesh.Kind.MESH || batch.key.particleMotion() || (material.dynamicSlots() & 14) != 0
                    || batch.key.kind() == EffectMesh.Kind.RIBBON && material.ribbonParticleData())) {
                hasExtraCpuData = true;
            }
            if (material.depthProjection()) {
                needsDepthProjection = true;
                if (material.macroUV()) needsMacroDepthView = true;
            }
            if (material.macroUV()) needsMacroUv = true;
            if (material.sceneDepth()) needsSceneDepth = true;
            if (material.sceneColor()) needsSceneColor = true;
        }
        if (nanite != null) for (var surface : nanite.surfaces()) {
            var surfaceMat = surface.material();
            customSceneTextures |= surfaceMat.customSceneTextures();
            deferredSceneTextures |= surfaceMat.deferredSceneTextures();
            if (!needsReflectionEnergy && SlabTreeLighting.forMaterial(surfaceMat).reflection()) needsReflectionEnergy = true;
            if (!depthOnly && !needsHeightFog && hasHeightFog(surfaceMat)) needsHeightFog = true;
            if (!needsSceneDepth && surfaceMat.sceneDepth()) needsSceneDepth = true;
            if (!needsSceneColor && surfaceMat.sceneColor()) needsSceneColor = true;
        }
        bindMaterialCustomTextures(sceneInputs, target, customSceneTextures, sceneOutputs && sceneTarget);
        bindMaterialDeferredTextures(sceneInputs, target, deferredSceneTextures);
        materialViewUniforms = needsMaterialView
                ? com.kltyton.lumenfx.client.render.scene.material.MaterialViewUniforms.write(RenderSystem.getDevice().createCommandEncoder(),
                        materialViewInputs, cameraWorldPosition, modelView, camera, pointShadow,
                        (materialViewInputs & AtmosphereMaterialLighting.VIEW_INPUT) == 0 ? null
                                : AtmosphereMaterialLighting.resolve(atmosphere, frame.directionalLights(), platform)) : null;
        drawing.sort(DRAW_ORDER);
        batches.values().removeIf(batch -> batch.instances == 0);
        int sortedOffset = 0;
        for (int i = 0, n = drawing.size(); i < n; i++) {
            var batch = drawing.get(i);
            batch.offset = sortedOffset;
            sortedOffset = Math.addExact(sortedOffset, batch.bytes);
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (hasAerialPerspective && needsAerialPerspective) {
            var volume = Objects.requireNonNull(Objects.requireNonNull(atmosphere).aerialPerspective());
            aerialPerspective = AerialPerspectiveBinding.prepare(encoder, volume);
        }
        materialReflectionEnergy = lights.prepareReflectionEnergy(encoder, needsReflectionEnergy);
        instanceParameters.clear();
        var collectionFrame = MaterialCollectionUniforms.INSTANCE.frame(encoder, Minecraft.getInstance().level);
        for (var batch : drawing) {
            var vertex = batch.key.material().vertexMaterial();
            MaterialDefinition material = batch.key.material();
            if (!instanceParameters.containsKey(material)) instanceParameters.put(material,
                    MaterialInstanceUniforms.write(encoder, material));
            batch.instanceUniforms = instanceParameters.get(material);
            batch.collectionUniforms = collectionFrame.prepare(material);
            batch.primitiveUniforms = null;
            if (vertex != null && vertex.primitiveMatrices()) {
                var primitiveFrame = materialPrimitive(batch);
                var meshBounds = batch.key.mesh() == null ? null : batch.key.mesh().sourceBounds();
                batch.primitiveUniforms = com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveUniforms.write(encoder,
                        java.util.Objects.requireNonNull(primitiveFrame, "Material primitive transforms require the component render-frame poses"),
                        meshBounds);
            }
        }
        motionUniforms = frame.motionView() != null && capture.particleMotionActive()
                ? ParticleMotionUniforms.write(encoder, frame.motionView(), frame.deltaTime(), materialViewInputs) : null;
        motionHistoryValid = frame.motionView() != null && frame.motionView().historyValid();
        frameMotionRealTime = frame.motionView() == null ? Double.NaN : frame.motionView().previous().realTime();
        frameMotionEngineTime = frame.motionView() == null ? Double.NaN : frame.motionView().previous().engineTime();
        frameMotionProjectionKnown = frame.motionView() != null && frame.motionView().previous().projection() != null;
        frameMotionMatricesKnown = frame.motionView() != null && frame.motionView().previous().modelView() != null;
        frameMotionFirstPersonKnown = frame.motionView() != null && frame.motionView().previous().firstPerson() != null;
        for (var batch : drawing) batch.cutoutBuffers = batch.key.cutout() == null ? null
                : cutouts.computeIfAbsent(batch.key.cutout(), value -> new com.kltyton.lumenfx.client.render.niagara.SpriteCutoutBuffers(encoder, value));
        var dynamicDraws = new java.util.IdentityHashMap<NiagaraDynamicDrawGeometry, List<? extends MeshDrawChunk>>();
        for (var batch : drawing) if (batch.key.mesh() != null) {
            var geometry = batch.key.mesh().geometry();
            if (geometry instanceof NiagaraDynamicDrawGeometry dynamic) {
                batch.meshChunks = dynamicDraws.computeIfAbsent(dynamic, value -> value.prepare(encoder));
            } else {
                var source = (StaticMeshGeometry) geometry;
                var chunks = staticMeshes.get(encoder, source, meshTexelLimit()).chunks();
                batch.meshChunks = chunks;
                if (batch.key.mesh().reversedIndices()) {
                    var section = geometry.sections().get(batch.key.mesh().section());
                    for (var chunk : chunks) if (chunk.section().equals(section))
                        batch.reversedMeshIndices.put(chunk, chunk.reversedIndices(encoder, source));
                }
            }
            batch.meshUniforms = MeshRendererUniforms.write(encoder, batch.key.mesh());
            if (batch.gpuMesh != null) {
                var section = batch.key.mesh().geometry().sections().get(batch.key.mesh().section());
                for (var chunk : batch.meshChunks) if (chunk.section().equals(section)) {
                    var command = batch.gpuMesh.command(encoder, chunk.indexCount());
                    if (command != null) batch.meshCommands.put(chunk, command);
                }
            }
        }
        if (!depthOnly && needsHeightFog) {
            fogView.prepare(modelView, cameraWorldPosition);
            for (int i = 0, n = drawing.size(); i < n; i++) prepareHeightFog(encoder, drawing.get(i).key.material());
            if (nanite != null) for (var surface : nanite.surfaces()) prepareHeightFog(encoder, surface.material());
        }
        GpuBufferSlice vertices = null;
        var meshScenePrograms = needsMeshScene
                ? com.kltyton.lumenfx.client.VfxEngine.INSTANCE.meshScenePrograms() : null;
        if (meshScenePrograms != null) meshScenePrograms.beginDraw();
        if (bytes > 0) {
            vertexStreamRing.beginDraw(frame);
            vertices = vertexStreamRing.upload(encoder, bytes, meshScenePrograms != null, drawing);
        }
        if (hasExtraCpuData) {
            materialExtras.beginFrame();
            for (int i = 0, n = drawing.size(); i < n; i++) {
                var batch = drawing.get(i);
                if (batch.gpuSprite == null && batch.gpuRibbon == null && batch.gpuMesh == null
                        && (batch.key.kind() == EffectMesh.Kind.MESH || batch.key.particleMotion() || (batch.key.material().dynamicSlots() & 14) != 0
                        || batch.key.kind() == EffectMesh.Kind.RIBBON && (batch.key.material().ribbonParticleData()
                        || batch.parts.getFirst().needsMaterialBasis())))
                    batch.extraChunks = materialExtras.prepare(encoder, batch.parts);
            }
        }
        if (meshScenePrograms != null) {
            var sceneView = new com.kltyton.lumenfx.client.render.mesh.scene.MeshSceneView(camera, cameraWorldPosition,
                    frame.motionView(), frame.deltaTime());
            for (int i = 0, n = drawing.size(); i < n; i++) {
                var batch = drawing.get(i);
                if (!batch.needsMeshScene()) continue;
                if (batch.gpuMesh != null)
                    batch.meshScene = batch.gpuMesh.preparedScene() != null ? batch.gpuMesh.preparedScene()
                            : meshScenePrograms.prepareGpu(encoder, batch.gpuMesh, batch.meshUniforms, sceneView,
                                    com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection.needsPreviousPayload(batch.key.material()));
                else for (var chunk : batch.extraChunks)
                    batch.meshSceneChunks.put(chunk, meshScenePrograms.prepareCpu(encoder, batch.key.mesh(),
                            batch.meshUniforms, sceneView, vertices, batch.offset / Float.BYTES, chunk.buffer(),
                            chunk.firstInstance(), chunk.instances(), chunk.source().extraFloatsPerInstance(),
                            com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection.needsPreviousPayload(batch.key.material()), chunk.source().emitterScene()));
            }
        }
        GpuBuffer gpuIndices = null;
        IndexType gpuIndexType = null;
        if (!frame.gpuSprites().isEmpty()) {
            var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.TRIANGLES);
            gpuIndices = sequential.getBuffer(6);
            gpuIndexType = sequential.type();
        }
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        int viewBytes = needsMacroDepthView ? 80 : 64;
        int block = ((viewBytes + alignment - 1) / alignment) * alignment;
        GpuBufferSlice uniforms;
        Vector3f right = camera.transform(scratchRight.set(1, 0, 0)), up = camera.transform(scratchUp.set(0, 1, 0));
        float materialMipBias = pointShadow ? 0 : capture.materialViewMipBias();
        float resolutionFraction = pointShadow ? 1 : capture.materialResolutionFraction();
        boolean perspective = RenderSystem.getProjectionType() == ProjectionType.PERSPECTIVE;
        int viewportWidth = shadowPass == null ? target.width : shadowPass.target().size();
        int viewportHeight = shadowPass == null ? target.height : shadowPass.target().size();
        float[] depthTransform = null;
        Matrix4f macroProjection = null;
        if (needsDepthProjection) {
            Matrix4f projection = scratchDepthProjection;
            if (shadowPass != null) projection.set(shadowPass.view().projection());
            else if (customPass != null) projection.set(customPass.projection());
            else capture.copyCameraProjection(projection);
            depthTransform = com.kltyton.lumenfx.client.render.niagara.ParticleSceneDepth.depthTransform(
                    projection, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        }
        if (needsMacroUv) {
            macroProjection = scratchMacroProjection;
            if (shadowPass != null) macroProjection.set(shadowPass.view().projection());
            else if (customPass != null) macroProjection.set(customPass.projection());
            else capture.copyProjection(macroProjection);
        }
        if (drawing.isEmpty()) uniforms = null;
        else try (var mapped = encoder.transientMemory().allocateGpuMapped((long)block * 2 * drawing.size(), alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            for (int i = 0; i < drawing.size(); i++) {
                var batch = drawing.get(i);
                batch.uniformOffset = i * block * 2;
                data.position(batch.uniformOffset);
                data.putFloat((float)frame.time()).putFloat(materialMipBias).putFloat((float)frame.realTime()).putFloat((float)frame.engineTime());
                batch.key.material().writeParameters(data);
                data.putFloat(frame.deltaTime()).putFloat(resolutionFraction).putFloat(1.0f / resolutionFraction).putFloat(batch.firstPerson() ? 1 : 0);
                data.position(batch.uniformOffset + block);
                data.putFloat(right.x).putFloat(right.y).putFloat(right.z)
                        .putFloat(RenderSystem.getDevice().backend instanceof GlDevice ? 1 : 0);
                data.putFloat(up.x).putFloat(up.y).putFloat(up.z).putFloat(1f / viewportHeight);
                var coverage = perspective ? batch.key.coverage().forMaterial(batch.key.material()) : SpriteCoverage.DISABLED;
                data.putFloat(batch.key.widthSegments()).putFloat(coverage.channels() | (coverage.enabled() ? 16 : 0))
                        .putFloat(coverage.blend()).putFloat(1f / viewportWidth);
                if (batch.key.material().macroUV()) {
                    if (batch.key.kind() == EffectMesh.Kind.MESH) data.putFloat(.5f).putFloat(.5f).putFloat(0).putFloat(0);
                    else {
                        if (batch.key.kind() != EffectMesh.Kind.SPRITE) throw new IllegalStateException("Macro UV data requires a sprite or mesh renderer");
                        Vec3 actorPosition = batch.gpuSprite == null ? batch.parts.get(0).actorPosition() : batch.gpuSprite.actorPosition();
                        double radius = batch.gpuSprite == null ? batch.parts.get(0).macroUVRadius() : batch.gpuSprite.macroUVRadius();
                        for (float component : com.kltyton.lumenfx.client.render.niagara.ParticleMacroUV.calculate(
                                actorPosition, cameraWorldPosition, radius, modelView, macroProjection,
                                RenderSystem.getDevice().backend instanceof GlDevice)) data.putFloat(component);
                    }
                }
                if (batch.key.material().depthProjection()) for (float component : depthTransform) data.putFloat(component);
            }
            uniforms = mapped.slice();
        }
        instanceParameters.clear();
        var transform = RenderSystem.getDynamicUniforms().writeTransform(scratchDynamicModelView.set(modelView), UNIT_COLOR, ZERO_VEC3, IDENTITY_MATRIX);
        var legacySampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR, true);
        if (shadowPass != null) {
            drawShadowFaces(encoder, shadowPass, vertices, uniforms, block, transform, legacySampler, minecraft, gpuIndices, gpuIndexType);
            return;
        }
        if (customPass != null) {
            if (needsSceneDepth) sceneDepth.capture(encoder, target);
            if (needsSceneColor) isolatedSceneColor.capture(encoder, target, !capture.composing());
            drawCustomDepthPass(encoder, customPass, target, sceneInputs, vertices, uniforms, block, transform,
                    legacySampler, minecraft, gpuIndices, gpuIndexType, nanite);
            return;
        }
        if (!separate) {
            opaqueBatches.clear();
            translucentBatches.clear();
            boolean translucentNeedsSceneColor = false;
            for (int i = 0, n = drawing.size(); i < n; i++) {
                var batch = drawing.get(i);
                var material = batch.key.material();
                if (material.blend() == MaterialDefinition.Blend.OPAQUE) opaqueBatches.add(batch);
                else {
                    translucentBatches.add(batch);
                    if (material.sceneColor()) translucentNeedsSceneColor = true;
                }
            }
            if (!opaqueBatches.isEmpty()) drawDepthAware(encoder, "LumenFX opaque instances", target, opaqueBatches,
                    vertices, uniforms, block, transform, legacySampler, minecraft, gpuIndices, gpuIndexType);
            if (nanite != null) drawNanite(encoder, nanite, target, linearTarget, false, null, null, legacySampler, transform,
                    NaniteWorldRenderer.Surface::drawsInMainPass, true);
            if (translucentNeedsSceneColor)
                isolatedSceneColor.capture(encoder, target, !capture.composing());
            prepareTranslucencyLighting(encoder, frame, capture, modelView, cameraWorldPosition, volumeSettings, sceneTarget,
                    viewportWidth, viewportHeight, platform, atmosphere);
            if (!translucentBatches.isEmpty()) drawDepthAware(encoder, "LumenFX translucent instances", target, translucentBatches,
                    vertices, uniforms, block, transform, legacySampler, minecraft, gpuIndices, gpuIndexType);
            opaqueBatches.clear();
            translucentBatches.clear();
            return;
        }

        opaqueBatches.clear();
        for (int i = 0, n = drawing.size(); i < n; i++) {
            var batch = drawing.get(i);
            if (batch.key.material().blend() == MaterialDefinition.Blend.OPAQUE) opaqueBatches.add(batch);
        }
        final List<Batch> opaque = opaqueBatches;
        Surfaces particleProjection = null;
        if (deferredDecals) {
            var world = java.util.Objects.requireNonNull(surfaces, "Particle decals require captured scene surfaces");
            receiverBatches.clear();
            boolean receiverNeedsSceneDepth = false;
            for (int i = 0, n = opaque.size(); i < n; i++) {
                var batch = opaque.get(i);
                if (EffectFrame.writesOpaqueDepth(batch.key.material())) {
                    receiverBatches.add(batch);
                    if (batch.key.material().sceneDepth()) receiverNeedsSceneDepth = true;
                }
            }
            if (receiverNeedsSceneDepth) sceneDepth.capture(encoder, target);
            particleDecalDepth.begin(encoder, target, world);
            drawDecalReceiverGroups(encoder, target, receiverBatches, vertices, uniforms, block, transform,
                    legacySampler, minecraft, gpuIndices, gpuIndexType, decalNormalReprojection);
            if (nanite != null) drawNaniteDecalDepth(encoder, nanite, target, linearTarget, legacySampler, transform, decalNormalReprojection);
            receiverBatches.clear();
            particleProjection = particleDecalDepth.projection(world);
            prepareSceneDecals(frame, modelView, camera, target, cameraWorldPosition, world, particleProjection, sceneInputs);
            for (int i = 0, n = opaque.size(); i < n; i++) {
                var batch = opaque.get(i);
                if (hasDBuffer(batch.key.material(), true, batch.key.receivesDecals())) {
                    if (batch.cachedDBufferState == null)
                        batch.cachedDBufferState = states.computeIfAbsent(batch.state.key.withDBuffer(), this::createState);
                    batch.state = batch.cachedDBufferState;
                }
            }
        }
        boolean hasOpaqueSurfaceWriters = false;
        for (int i = 0, n = opaque.size(); i < n; i++) {
            if (writesOpaqueSurface(opaque.get(i).key.material())) {
                hasOpaqueSurfaceWriters = true;
                break;
            }
        }
        if (!hasOpaqueSurfaceWriters && nanite != null) {
            for (var surface : nanite.surfaces()) {
                if (surface.drawsInMainPass() && writesOpaqueSurface(surface.material())) {
                    hasOpaqueSurfaceWriters = true;
                    break;
                }
            }
        }
        var defaultOpaqueIndirectSample = captureParticleMaterials && nativeSurfaceCapture && hasOpaqueSurfaceWriters
                && opaqueIndirect && opaqueRequirements.defaultLit()
                && SceneIndirectDiffuse.hasOpaqueBasePassLighting(indirect, platform)
                ? SceneIndirectDiffuse.prepare(encoder, indirect, platform, cameraWorldPosition, alignment, false,
                        RenderSystem.getDevice().backend instanceof GlDevice ? target.height : 0) : null;
        boolean captureAnisotropy = captureParticleMaterials && hasOpaqueSurfaceWriters
                && java.util.Objects.requireNonNull(opaqueRequirements).anisotropic()
                && java.util.Objects.requireNonNull(NiagaraPlatforms.snapshot().console("r.AnisotropicMaterials")).bits() != 0;
        var particleSurfaces = captureParticleMaterials && hasOpaqueSurfaceWriters
                ? (surfaces != null
                    ? opaqueParticleSurfaces.begin(encoder, target, surfaces,
                        captureGBuffer || java.util.Objects.requireNonNull(opaqueRequirements).customData()
                                || deferredSky && DeferredSkyLighting.hasReflection(indirect),
                        captureAnisotropy,
                        opaqueRequirements.customData(), opaqueRequirements.particleLightingFeatures(),
                        opaqueRequirements.adaptiveSubstrateWords(), defaultOpaqueIndirectSample)
                    : opaqueParticleSurfaces.beginStandalone(encoder, target, true,
                        captureAnisotropy,
                        opaqueRequirements.customData(), opaqueRequirements.particleLightingFeatures())) : null;
        Map<SceneIndirectLighting, SceneIndirectDiffuse.@Nullable Sample> opaqueIndirectCache = null;
        if (particleSurfaces != null && nativeSurfaceCapture && opaqueIndirect && opaqueRequirements.defaultLit()) {
            opaqueIndirectCache = new HashMap<>();
            opaqueIndirectCache.put(indirect, defaultOpaqueIndirectSample);
            int glHeight = RenderSystem.getDevice().backend instanceof GlDevice ? target.height : 0;
            for (int i = 0, n = opaque.size(); i < n; i++) {
                var batch = opaque.get(i);
                var material = batch.key.material();
                if (!writesOpaqueSurface(material) || !OpaqueParticleSurfaces.acceptsIndirectDiffuse(material)) {
                    batch.opaqueIndirectDiffuse = null;
                    continue;
                }
                var batchIndirect = materialIndirectLighting(material, sceneTarget);
                if (opaqueIndirectCache.containsKey(batchIndirect)) {
                    batch.opaqueIndirectDiffuse = opaqueIndirectCache.get(batchIndirect);
                } else {
                    var sample = SceneIndirectDiffuse.hasOpaqueBasePassLighting(batchIndirect, platform)
                            ? SceneIndirectDiffuse.prepare(encoder, batchIndirect, platform, cameraWorldPosition, alignment, false, glHeight)
                            : null;
                    opaqueIndirectCache.put(batchIndirect, sample);
                    batch.opaqueIndirectDiffuse = sample;
                }
            }
            if (nanite != null) {
                for (var surface : nanite.surfaces()) {
                    var material = surface.material();
                    if (!surface.drawsInMainPass() || !OpaqueParticleSurfaces.acceptsIndirectDiffuse(material)) continue;
                    var surfaceIndirect = materialIndirectLighting(material, sceneTarget);
                    if (!opaqueIndirectCache.containsKey(surfaceIndirect)) {
                        var sample = SceneIndirectDiffuse.hasOpaqueBasePassLighting(surfaceIndirect, platform)
                                ? SceneIndirectDiffuse.prepare(encoder, surfaceIndirect, platform, cameraWorldPosition, alignment, false, glHeight)
                                : null;
                        opaqueIndirectCache.put(surfaceIndirect, sample);
                    }
                }
            }
        }
        final Map<SceneIndirectLighting, SceneIndirectDiffuse.@Nullable Sample> naniteOpaqueIndirect = opaqueIndirectCache;
        boolean secondDepth = false;
        if (splitOpaqueDepth && nanite != null) {
            for (var surface : nanite.surfaces()) {
                if (surface.readsPartialDepth()) {
                    secondDepth = true;
                    break;
                }
            }
        }
        final boolean naniteSecondDepth = secondDepth;
        var instanceVertices = vertices;
        var spriteIndices = gpuIndices;
        var spriteIndexType = gpuIndexType;
        OpaqueParticleDepthPass.draw(opaque, splitOpaqueDepth, Batch::readsPartialDepth, naniteSecondDepth,
                (stageList, secondary, finishMeshes) -> {
                    if (!stageList.isEmpty())
                        drawDepthAware(encoder, secondary ? "LumenFX secondary opaque instances" : "LumenFX opaque instances",
                                target, stageList, instanceVertices, uniforms, block, transform, legacySampler, minecraft,
                                spriteIndices, spriteIndexType, false, particleSurfaces);
                    if (nanite != null && (!secondary || naniteSecondDepth))
                        drawNanite(encoder, nanite, target, linearTarget, true, particleSurfaces, naniteOpaqueIndirect, legacySampler, transform,
                                surface -> surface.drawsInMainPass() && (!splitOpaqueDepth || surface.readsPartialDepth() == secondary),
                                finishMeshes);
                },
                () -> capture.capturePartialDepth(encoder, target));
        opaqueBatches.clear();
        if (opaqueDepthChanged) capture.noteOpaqueParticleDepthWrite();
        if (captureGBuffer && particleSurfaces != null) worldCapture.particleGBuffer(particleSurfaces);
        if (opaqueDirectional && particleSurfaces != null && opaqueRequirements.defaultLit()) {
            var projection = scratchLightProjection;
            worldCapture.copyProjection(projection);
            var unjittered = scratchUnjitteredProjection;
            worldCapture.copyUnjitteredProjection(unjittered);
            var main = SceneDirectionalLight.selectForward(frame.directionalLights());
            for (var light : frame.directionalLights()) {
                if (light.lightingChannels() == 0) continue;
                var shadows = light == main ? worldCapture.forwardDirectionalShadows()
                        : volumeDirectionalShadows.prepare(platform, light, target, unjittered, modelView,
                                cameraWorldPosition, worldCapture.nearClipCm(), directionalCasters);
                directionalParticleLighting.draw(encoder, target, particleSurfaces, projection, modelView, light, shadows, linearTarget);
            }
        }
        if (sceneOutputs && sceneTarget) {
            var postBase = finishSceneDecals(target, modelView, surfaces, particleProjection, sceneInputs, afterBaseDepth);
            if (customSceneTextures != 0)
                bindMaterialCustomTextures(postBase, target, customSceneTextures, false);
        }
        if (lateLighting)
            drawSceneLights(frame, modelView, camera, target, cameraWorldPosition, surfaces,
                    particleSurfaces,
                    particleSurfaces == null ? 1 : 1 | java.util.Objects.requireNonNull(opaqueRequirements).lightChannels(), particleCasters,
                    translucentLocalLights, forwardLightFunctions, forwardLocalShadows, sceneInputs);

        prepareTranslucencyLighting(encoder, frame, capture, modelView, cameraWorldPosition, volumeSettings, sceneTarget,
                viewportWidth, viewportHeight, platform, atmosphere);

        boolean orderedBeforeDofNeeded = drawing.stream().anyMatch(batch -> gammaBatch(batch.key.material()));
        translucentBatches.clear();
        boolean sceneBlendDepthChanged = false;
        for (int i = 0, n = drawing.size(); i < n; i++) {
            var batch = drawing.get(i);
            var material = batch.key.material();
            if (material.blend() != MaterialDefinition.Blend.OPAQUE && !isSeparateLayerSafe(material)
                    && !(orderedBeforeDofNeeded && material.translucencyPass() == TranslucencyPass.BEFORE_DOF)) {
                translucentBatches.add(batch);
                sceneBlendDepthChanged |= material.depth().write()
                        && material.depth().compare() != MaterialDepth.Compare.DISABLED
                        && material.depth().compare() != MaterialDepth.Compare.NEVER;
            }
        }
        if (!translucentBatches.isEmpty()) {
            drawDepthAware(encoder, "LumenFX scene-blend translucency", target, translucentBatches, vertices,
                    uniforms, block, transform, legacySampler, minecraft, gpuIndices, gpuIndexType);
            translucentBatches.clear();
            if (sceneBlendDepthChanged) {
                opaqueDepthChanged = true;
                capture.noteOpaqueParticleDepthWrite();
            }
        }

        if (orderedBeforeDofNeeded && drawOrderedBeforeDof(encoder, target, vertices, uniforms, block, transform,
                legacySampler, minecraft, gpuIndices, gpuIndexType)) {
            opaqueDepthChanged = true;
            capture.noteOpaqueParticleDepthWrite();
        }
        boolean sceneDepthChanged = opaqueDepthChanged;
        if (needsSceneColor)
            capture.prepareMaterialSceneColor();
        for (TranslucencyPass stage : TranslucencyPass.values()) {
            if (orderedBeforeDofNeeded && stage == TranslucencyPass.BEFORE_DOF) continue;
            stageBatches.clear();
            modulationBatches.clear();
            boolean stageDepthChanged = false;
            boolean modulationDepthChanged = false;
            boolean orderedMultiply = false;
            for (int i = 0, n = drawing.size(); i < n; i++) {
                var batch = drawing.get(i);
                var material = batch.key.material();
                if (material.blend() == MaterialDefinition.Blend.OPAQUE || !isSeparateLayerSafe(material)
                        || material.translucencyPass() != stage) continue;
                boolean writesDepth = material.depth().write()
                        && material.depth().compare() != MaterialDepth.Compare.DISABLED
                        && material.depth().compare() != MaterialDepth.Compare.NEVER;
                if (material.blend() == MaterialDefinition.Blend.MODULATE) {
                    modulationBatches.add(batch);
                    if (batch.orderedMultiply()) {
                        // Multiply applies to the accumulated transparent RGB as well as to the background.
                        stageBatches.add(batch);
                        orderedMultiply = true;
                        stageDepthChanged |= writesDepth;
                    } else if (writesDepth) modulationDepthChanged = true;
                } else {
                    stageBatches.add(batch);
                    if (writesDepth) stageDepthChanged = true;
                    if (stage != TranslucencyPass.AFTER_MOTION_BLUR && batch.state.dualSource) {
                        if (batch.cachedModulationState == null)
                            batch.cachedModulationState = states.computeIfAbsent(batch.state.key.modulation(), this::createState);
                        batch.modulationState = batch.cachedModulationState;
                        modulationBatches.add(batch);
                    }
                }
            }
            if (stageBatches.isEmpty() && modulationBatches.isEmpty()) continue;
            for (int i = 0, n = stageBatches.size(); i < n; i++) prepareVolumeFog(capture, stageBatches.get(i).key.material());
            for (int i = 0, n = modulationBatches.size(); i < n; i++) prepareVolumeFog(capture, modulationBatches.get(i).key.material());
            RenderTarget stageTarget = capture.beginTranslucency(sceneDepthChanged, stageDepthChanged || modulationDepthChanged, stage);
            if (orderedMultiply && (stageDepthChanged || modulationDepthChanged)) {
                drawTranslucencyWithDepth(encoder, stage, stageTarget, vertices, uniforms, block, transform,
                        legacySampler, minecraft, gpuIndices, gpuIndexType);
                modulationBatches.removeIf(Batch::orderedMultiply);
            } else if (!stageBatches.isEmpty()) {
                drawDepthAware(encoder, "LumenFX " + stage + " instances", stageTarget, stageBatches, vertices,
                        uniforms, block, transform, legacySampler, minecraft, gpuIndices, gpuIndexType);
            }
            if (!modulationBatches.isEmpty()) {
                RenderTarget modulationTarget = capture.beginTranslucencyModulation(stage, stageTarget);
                drawDepthAware(encoder, "LumenFX " + stage + " modulation", modulationTarget, modulationBatches, vertices,
                        uniforms, block, transform, legacySampler, minecraft, gpuIndices, gpuIndexType, true);
                if (modulationDepthChanged) stageTarget.copyDepthFrom(modulationTarget);
                stageDepthChanged |= modulationDepthChanged;
            }
            if (stageDepthChanged) capture.noteTranslucencyDepthWrite(stageTarget);
            sceneDepthChanged = stageDepthChanged;
            stageBatches.clear();
            modulationBatches.clear();
        }
    }

    private static boolean gammaBatch(MaterialDefinition material) {
        return material.blend().translucent() && material.translucencyPass() == TranslucencyPass.BEFORE_DOF
                && material.renderSettings().blendColorSpace() == RenderSettings.BlendColorSpace.UNITY_GAMMA;
    }

    private boolean drawOrderedBeforeDof(CommandEncoder encoder, RenderTarget linear, GpuBufferSlice vertices,
                                         GpuBufferSlice uniforms, int block, GpuBufferSlice transform,
                                         GpuSampler sampler, Minecraft minecraft, GpuBuffer indices, IndexType indexType) {
        var capture = WorldHdrCapture.INSTANCE;
        boolean needsColorInput = false;
        for (var batch : drawing) {
            var material = batch.key.material();
            if (!material.blend().translucent() || material.translucencyPass() != TranslucencyPass.BEFORE_DOF) continue;
            orderedBeforeDof.add(batch);
            needsColorInput |= material.sceneColor();
        }
        try {
            if (needsColorInput) isolatedSceneColor.capture(encoder, linear, false);
            boolean writesDepth = false;
            int begin = 0;
            while (begin < orderedBeforeDof.size()) {
                var material = orderedBeforeDof.get(begin).key.material();
                boolean gamma = gammaBatch(material);
                boolean floating = gamma && material.renderSettings().hdr();
                int end = begin + 1;
                while (end < orderedBeforeDof.size()) {
                    var next = orderedBeforeDof.get(end).key.material();
                    if (gamma != gammaBatch(next) || (gamma && floating != next.renderSettings().hdr())) break;
                    end++;
                }
                var run = orderedBeforeDof.subList(begin, end);
                var destination = gamma ? gammaBlendTargets.begin(linear, floating) : linear;
                GpuFormat format = destination.getColorTexture().getFormat();
                var original = new ArrayList<DrawState>(run.size());
                boolean runWritesDepth = false;
                try {
                    for (var batch : run) {
                        var m = batch.key.material();
                        prepareVolumeFog(capture, m);
                        runWritesDepth |= ParticleMotionPassPlan.writesTranslucentColorDepth(m);
                        original.add(batch.state);
                        batch.state = states.computeIfAbsent(batch.state.key.directlyTo(format, gamma), this::createState);
                    }
                    drawDepthAware(encoder, gamma ? "LumenFX Gamma particle run" : "LumenFX linear particle run",
                            destination, run, vertices, uniforms, block, transform, sampler, minecraft, indices, indexType);
                } finally {
                    for (int i = 0; i < original.size(); i++) run.get(i).state = original.get(i);
                }
                if (gamma) gammaBlendTargets.composite(destination, linear, runWritesDepth);
                writesDepth |= runWritesDepth;
                begin = end;
            }
            return writesDepth;
        } finally {
            orderedBeforeDof.clear();
        }
    }

    private void drawTranslucencyWithDepth(CommandEncoder encoder, TranslucencyPass stage, RenderTarget stageTarget,
                                           GpuBufferSlice vertices, GpuBufferSlice uniforms, int block, GpuBufferSlice transform,
                                           GpuSampler legacySampler, Minecraft minecraft, GpuBuffer indices, IndexType indexType) {
        var capture = WorldHdrCapture.INSTANCE;
        String label = "LumenFX " + stage + " instances";
        String modulationLabel = "LumenFX " + stage + " modulation";
        int first = 0;
        for (int i = 0; i < stageBatches.size(); i++) {
            var batch = stageBatches.get(i);
            if (!batch.orderedMultiply()) continue;
            if (i > first)
                drawDepthAware(encoder, label, stageTarget, stageBatches.subList(first, i), vertices, uniforms, block,
                        transform, legacySampler, minecraft, indices, indexType);
            // Both draws of Multiply must start with the depth at its position in the sorted stream.
            var modulationTarget = capture.beginTranslucencyModulation(stage, stageTarget);
            drawDepthAware(encoder, modulationLabel, modulationTarget, List.of(batch), vertices, uniforms, block,
                    transform, legacySampler, minecraft, indices, indexType, true);
            first = i;
        }
        drawDepthAware(encoder, label, stageTarget, stageBatches.subList(first, stageBatches.size()), vertices, uniforms,
                block, transform, legacySampler, minecraft, indices, indexType);
    }

    private void prepareTranslucencyLighting(CommandEncoder encoder, EffectFrame frame, WorldHdrCapture capture,
                                             Matrix4fc view, Vec3 camera, TranslucencyLightingVolume.@Nullable Settings settings,
                                             boolean sceneTarget, int viewportWidth, int viewportHeight,
                                             NiagaraPlatformEnvironment platform, NiagaraSceneInputs.@Nullable Atmosphere atmosphere) {
        boolean volume = false;
        boolean forward = false;
        boolean surfaceVolume = false;
        boolean hasBlendedCaptureMat = false;
        boolean usesVolumeIndirect = false;
        boolean perPixel = false;
        for (int i = 0, n = drawing.size(); i < n; i++) {
            var batch = drawing.get(i);
            var material = batch.key.material();
            if (material.usesTranslucencyVolume()) volume = true;
            if (material.usesForwardTranslucency()) forward = true;
            if (material.translucencyVolume() == MaterialDefinition.TranslucencyVolume.SURFACE_VOLUME) surfaceVolume = true;
            var stateMat = batch.state.key.material();
            if (stateMat.translucencyVolume().usesSurfaceEnvironment() && !stateMat.singleLocalCapture()) hasBlendedCaptureMat = true;
            if (material.usesVolumeIndirect()) usesVolumeIndirect = true;
            if (material.samplesTranslucencyPerPixel()) perPixel = true;
        }
        if (!volume && !forward) return;
        var materialProjection = scratchMaterialProjection;
        capture.copyProjection(materialProjection);
        var shaderpack = capture.shaderpackFrame();
        var sceneLights = sceneTarget ? capture.sceneLights() : frame.lights();
        float exposure = capture.eyeAdaptationExposure();
        boolean glViewport = RenderSystem.getDevice().backend instanceof GlDevice;
        if (forward || surfaceVolume) {
            int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
            var components = sceneTarget ? capture.sceneComponentLights() : frame.pointLights();
            var directional = sceneTarget ? capture.sceneDirectionalLights() : frame.directionalLights();
            var envCache = new HashMap<SceneIndirectLighting, SurfaceTranslucencyEnvironment>();
            var forwardCache = new HashMap<SceneIndirectLighting, ForwardTranslucentLights.Sample>();
            for (int i = 0, n = drawing.size(); i < n; i++) {
                var batch = drawing.get(i);
                var material = batch.key.material();
                boolean batchForward = material.usesForwardTranslucency();
                boolean batchSurfaceVolume = material.translucencyVolume() == MaterialDefinition.TranslucencyVolume.SURFACE_VOLUME;
                if (!batchForward && !batchSurfaceVolume) continue;
                var batchIndirect = materialIndirectLighting(material, sceneTarget);
                var captures = batchIndirect.reflectionCaptures();
                var env = envCache.get(batchIndirect);
                if (env == null) {
                    boolean batchBlendedCaptures = captures != null && hasBlendedCaptureMat;
                    var reflectionCaptures = localReflections.prepare(encoder, captures, platform, camera, alignment,
                            capture.renderFrameSequence());
                    var lightingGrid = (forward || batchBlendedCaptures) ? forwardTranslucency.prepareGrid(platform, sceneLights, components,
                            materialProjection, view, camera, viewportWidth, viewportHeight, capture.nearClipCm(), captures) : null;
                    var reflectionGrid = !batchBlendedCaptures || lightingGrid == null ? null
                            : forwardTranslucency.prepareReflections(encoder, lightingGrid, view, glViewport);
                    env = SurfaceTranslucencyEnvironment.prepare(encoder, batchIndirect, platform, camera, alignment,
                            reflectionCaptures, reflectionGrid);
                    envCache.put(batchIndirect, env);
                    if (forward) {
                        var fwd = forwardTranslucency.prepare(encoder, platform, sceneLights,
                                java.util.Objects.requireNonNull(lightingGrid), directional, view, camera, exposure, glViewport,
                                sceneTarget ? capture.forwardLightFunctions() : null, sceneTarget ? capture.forwardDirectionalShadows() : null,
                                defaultMaterialSceneTexture(true), sceneTarget ? pointFunctions.volumeShadows() : Map.of(),
                                env, atmosphere);
                        forwardCache.put(batchIndirect, fwd);
                    }
                }
                batch.surfaceTranslucencyEnvironment = env;
                if (batchForward) batch.forwardTranslucencySample = forwardCache.get(batchIndirect);
                if (captures != null && batch.state.key.material().singleLocalCapture()) {
                    var primitive = materialPrimitive(batch);
                    Vec3 origin = primitive == null ? null : primitive.sourceBoundsOrigin();
                    if (origin == null && primitive != null) {
                        Vec3 position = primitive.current().position();
                        origin = new Vec3(position.x * 100, position.z * 100, position.y * 100);
                    }
                    int index = origin == null ? -1 : captures.closestTo(origin);
                    batch.primitiveCapture = SurfaceTranslucencyEnvironment.primitiveCapture(encoder, alignment, index);
                }
            }
            surfaceTranslucencyEnvironment = envCache.isEmpty() ? null : envCache.values().iterator().next();
            forwardTranslucencySample = forwardCache.isEmpty() ? null : forwardCache.values().iterator().next();
        }
        if (!volume) return;
        if (usesVolumeIndirect) {
            int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
            var indirectCache = new HashMap<SceneIndirectLighting, SceneIndirectDiffuse.Sample>();
            for (int i = 0, n = drawing.size(); i < n; i++) {
                var batch = drawing.get(i);
                if (!batch.key.material().usesVolumeIndirect()) continue;
                var batchIndirect = materialIndirectLighting(batch.key.material(), sceneTarget);
                batch.vertexTranslucencyIndirect = indirectCache.computeIfAbsent(batchIndirect, key ->
                        SceneIndirectDiffuse.prepare(encoder, key, platform, camera, alignment, true,
                                glViewport ? viewportHeight : 0));
            }
            vertexTranslucencyIndirect = indirectCache.isEmpty() ? null : indirectCache.values().iterator().next();
        }
        var projection = scratchTranslucencyProjection;
        capture.copyUnjitteredProjection(projection);
        var localLights = sceneTarget ? capture.translucencyLocalLights() : new LocalLights(frame.pointLights(), Map.of(), null, Map.of());
        var directionalLights = sceneTarget ? capture.translucencyDirectionalLights()
                : new DirectionalLights(frame.directionalLights(), Map.of(), light -> null);
        var history = sceneTarget ? capture.translucencyViewHistory(Minecraft.getInstance().isPaused()) : null;
        translucencyLightingSample = translucencyLighting.prepare(encoder, view, projection, materialProjection, camera,
                java.util.Objects.requireNonNull(settings), sceneLights, localLights, directionalLights, defaultMaterialSceneTexture(true),
                exposure, history,
                perPixel, history == null ? 0 : history.frame(), glViewport ? viewportHeight : 0);
    }

    private void prepareSceneDecals(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                                    Vec3 cameraWorldPosition, @Nullable Surfaces world, @Nullable Surfaces particleProjection,
                                    NiagaraSceneInputs sceneInputs) {
        var capture = WorldHdrCapture.INSTANCE;
        var projection = scratchDecalProjection;
        capture.copyProjection(projection);
        var surfaces = capture.shaderpackFrame() == null
                ? java.util.Objects.requireNonNull(world, "Native decals require captured scene surfaces") : null;
        var projectionDepth = particleProjection == null ? target.getDepthTextureView()
                : particleDecalDepth.depthTarget().getDepthTextureView();
        var projected = decals.prepare(frame, projection, modelView, camera, target, cameraWorldPosition,
                capture.nearClipCm(), 1, capture.materialViewMipBias(), capture.materialResolutionFraction(),
                particleProjection != null ? particleProjection : surfaces, surfaces, sceneInputs, capture.decalNormalInput(projectionDepth));
        capture.sceneDecals(projected);
        if (projected != null) {
            if (surfaces != null)
                nativeDecalMaterials.resolve(java.util.Objects.requireNonNull(capture.nativeEncodedScene()), surfaces, projected, target,
                        modelView, particleProjection == null ? null : particleProjection.positionDepth());
            else resolveShaderpackDecalColor(capture, target, projected, null);
        }
    }

    private NiagaraSceneInputs finishSceneDecals(RenderTarget target, Matrix4fc modelView, @Nullable Surfaces world,
                                                @Nullable Surfaces particleProjection, NiagaraSceneInputs beforeBase,
                                                @Nullable Supplier<NiagaraSceneInputs> afterBaseDepth) {
        var inputs = afterBaseDepth == null ? beforeBase : afterBaseDepth.get();
        var ambient = decals.drawAfterBasePass(target, inputs);
        var capture = WorldHdrCapture.INSTANCE;
        capture.sceneDecalAo(ambient);
        var projected = capture.sceneDecals();
        if (ambient != null && projected != null) {
            if (capture.shaderpackFrame() != null)
                resolveShaderpackDecalColor(capture, target, projected, ambient);
            else if (world != null)
                nativeDecalMaterials.resolveAmbient(java.util.Objects.requireNonNull(capture.nativeEncodedScene()), world,
                        projected, target, modelView, particleProjection == null ? null : particleProjection.positionDepth(), ambient);
        }
        return inputs;
    }

    private void resolveShaderpackDecalColor(WorldHdrCapture capture, RenderTarget target,
                                            com.kltyton.lumenfx.client.render.decal.DecalBuffers.Frame projected,
                                            @Nullable GpuTextureView ambient) {
        var frame = capture.shaderpackFrame();
        if (frame == null || frame.materialSurfaces() == null) return;
        var encoded = java.util.Objects.requireNonNull(capture.shaderpackEncodedScene());
        shaderpackDecalColors.resolve(target, encoded, frame.materialSurfaces(), projected, frame.colourEncoding(), ambient);
    }

    private void bindMaterialCustomTextures(NiagaraSceneInputs scene, RenderTarget target, int mask, boolean basePass) {
        var textures = basePass && !scene.customDepthBeforeBasePass() ? NiagaraSceneInputs.EMPTY.textures() : scene.textures();
        materialCustomDepth = null;
        materialCustomStencil = null;
        if ((mask & 1) != 0) {
            var depth = textures.get(NiagaraSceneInputs.Texture.CUSTOM_DEPTH);
            materialCustomDepth = depth == null ? sceneDepthDefaults.depth(GpuFormat.D32_FLOAT) : requireMaterialViewport(depth, target);
        }
        if ((mask & 2) != 0) {
            var stencil = textures.get(NiagaraSceneInputs.Texture.CUSTOM_STENCIL);
            materialCustomStencil = stencil == null ? sceneDepthDefaults.stencil() : requireMaterialViewport(stencil, target);
        }
    }

    private static GpuTextureView requireMaterialViewport(GpuTextureView input, RenderTarget target) {
        if (input.getWidth(0) != target.width || input.getHeight(0) != target.height)
            throw new IllegalArgumentException("Particle material scene input and render viewport dimensions differ");
        return input;
    }

    private void bindMaterialDeferredTextures(NiagaraSceneInputs scene, RenderTarget target, int mask) {
        for (int slot = 0; slot < MATERIAL_DEFERRED_TEXTURES.length; slot++) {
            materialDeferred[slot] = null;
            if ((mask & 1 << slot) == 0) continue;
            var field = MATERIAL_DEFERRED_TEXTURES[slot];
            var input = scene.textures().get(field);
            materialDeferred[slot] = input == null ? defaultMaterialSceneTexture(field == NiagaraSceneInputs.Texture.SCREEN_SPACE_AO)
                    : requireMaterialViewport(input, target);
        }
    }

    private GpuTextureView defaultMaterialSceneTexture(boolean white) {
        var texture = white ? whiteMaterialScene : blackMaterialScene;
        if (texture == null) {
            texture = new OwnedColorTarget("LumenFX unbound material scene texture", 1, 1, GpuFormat.RGBA8_UNORM);
            try {
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture.getColorTexture(), new Vector4f(white ? 1f : 0f));
            } catch (RuntimeException | Error failure) {
                texture.closeOnFailure(failure);
                throw failure;
            }
            if (white) whiteMaterialScene = texture;
            else blackMaterialScene = texture;
        }
        return texture.getColorTextureView();
    }

    private void drawSceneLights(EffectFrame frame, Matrix4fc modelView, Quaternionf camera, RenderTarget target,
                                 Vec3 cameraWorldPosition, @Nullable Surfaces surfaces, OpaqueParticleSurfaces.@Nullable Frame opaqueMaterials,
                                 int receiverChannels, @Nullable ParticleCasters particleCasters, boolean translucentLocalLights,
                                 boolean forwardLightFunctions, boolean forwardLocalShadows, NiagaraSceneInputs sceneInputs) {
        var capture = WorldHdrCapture.INSTANCE;
        var projection = scratchLightProjection;
        capture.copyProjection(projection);
        var shaderpack = capture.shaderpackFrame();
        boolean sceneFog = capture.usesVolumeSceneLighting();
        boolean directionalFunction = PointLightFunctionRenderer.hasDirectionalFunctions(frame, sceneFog, translucentLocalLights, forwardLightFunctions);
        var lightProjection = frame.pointLights().isEmpty() && !directionalFunction ? null
                : new PointLightProjection(projection, modelView, cameraWorldPosition, target.width, target.height,
                        RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        var pointFrame = lightProjection == null ? frame
                : frame.withPointLights(frame.pointLights().stream().filter(light -> lightProjection.visible(light, receiverChannels)
                        || (sceneFog && light.data().volumetricScattering() > 0
                            || (translucentLocalLights || forwardLightFunctions || forwardLocalShadows)
                                && light.data().shader().affectsTranslucency())
                            && lightProjection.visibleBounds(light)).toList());
        var shaderpackMaterial = shaderpack == null || (frame.lights().isEmpty() && lightProjection == null)
                ? null : capture.resolveShaderpackSurfaces(shaderpack, projection);
        List<PointDraw> points = lightProjection == null ? List.of()
                : pointFunctions.prepare(pointFrame, lightProjection, projection, modelView, camera, cameraWorldPosition, target,
                        capture.materialViewMipBias(), capture.materialResolutionFraction(),
                        shaderpack == null ? surfaces : null, shaderpackMaterial,
                        capture.sceneDecals(), particleCasters, opaqueMaterials, sceneFog, translucentLocalLights,
                        forwardLightFunctions, forwardLocalShadows, sceneInputs.atmosphere()).stream()
                        .filter(draw -> lightProjection.visible(draw.light(), receiverChannels)).toList();
        try {
            if (shaderpack != null) {
                if (shaderpackMaterial != null) {
                    var surfaceEncoding = shaderpackMaterial.surfaceEncoding() != null
                            ? shaderpackMaterial.surfaceEncoding() : shaderpack.colourEncoding();
                    int encoding = surfaceEncoding.orDefaultForFormat(true).shaderCode();
                    float exposure = capture.eyeAdaptationExposure();
                    lights.draw(frame.lights(), shaderpackMaterial, target, projection, modelView,
                            cameraWorldPosition, exposure, 1, encoding);
                    if (lightProjection != null) lights.drawPoints(points, shaderpackMaterial, target, projection, modelView,
                            lightProjection.viewToSourceWorld(), cameraWorldPosition, exposure, 1, encoding);
                }
            } else if (surfaces != null) {
                var particleLighting = opaqueMaterials == null ? null : opaqueMaterials.f0Roughness();
                var anisotropy = opaqueMaterials == null ? null : opaqueMaterials.tangentAnisotropy();
                var platform = NiagaraPlatforms.snapshot();
                var encoder = RenderSystem.getDevice().createCommandEncoder();
                var ao = sceneInputs.textures().get(NiagaraSceneInputs.Texture.SCREEN_SPACE_AO);
                if (ao != null) requireMaterialViewport(ao, target);
                else ao = capture.sceneDecalAo();
                lights.draw(frame.lights(), surfaces, target, projection, modelView, cameraWorldPosition, 1, 1, 1,
                        capture.sceneDecals(), particleLighting, anisotropy, opaqueMaterials, ao);
                if (lightProjection != null) lights.drawPoints(points, surfaces, target, projection, modelView,
                        lightProjection.viewToSourceWorld(), cameraWorldPosition, 1, 1, 1,
                        capture.sceneDecals(), particleLighting, anisotropy, opaqueMaterials, ao);
                if (opaqueMaterials != null) {
                    var indirect = resolveOpaqueIndirectLighting(frame, null, capture.sceneIndirectLighting());
                    var reflectionCaptures = localReflections.prepare(encoder, indirect.reflectionCaptures(), platform, cameraWorldPosition,
                            RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), capture.renderFrameSequence());
                    deferredSkyLighting.draw(encoder, target, opaqueMaterials, projection, modelView,
                            indirect, platform, ao, lights, reflectionCaptures);
                }
                if (opaqueMaterials != null && opaqueMaterials.subsurfaceDiffuse() != null)
                    subsurfaceScattering.resolve(encoder, target, opaqueMaterials,
                            projection, modelView, cameraWorldPosition);
            }
            pointFunctions.renderVolumeCasters();
            if (sceneFog) capture.fogLocalLights(pointFrame.pointLights().stream()
                    .filter(light -> light.data().volumetricScattering() > 0).toList(),
                    pointFunctions.volumeShadows(), pointFunctions.atlasFrame(), pointFunctions.fogDirectionalMaterial());
            if (translucentLocalLights) capture.translucencyLocalLights(new LocalLights(pointFrame.pointLights(),
                    pointFunctions.volumeShadows(), pointFunctions.translucencyAtlasFrame(), pointFunctions.translucencyFunctions()));
            if (translucentLocalLights) capture.translucencyDirectionalLights(
                    capture.translucencyDirectionalLights().withMaterials(pointFunctions.directionalVolumeFunctions()));
            if (forwardLightFunctions) capture.forwardLightFunctions(pointFunctions.forwardAtlasFrame());
        } finally { pointFunctions.endFrame(); }
    }

    private static boolean hasDBuffer(MaterialDefinition material, boolean sceneTarget, boolean receivesDecals) {
        return receivesDecals && sceneTarget && material.receivesDecals() && material.dbufferTextures() != 0
                && WorldHdrCapture.INSTANCE.sceneDecals() != null;
    }

    private DrawState createState(Key key) { return new DrawState(key, nextPipeline++, vertexMaterials, pointShadow, customDepth, false); }

    private DrawState naniteState(MaterialDefinition material, GpuFormat format, boolean linearTarget,
                                  boolean dbufferLookup, boolean receivesDecals) {
        var key = new Key(material, EffectMesh.Kind.MESH, format, linearTarget, false, false, false,
                true, false, dbufferLookup, receivesDecals, Features.NONE, false);
        return naniteStates.computeIfAbsent(key,
                value -> new DrawState(value, nextPipeline++, vertexMaterials, pointShadow, customDepth, true));
    }

    private void drawNanite(CommandEncoder encoder, NaniteWorldRenderer.Frame frame, RenderTarget target, boolean linearTarget,
                            boolean sceneTarget, OpaqueParticleSurfaces.@Nullable Frame surfaces,
                            @Nullable Map<SceneIndirectLighting, SceneIndirectDiffuse.@Nullable Sample> opaqueIndirectSamples,
                            GpuSampler sampler, GpuBufferSlice transform, java.util.function.Predicate<NaniteWorldRenderer.Surface> accepts,
                            boolean commitHistory) {
        var depth = target.getDepthTextureView();
        if (depth == null) return;
        if (frame.surfaces().stream().filter(accepts).anyMatch(surface -> surface.material().sceneDepth())) sceneDepth.capture(encoder, target);
        if (frame.surfaces().stream().filter(accepts).anyMatch(surface -> surface.material().sceneColor()))
            isolatedSceneColor.capture(encoder, target, !WorldHdrCapture.INSTANCE.composing());

        var format = target.getColorTexture().getFormat();
        frame.draw(encoder, depth, accepts, commitHistory,
                rasterNaniteMaterials(encoder, format, linearTarget, sceneTarget, sampler, transform), (surface, bindings) -> {
            var material = surface.material();
            boolean dbuffer = hasDBuffer(material, sceneTarget, surface.receivesDecals());
            var state = naniteState(material, format, linearTarget, dbuffer, surface.receivesDecals());
            var pipeline = state.pipeline;
            var surfaceFrame = surfaces != null && writesOpaqueSurface(material) ? surfaces : null;
            boolean substrate = surfaceFrame != null && (material.particleLightingFeatures() & MaterialDefinition.SUBSTRATE_SURFACE_PACKING) != 0;
            SceneIndirectDiffuse.@Nullable Sample surfaceIndirect = surfaceFrame != null && opaqueIndirectSamples != null
                    && OpaqueParticleSurfaces.acceptsIndirectDiffuse(material)
                    ? opaqueIndirectSamples.get(materialIndirectLighting(material, sceneTarget))
                    : null;
            if (surfaceFrame != null) {
                if (surfaceFrame.adaptive() != null) for (var replay : surfaceFrame.adaptive().replays()) {
                    AdaptiveParticleBuffer.copyDepth(encoder, target, replay);
                    var variant = adaptiveParticleReplays.get(pipeline, material, replay, surfaceFrame.bottomNormal(),
                            surfaceFrame.tangentAnisotropy() != null, vertexMaterials);
                    try (var pass = encoder.createRenderPass(replay.descriptor())) {
                        resolveNaniteMaterial(pass, variant, state, material, bindings, sampler, transform,
                                substrate && material.adaptiveSubstrateWords() != 0 ? surfaceFrame : null, null);
                    }
                }
                String profile = material.subsurfaceProfile();
                int profileIndex = profile == null ? -1 : VfxEngine.INSTANCE.subsurfaceProfileIndex(profile);
                pipeline = opaqueParticleSurfaces.pipeline(pipeline, material,
                        surface.primitive() == null ? PrimitiveLighting.DEFAULT : surface.primitive().lighting(),
                        surfaceFrame, surfaceIndirect, surface.firstPerson(), profileIndex, vertexMaterials);
            }
            String label = "LumenFX Nanite material";
            try (var pass = surfaceFrame == null ? encoder.createRenderPass(() -> label, target.getColorTextureView(),
                    Optional.empty(), depth, OptionalDouble.empty()) : encoder.createRenderPass(surfaceFrame.pass(target, label))) {
                resolveNaniteMaterial(pass, pipeline, state, material, bindings, sampler, transform, substrate ? surfaceFrame : null,
                        surfaceIndirect);
            }
            drawNaniteMotion(encoder, depth, state, material, bindings, sampler, transform, frame.motionValid());
        });
    }

    private void drawNaniteDecalDepth(CommandEncoder encoder, NaniteWorldRenderer.Frame frame, RenderTarget target,
                                      boolean linearTarget, GpuSampler sampler, GpuBufferSlice transform, boolean writeMotion) {
        if (frame.surfaces().stream().anyMatch(surface -> surface.material().sceneDepth())) sceneDepth.capture(encoder, target);
        if (frame.surfaces().stream().anyMatch(surface -> surface.material().sceneColor()))
            isolatedSceneColor.capture(encoder, target, !WorldHdrCapture.INSTANCE.composing());
        var depth = java.util.Objects.requireNonNull(particleDecalDepth.depthTarget().getDepthTextureView());
        var format = target.getColorTexture().getFormat();
        frame.drawDepth(encoder, depth, null, false,
                surface -> surface.drawsInMainPass() && EffectFrame.writesOpaqueDepth(surface.material()),
                rasterNaniteMaterials(encoder, format, linearTarget, false, sampler, transform), (surface, bindings) -> {
            var material = surface.material();
            var state = naniteState(material, format, linearTarget, false, surface.receivesDecals());
            var pipeline = particleDecalDepth.pipeline(state.pipeline, surface.receivesDecals() && material.receivesDecals(), vertexMaterials);
            try (var pass = encoder.createRenderPass(particleDecalDepth.descriptor(target))) {
                resolveNaniteMaterial(pass, pipeline, state, material, bindings, sampler, transform, null, null);
            }
            if (writeMotion) drawNaniteMotion(encoder, depth, state, material, bindings, sampler, transform, frame.motionValid());
        });
    }

    private NaniteWorldRenderer.RasterResolve rasterNaniteMaterials(CommandEncoder encoder, GpuFormat format,
            boolean linearTarget, boolean sceneTarget, GpuSampler sampler, GpuBufferSlice transform) {
        return new NaniteWorldRenderer.RasterResolve() {
            @Override public void draw(NaniteWorldRenderer.Surface surface, RenderPassDescriptor target,
                    NaniteWorldRenderer.SurfaceBindings bindings, GpuBufferSlice command, boolean topDown) {
                var material = surface.material();
                boolean dbuffer = hasDBuffer(material, sceneTarget, surface.receivesDecals());
                var state = naniteState(material, format, linearTarget, dbuffer, surface.receivesDecals());
                boolean dither = java.util.Objects.requireNonNull(material.naniteSource()).ditherOpacityMask();
                var pipeline = naniteProgrammable.get(state.pipeline, topDown, dither, vertexMaterials);
                try (var pass = encoder.createRenderPass(target)) {
                    bindNaniteMaterial(pass, pipeline, state, material, bindings, sampler, transform, null, null);
                    if (dither) pass.bindTexture(NaniteProgrammableRasterPipelines.BLUE_NOISE, BlueNoiseTextures.scalarActive(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    pass.drawIndirect(command, 1);
                }
            }

            @Override public boolean bindSampled(ComputeBindings.Builder bindings, ShaderBinding.SampledTexture binding) {
                String name = binding.name();
                GpuTextureView view = switch (name) {
                    case NaniteProgrammableRasterPipelines.BLUE_NOISE -> BlueNoiseTextures.scalarActive();
                    case "SceneDepthSampler" -> naniteSceneDepth();
                    case "LfxSceneColor" -> naniteSceneColor();
                    case "LfxMaterialCustomDepth" -> java.util.Objects.requireNonNull(materialCustomDepth);
                    case "LfxMaterialCustomStencil" -> java.util.Objects.requireNonNull(materialCustomStencil);
                    default -> null;
                };
                if (view == null) for (int slot = 0; slot < MATERIAL_DEFERRED_SAMPLERS.length; slot++) {
                    if (!MATERIAL_DEFERRED_SAMPLERS[slot].equals(name)) continue;
                    view = java.util.Objects.requireNonNull(materialDeferred[slot]);
                    break;
                }
                if (view == null) return false;
                bindings.texture(name, view, RenderSystem.getSamplerCache().getClampToEdge(
                        name.equals("LfxSceneColor") ? FilterMode.LINEAR : FilterMode.NEAREST));
                return true;
            }
        };
    }

    private void drawNaniteMotion(CommandEncoder encoder, GpuTextureView depth, DrawState state, MaterialDefinition material,
                                  NaniteWorldRenderer.SurfaceBindings bindings, GpuSampler sampler, GpuBufferSlice transform,
                                  boolean historyValid) {
        var capture = WorldHdrCapture.INSTANCE;
        var targets = capture.particleMotionTargets();
        if (targets == null || !material.writesVelocity()) return;
        if (motionShaders == null) motionShaders = new MotionShaderAdapter(vertexMaterials);
        var pipeline = state.naniteMotionPipeline;
        if (pipeline == null) pipeline = state.naniteMotion(motionShaders, nextPipeline++);
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX Nanite velocity coverage")
                .withColorAttachment(targets.previousNdc()).withColorAttachment(targets.depthCoverage())
                .withColorAttachment(Objects.requireNonNull(targets.motionOffset()))
                .withDepthAttachment(depth).withRenderArea(new RenderPass.RenderArea(0, 0, targets.width(), targets.height()));
        try (var pass = encoder.createRenderPass(descriptor)) {
            resolveNaniteMaterial(pass, pipeline, state, material, bindings, sampler, transform, null, null);
        }
        capture.recordParticleMotionHistory(historyValid);
    }

    private void resolveNaniteMaterial(RenderPass pass, RenderPipeline pipeline, DrawState state, MaterialDefinition material,
                                       NaniteWorldRenderer.SurfaceBindings bindings, GpuSampler sampler, GpuBufferSlice transform,
                                       OpaqueParticleSurfaces.@Nullable Frame substrate,
                                       SceneIndirectDiffuse.@Nullable Sample indirect) {
        bindNaniteMaterial(pass, pipeline, state, material, bindings, sampler, transform, substrate, indirect);
        pass.draw(3, 1, 0, 0);
    }

    private void bindNaniteMaterial(RenderPass pass, RenderPipeline pipeline, DrawState state, MaterialDefinition material,
                                    NaniteWorldRenderer.SurfaceBindings bindings, GpuSampler sampler, GpuBufferSlice transform,
                                    OpaqueParticleSurfaces.@Nullable Frame substrate,
                                    SceneIndirectDiffuse.@Nullable Sample indirect) {
        pass.setPipeline(pipeline);
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", transform);
        bindOpaqueIndirect(pass, material, indirect);
        if (substrate != null) pass.setUniform("LfxSubstratePacking", java.util.Objects.requireNonNull(substrate.substratePacking()));
        bindings.bind(pass);
        bindMaterialFog(pass, material);
        if (material.sceneDepth())
            pass.bindTexture("SceneDepthSampler", naniteSceneDepth(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        if (material.sceneColor())
            pass.bindTexture("LfxSceneColor", naniteSceneColor(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        bindMaterialSceneBuffers(pass, material, state.dbufferLookup);
        bindMaterialTextures(pass, material, state, sampler);
    }

    private GpuTextureView naniteSceneDepth() {
        return pointShadow ? sceneDepthDefaults.depth(GpuFormat.D32_FLOAT) : sceneDepth.view();
    }

    private static void bindOpaqueIndirect(RenderPass pass, MaterialDefinition material,
                                           SceneIndirectDiffuse.@Nullable Sample indirect) {
        if (indirect == null) return;
        indirect.bind(pass);
        if ((material.particleLightingFeatures() & MaterialDefinition.SUBSTRATE_TOON) != 0) ToonProfileBindings.bind(pass);
    }

    private GpuTextureView naniteSceneColor() {
        return pointShadow ? defaultMaterialSceneTexture(false) : isolatedSceneColor.view();
    }

    private static void prepareVolumeFog(WorldHdrCapture scene, MaterialDefinition material) {
        if (material.appliesBasePassFog() && material.renderSettings().fog() instanceof VolumetricFog fog)
            scene.prepareSceneFog(fog);
    }

    private static boolean hasHeightFog(MaterialDefinition material) {
        return material.appliesBasePassFog() && material.renderSettings().fog() instanceof ExponentialHeightFog;
    }

    private void prepareHeightFog(CommandEncoder encoder, MaterialDefinition material) {
        if (material.appliesBasePassFog() && material.renderSettings().fog() instanceof ExponentialHeightFog sourceFog)
            fogUniforms.computeIfAbsent(sourceFog, fog -> HeightFogUniforms.write(encoder, fog, fogView));
    }

    private void bindMaterialFog(RenderPass pass, MaterialDefinition material) {
        if (!depthOnly && material.appliesBasePassFog()) {
            if (material.renderSettings().fog() instanceof VolumetricFog sourceVolume) {
                var volume = WorldHdrCapture.INSTANCE.volumeFrame(sourceVolume);
                pass.setUniform("ExponentialFog", volume.heightUniforms());
                pass.setUniform("FogVolume", volume.uniforms());
                pass.bindTexture("FogVolumeSampler", volume.integrated(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            } else if (material.renderSettings().fog() instanceof ExponentialHeightFog sourceFog)
                pass.setUniform("ExponentialFog", fogUniforms.get(sourceFog));
            else pass.setUniform("Fog", WorldHdrCapture.INSTANCE.worldFog());
        }
    }

    private void bindMaterialSceneBuffers(RenderPass pass, MaterialDefinition material, boolean dbufferLookup) {
        AtmosphereMaterialTextures.bind(pass, material.materialView(), materialAtmosphere);
        int customSceneTextures = material.customSceneTextures();
        if ((customSceneTextures & 1) != 0)
            pass.bindTexture("LfxMaterialCustomDepth", java.util.Objects.requireNonNull(materialCustomDepth),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        if ((customSceneTextures & 2) != 0)
            pass.bindTexture("LfxMaterialCustomStencil", java.util.Objects.requireNonNull(materialCustomStencil),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        for (int slot = 0; slot < MATERIAL_DEFERRED_TEXTURES.length; slot++) {
            if ((material.deferredSceneTextures() & 1 << slot) == 0) continue;
            pass.bindTexture(MATERIAL_DEFERRED_SAMPLERS[slot], java.util.Objects.requireNonNull(materialDeferred[slot]),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
        if (dbufferLookup) {
            var buffers = java.util.Objects.requireNonNull(WorldHdrCapture.INSTANCE.sceneDecals(), "Material DBuffer inputs are no longer available for this scene");
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            for (var texture : DBufferTexture.ALL) if ((material.dbufferTextures() & texture.mask()) != 0)
                pass.bindTexture(texture.sampler(), buffers.texture(texture), sampler);
        }
    }

    private void bindMaterialTextures(RenderPass pass, MaterialDefinition material, DrawState state, GpuSampler sampler) {
        com.kltyton.lumenfx.client.render.texture.MaterialTextureBindings.bind(pass, material, sampler,
                pointShadow ? 0f : WorldHdrCapture.INSTANCE.materialViewMipBias());
        SpecularProfileBindings.bind(pass, !material.specularProfiles().isEmpty());
        if (state.key.material().adaptiveProfile()) ParticleLightRenderer.bindSubsurfaceProfiles(pass);
        if (state.reflectionEnergy()) ParticleLightRenderer.bindReflectionEnergy(pass, materialReflectionEnergy);
        if (state.slabTreeLighting.fuzz()) ParticleLightRenderer.bindSheenLtc(pass);
    }

    private void drawShadowFaces(CommandEncoder encoder, ShadowPass shadow, GpuBufferSlice vertices,
                                 GpuBufferSlice uniforms, int block, GpuBufferSlice transform, GpuSampler sampler,
                                 Minecraft minecraft, GpuBuffer gpuIndices, IndexType gpuIndexType) {
        if (shadowSceneTexture == null && drawing.stream().anyMatch(batch -> batch.key.material().sceneDepth() || batch.key.material().sceneColor())) {
            var next = new OwnedColorTarget("LumenFX point shadow unbound scene texture", 1, 1, GpuFormat.RGBA8_UNORM);
            try { encoder.clearColorTexture(next.getColorTexture(), new Vector4f()); }
            catch (RuntimeException | Error failure) { next.closeOnFailure(failure); throw failure; }
            shadowSceneTexture = next;
        }
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        int stride = Math.multiplyExact(Math.ceilDiv(64, alignment), alignment);
        GpuBufferSlice clips;
        try (var mapped = encoder.transientMemory().allocateGpuMapped((long) stride * (shadow.endView() - shadow.firstView()), alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            for (int face = shadow.firstView(); face < shadow.endView(); face++)
                shadow.view().clip(shadow.matrices(), face).get((face - shadow.firstView()) * stride, data);
            clips = mapped.slice();
        }
        var spotCaster = SpotShadowCaster.uniform(encoder, shadow.matrices());
        for (int face = shadow.firstView(); face < shadow.endView(); face++) {
            int layer = face;
            var directionalCaster = DirectionalShadowCaster.uniform(encoder, shadow.matrices(), face);
            try (var pass = encoder.createRenderPass(shadow.target().descriptor(layer, () -> "LumenFX particle shadow view " + layer))) {
                drawBatches(pass, drawing, vertices, uniforms, block, transform, sampler, minecraft, gpuIndices, gpuIndexType,
                        false, false, null, new ShadowDraw(clips.slice((long) (layer - shadow.firstView()) * stride, 64),
                                spotCaster, directionalCaster), false, null);
            }
        }
    }

    private void drawCustomDepthPass(CommandEncoder encoder, CustomDepthPass custom, RenderTarget scene, NiagaraSceneInputs inputs,
                                    GpuBufferSlice vertices, GpuBufferSlice uniforms, int block, GpuBufferSlice transform,
                                    GpuSampler sampler, Minecraft minecraft, GpuBuffer gpuIndices, IndexType gpuIndexType,
                                    NaniteWorldRenderer.@Nullable Frame nanite) {
        var descriptor = custom.targets().begin(encoder, scene, inputs, custom.stencil());
        Map<Integer, GpuBufferSlice> references = new HashMap<>();
        if (custom.stencil()) {
            int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
            for (var batch : drawing) {
                int value = java.util.Objects.requireNonNull(materialPrimitive(batch)).customDepthStencil().stencil();
                if (references.containsKey(value)) continue;
                try (var mapped = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
                    mapped.data().order(ByteOrder.nativeOrder()).putInt(value).putInt(0).putInt(0).putInt(0);
                    references.put(value, mapped.slice());
                }
            }
        }
        try (var pass = encoder.createRenderPass(descriptor)) {
            for (var batch : drawing) {
                var primitive = java.util.Objects.requireNonNull(materialPrimitive(batch));
                var source = primitive.customDepthStencil();
                var pose = primitive.current();
                boolean reverseCull = batch.state.pipeline.isCull() && mirroredComponent(batch, pose);
                int mask = custom.stencil() ? source.writeMask().bits() : 0;
                var reference = references.get(source.stencil());
                var group = List.of(batch);
                if (mask != 0 && source.writeMask().ignoresDepth()) {
                    var stencil = customDepthPipelines.get(batch.state.pipeline, true, false, mask, reverseCull, vertexMaterials);
                    drawBatches(pass, group, vertices, uniforms, block, transform, sampler, minecraft, gpuIndices, gpuIndexType,
                            false, false, null, null, false, new CustomDepthDraw(stencil, reference));
                    mask = 0;
                }
                var depth = customDepthPipelines.get(batch.state.pipeline, custom.stencil(), true, mask, reverseCull, vertexMaterials);
                drawBatches(pass, group, vertices, uniforms, block, transform, sampler, minecraft, gpuIndices, gpuIndexType,
                        false, false, null, null, false, new CustomDepthDraw(depth, mask == 0 ? null : reference));
            }
        }
        if (nanite != null) {
            if (custom.stencil()) drawNaniteCustomDepth(encoder, custom, nanite, transform, sampler, true);
            drawNaniteCustomDepth(encoder, custom, nanite, transform, sampler, false);
        }
        custom.targets().resolveStencil(encoder);
    }

    private void drawNaniteCustomDepth(CommandEncoder encoder, CustomDepthPass custom, NaniteWorldRenderer.Frame nanite,
                                       GpuBufferSlice transform, GpuSampler sampler, boolean stencilOnly) {
        nanite.drawDepth(encoder, custom.targets().depth(), custom.projection(), stencilOnly, surface ->
                surface.writesCustomDepth() && (!stencilOnly || java.util.Objects.requireNonNull(surface.primitive())
                        .customDepthStencil().writeMask().ignoresDepth()),
                rasterNaniteMaterials(encoder, GpuFormat.R8_UNORM, false, false, sampler, transform), (surface, bindings) -> {
            var material = surface.material();
            var source = java.util.Objects.requireNonNull(surface.primitive()).customDepthStencil();
            int mask = custom.stencil() && stencilOnly == source.writeMask().ignoresDepth() ? source.writeMask().bits() : 0;
            GpuBufferSlice reference = null;
            if (mask != 0) {
                int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
                try (var mapped = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
                    mapped.data().order(ByteOrder.nativeOrder()).putInt(source.stencil()).putInt(0).putInt(0).putInt(0);
                    reference = mapped.slice();
                }
            }
            var state = naniteState(material, GpuFormat.R8_UNORM, false, false, surface.receivesDecals());
            var pipeline = customDepthPipelines.get(state.pipeline, custom.stencil(), !stencilOnly, mask, false, vertexMaterials);
            try (var pass = encoder.createRenderPass(custom.targets().descriptor("LumenFX Nanite custom depth"))) {
                bindNaniteMaterial(pass, pipeline, state, material, bindings, sampler, transform, null, null);
                if (reference != null) pass.setUniform("CustomStencilPrimitive", reference);
                pass.draw(3, 1, 0, 0);
            }
        });
    }

    private void drawDepthAware(CommandEncoder encoder, String label, RenderTarget target, List<Batch> batches,
                                GpuBufferSlice vertices, GpuBufferSlice uniforms, int block, GpuBufferSlice transform,
                                GpuSampler legacySampler, Minecraft minecraft, GpuBuffer gpuIndices, IndexType gpuIndexType) {
        drawDepthAware(encoder, label, target, batches, vertices, uniforms, block, transform, legacySampler, minecraft,
                gpuIndices, gpuIndexType, false);
    }

    private void drawDepthAware(CommandEncoder encoder, String label, RenderTarget target, List<Batch> batches,
                                GpuBufferSlice vertices, GpuBufferSlice uniforms, int block, GpuBufferSlice transform,
                                GpuSampler legacySampler, Minecraft minecraft, GpuBuffer gpuIndices, IndexType gpuIndexType,
                                boolean modulation) {
        drawDepthAware(encoder, label, target, batches, vertices, uniforms, block, transform, legacySampler, minecraft,
                gpuIndices, gpuIndexType, modulation, null);
    }

    static boolean writesOpaqueSurface(MaterialDefinition material) {
        return EffectFrame.writesOpaqueDepth(material);
    }

    static boolean requiresOpaqueMotionDepthReplay(MaterialDefinition material) {
        return ParticleMotionPassPlan.requiresOpaqueMotionDepthReplay(material);
    }

    static DepthStencilState motionDepthStencilState(MaterialDefinition material, boolean opaqueDepthReplay) {
        boolean translucent = material.blend() != MaterialDefinition.Blend.OPAQUE;
        if (translucent || opaqueDepthReplay) {
            return new DepthStencilState(depthCompare(material.depth().compare()), true);
        }
        return new DepthStencilState(CompareOp.EQUAL, false);
    }

    private static boolean hasMotionPayload(Batch batch) {
        return batch.key.kind() == EffectMesh.Kind.MESH || batch.key.particleMotion()
                || batch.gpuSprite != null || batch.gpuRibbon != null;
    }

    private void drawDecalReceiverGroups(CommandEncoder encoder, RenderTarget target, List<Batch> batches,
                                         GpuBufferSlice vertices, GpuBufferSlice uniforms, int block,
                                         GpuBufferSlice transform, GpuSampler legacySampler, Minecraft minecraft,
                                         GpuBuffer gpuIndices, IndexType gpuIndexType, boolean writeMotion) {
        if (batches.isEmpty()) return;
        boolean motionActive = writeMotion && motionUniforms != null && WorldHdrCapture.INSTANCE.particleMotionTargets() != null;
        var decalDepthTarget = particleDecalDepth.depthTarget();
        ParticleMotionPassPlan.runDecalReceivers(batches, depthAwareGroup, motionActive,
                batch -> batch.key.material(), EffectGpuRenderer::hasMotionPayload,
                (group, snapshotPrePassDepth) -> {
                    GpuTextureView opaqueMotionDepth = null;
                    if (snapshotPrePassDepth) {
                        var targets = WorldHdrCapture.INSTANCE.particleMotionTargets();
                        if (targets != null && decalDepthTarget.getDepthTexture() != null
                                && decalDepthTarget.width == targets.width()
                                && decalDepthTarget.height == targets.height()) {
                            opaqueMotionDepth = snapshotOpaqueMotionDepth(encoder, decalDepthTarget);
                        }
                    }
                    try (var pass = encoder.createRenderPass(particleDecalDepth.descriptor(target))) {
                        drawBatches(pass, group, vertices, uniforms, block, transform, legacySampler, minecraft,
                                gpuIndices, gpuIndexType, false, false, null, null, true, null);
                    }
                    if (motionActive) {
                        drawMotion(encoder, decalDepthTarget, group, vertices, uniforms, block, transform,
                                legacySampler, minecraft, gpuIndices, gpuIndexType, opaqueMotionDepth, null);
                    }
                });
    }

    private @Nullable GpuTextureView snapshotOpaqueMotionDepth(CommandEncoder encoder, RenderTarget target) {
        var source = target.getDepthTexture();
        if (source == null) return null;
        if (opaqueMotionDepthTexture == null
                || opaqueMotionDepthTexture.getWidth(0) != target.width
                || opaqueMotionDepthTexture.getHeight(0) != target.height
                || opaqueMotionDepthTexture.getFormat() != source.getFormat()) {
            if (opaqueMotionDepthView != null) { opaqueMotionDepthView.close(); opaqueMotionDepthView = null; }
            if (opaqueMotionDepthTexture != null) { opaqueMotionDepthTexture.close(); opaqueMotionDepthTexture = null; }
            opaqueMotionDepthTexture = RenderSystem.getDevice().createTexture("LumenFX opaque motion replay depth",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    source.getFormat(), target.width, target.height, 1, 1);
            try {
                opaqueMotionDepthView = RenderSystem.getDevice().createTextureView(opaqueMotionDepthTexture);
            } catch (RuntimeException | Error error) {
                opaqueMotionDepthTexture.close();
                opaqueMotionDepthTexture = null;
                throw error;
            }
        }
        WorldHdrCapture.INSTANCE.copyDepth(encoder, source, opaqueMotionDepthTexture);
        return opaqueMotionDepthView;
    }

    private void drawDepthAware(CommandEncoder encoder, String label, RenderTarget target, List<Batch> batches,
                                GpuBufferSlice vertices, GpuBufferSlice uniforms, int block, GpuBufferSlice transform,
                                GpuSampler legacySampler, Minecraft minecraft, GpuBuffer gpuIndices, IndexType gpuIndexType,
                                boolean modulation, OpaqueParticleSurfaces.@Nullable Frame particleSurfaces) {
        boolean uniformSurfaceCapture = particleSurfaces == null
                || batches.stream().allMatch(batch -> writesOpaqueSurface(batch.key.material()));
        boolean needsGrouping = !uniformSurfaceCapture;
        if (!needsGrouping) {
            boolean sawTranslucentVel = false;
            boolean sawTranslucentDepthSync = false;
            for (int i = 0, n = batches.size(); i < n; i++) {
                var batch = batches.get(i);
                var material = batch.key.material();
                if (material.sceneDepth() || requiresOpaqueMotionDepthReplay(material)) {
                    needsGrouping = true;
                    break;
                }
                boolean writesColorDepth = !modulation || batch.modulationState == null && !batch.orderedMultiply();
                boolean motionEligible = writesColorDepth && hasMotionPayload(batch);
                boolean batchVel = ParticleMotionPassPlan.isTranslucentVelocityBatch(material, motionEligible);
                boolean batchDepthSync = ParticleMotionPassPlan.isTranslucentDepthSyncBatch(
                        material, writesColorDepth, motionEligible);
                if (ParticleMotionPassPlan.isolatesTranslucentVelocityDepthWriter(material, writesColorDepth, motionEligible)
                        || ParticleMotionPassPlan.splitsTranslucentGroup(
                                sawTranslucentVel, sawTranslucentDepthSync, batchVel, batchDepthSync)) {
                    needsGrouping = true;
                    break;
                }
                sawTranslucentVel |= batchVel;
                sawTranslucentDepthSync |= batchDepthSync;
            }
        }
        if (!needsGrouping) {
            drawGroup(encoder, label, target, batches, vertices, uniforms, block, transform,
                    legacySampler, minecraft, gpuIndices, gpuIndexType, modulation, particleSurfaces);
            return;
        }
        List<Batch> group = depthAwareGroup;
        group.clear();
        boolean depthDirty = true;
        boolean groupWritesSurface = false;
        boolean groupHasTranslucentVel = false;
        boolean groupHasTranslucentDepthSync = false;
        try {
            for (Batch batch : batches) {
                var material = batch.key.material();
                boolean batchWritesSurface = particleSurfaces != null && writesOpaqueSurface(material);
                boolean strictMotionReplay = requiresOpaqueMotionDepthReplay(material);
                boolean writesColorDepth = !modulation || batch.modulationState == null && !batch.orderedMultiply();
                boolean motionEligible = writesColorDepth && hasMotionPayload(batch);
                boolean batchVel = ParticleMotionPassPlan.isTranslucentVelocityBatch(material, motionEligible);
                boolean batchDepthSync = ParticleMotionPassPlan.isTranslucentDepthSyncBatch(
                        material, writesColorDepth, motionEligible);
                boolean isolateTranslucentVelDepth = ParticleMotionPassPlan.isolatesTranslucentVelocityDepthWriter(
                        material, writesColorDepth, motionEligible);
                boolean translucentMotionTransition = isolateTranslucentVelDepth
                        || ParticleMotionPassPlan.splitsTranslucentGroup(
                                groupHasTranslucentVel, groupHasTranslucentDepthSync, batchVel, batchDepthSync);
                if (!group.isEmpty() && (batchWritesSurface != groupWritesSurface
                        || strictMotionReplay || translucentMotionTransition)) {
                    drawGroup(encoder, label, target, group, vertices, uniforms, block, transform,
                            legacySampler, minecraft, gpuIndices, gpuIndexType, modulation,
                            groupWritesSurface ? particleSurfaces : null);
                    group.clear();
                    groupHasTranslucentVel = false;
                    groupHasTranslucentDepthSync = false;
                }
                if (material.sceneDepth() && depthDirty) {
                    if (!group.isEmpty()) {
                        drawGroup(encoder, label, target, group, vertices, uniforms, block, transform,
                                legacySampler, minecraft, gpuIndices, gpuIndexType, modulation,
                                groupWritesSurface ? particleSurfaces : null);
                        group.clear();
                        groupHasTranslucentVel = false;
                        groupHasTranslucentDepthSync = false;
                    }
                    sceneDepth.capture(encoder, target);
                    depthDirty = false;
                }
                if (group.isEmpty()) groupWritesSurface = batchWritesSurface;
                group.add(batch);
                if (writesColorDepth && material.depth().write()
                        && material.depth().compare() != MaterialDepth.Compare.DISABLED
                        && material.depth().compare() != MaterialDepth.Compare.NEVER) {
                    depthDirty = true;
                }
                groupHasTranslucentVel |= batchVel;
                groupHasTranslucentDepthSync |= batchDepthSync;
                if (strictMotionReplay || isolateTranslucentVelDepth) {
                    drawGroup(encoder, label, target, group, vertices, uniforms, block, transform,
                            legacySampler, minecraft, gpuIndices, gpuIndexType, modulation,
                            groupWritesSurface ? particleSurfaces : null);
                    group.clear();
                    groupHasTranslucentVel = false;
                    groupHasTranslucentDepthSync = false;
                }
            }
            if (!group.isEmpty()) drawGroup(encoder, label, target, group, vertices, uniforms, block, transform,
                    legacySampler, minecraft, gpuIndices, gpuIndexType, modulation,
                    groupWritesSurface ? particleSurfaces : null);
        } finally {
            group.clear();
        }
    }

    private void drawGroup(CommandEncoder encoder, String label, RenderTarget target, List<Batch> batches,
                           GpuBufferSlice vertices, GpuBufferSlice uniforms, int block, GpuBufferSlice transform,
                           GpuSampler legacySampler, Minecraft minecraft, GpuBuffer gpuIndices, IndexType gpuIndexType,
                           boolean modulation, OpaqueParticleSurfaces.@Nullable Frame particleSurfaces) {
        var scissor = PhotonRenderOutput.scissor(target.width, target.height);
        if (PhotonRenderOutput.isEmpty(scissor)) return;
        var capture = WorldHdrCapture.INSTANCE;
        var motionTargets = capture.particleMotionTargets();
        boolean motionActive = motionUniforms != null && motionTargets != null;
        GpuTextureView[] opaqueMotionDepthHolder = new GpuTextureView[1];
        ParticleMotionPassPlan.runRasterGroup(batches, motionActive,
                batch -> batch.key.material(),
                batch -> (!modulation || batch.modulationState == null && !batch.orderedMultiply()) && hasMotionPayload(batch),
                batch -> !modulation || batch.modulationState == null && !batch.orderedMultiply(),
                () -> {
                    if (motionTargets != null && target.getDepthTexture() != null
                            && target.width == motionTargets.width() && target.height == motionTargets.height()) {
                        opaqueMotionDepthHolder[0] = snapshotOpaqueMotionDepth(encoder, target);
                    }
                },
                () -> {
                    if (motionTargets != null && target.getDepthTexture() != null
                            && target.width == motionTargets.width() && target.height == motionTargets.height()) {
                        capture.particleMotionDepth(encoder, target);
                    }
                },
                () -> {
                    if (motionTargets != null && capture.hasParticleMotionDepth() && target.getDepthTexture() != null
                            && target.width == motionTargets.width() && target.height == motionTargets.height()) {
                        capture.snapshotTranslucentMotionPreColorDepth(encoder, target);
                    }
                },
                () -> {
                    if (particleSurfaces != null && particleSurfaces.adaptive() != null) {
                        for (var replay : particleSurfaces.adaptive().replays()) {
                            AdaptiveParticleBuffer.copyDepth(encoder, target, replay);
                            try (var pass = ParticleRasterPass.open(encoder, replay.descriptor(), scissor)) {
                                drawBatches(pass, batches, vertices, uniforms, block, transform, legacySampler, minecraft,
                                        gpuIndices, gpuIndexType, false, modulation, particleSurfaces, null, false, null, replay);
                            }
                        }
                    }
                    var gammaCoverage = gammaBlendTargets.coverage(target);
                    try (RenderPass pass = gammaCoverage != null
                            ? ParticleRasterPass.open(encoder, RenderPassDescriptor.create(() -> label)
                                    .withColorAttachment(target.getColorTextureView()).withColorAttachment(gammaCoverage)
                                    .withDepthAttachment(target.getDepthTextureView())
                                    .withRenderArea(new RenderPass.RenderArea(0, 0, target.width, target.height)), scissor)
                            : particleSurfaces == null
                                ? ParticleRasterPass.open(encoder, label, target.getColorTextureView(), target.getDepthTextureView(), scissor)
                                : ParticleRasterPass.open(encoder, particleSurfaces.pass(target, label), scissor)) {
                        drawBatches(pass, batches, vertices, uniforms, block, transform, legacySampler, minecraft, gpuIndices, gpuIndexType,
                                false, modulation, particleSurfaces);
                    }
                },
                allowsFartherDepthWrite -> {
                    // Handled in exact per-fragment submission order inside drawMotion via TranslucentMotionReplayLog.
                },
                ignoredReplay -> {
                    try {
                        List<Batch> filteredMotionBatches = batches;
                        if (modulation) {
                            motionBatches.clear();
                            for (int i = 0, n = batches.size(); i < n; i++) {
                                var batch = batches.get(i);
                                if (batch.modulationState == null && !batch.orderedMultiply()) motionBatches.add(batch);
                            }
                            filteredMotionBatches = motionBatches;
                        }
                        drawMotion(encoder, target, filteredMotionBatches, vertices, uniforms, block, transform, legacySampler,
                                minecraft, gpuIndices, gpuIndexType, opaqueMotionDepthHolder[0], scissor);
                    } finally {
                        motionBatches.clear();
                        capture.clearTranslucentMotionPreColorDepth();
                    }
                });
    }

    private void drawMotion(CommandEncoder encoder, RenderTarget target, List<Batch> batches, GpuBufferSlice vertices,
                            GpuBufferSlice uniforms, int block, GpuBufferSlice transform, GpuSampler sampler,
                            Minecraft minecraft, GpuBuffer gpuIndices, IndexType gpuIndexType,
                            @Nullable GpuTextureView opaqueMotionDepth, @Nullable ScissorState scissor) {
        var capture = WorldHdrCapture.INSTANCE;
        var targets = capture.particleMotionTargets();
        if (motionUniforms == null || targets == null) return;
        motionOpaque.clear();
        motionTranslucent.clear();
        boolean opaqueHasRealMotion = false;
        boolean translucentHasRealMotion = false;
        boolean translucentHasDepthWriter = false;
        for (int i = 0, n = batches.size(); i < n; i++) {
            var batch = batches.get(i);
            var material = batch.key.material();
            if (material.blend() == MaterialDefinition.Blend.OPAQUE) continue;
            if (hasMotionPayload(batch) && material.writesVelocity()) {
                translucentHasRealMotion = true;
            }
            if (ParticleMotionPassPlan.writesTranslucentColorDepth(material)) {
                translucentHasDepthWriter = true;
            }
        }
        boolean translucentDirect = translucentHasRealMotion && !translucentHasDepthWriter;
        boolean translucentOrderedReplay = translucentHasDepthWriter && (translucentHasRealMotion || capture.hasParticleMotionDepth());
        boolean translucentHardwareLog = translucentOrderedReplay
                && TranslucentMotionReplayLog.supportsHardwareLog(RenderSystem.getDevice());
        List<TranslucentMotionReplayLog.DrawSpec> translucentLogSpecs = null;
        try {
            for (int i = 0, n = batches.size(); i < n; i++) {
                var batch = batches.get(i);
                var material = batch.key.material();
                boolean opaque = material.blend() == MaterialDefinition.Blend.OPAQUE;
                boolean realMotion = hasMotionPayload(batch) && material.writesVelocity();
                batch.motionColorDepth = null;
                if (opaque) {
                    if (!EffectFrame.writesOpaqueDepth(material)) continue;
                    if (motionShaders == null) motionShaders = new MotionShaderAdapter(vertexMaterials);
                    boolean opaqueReplay = opaqueMotionDepth != null;
                    if (realMotion) {
                        validateVertexMotionContracts(batch, material);
                        batch.motionZeroClear = false;
                        batch.motionPipeline = batch.state.motion(motionShaders, material, opaqueReplay, false, nextPipeline++);
                        opaqueHasRealMotion = true;
                    } else {
                        batch.motionZeroClear = true;
                        batch.motionPipeline = batch.state.motionZeroOpaque(motionShaders, material, opaqueReplay, nextPipeline++);
                    }
                    motionOpaque.add(batch);
                } else if (translucentDirect) {
                    if (!realMotion) continue;
                    if (motionShaders == null) motionShaders = new MotionShaderAdapter(vertexMaterials);
                    validateVertexMotionContracts(batch, material);
                    batch.motionZeroClear = false;
                    boolean needsColorGuard = ParticleMotionPassPlan.colorDepthCompareCode(material.depth().compare())
                            != MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE;
                    batch.motionColorDepth = needsColorGuard ? capture.translucentMotionPreColorDepth() : null;
                    batch.motionPipeline = batch.state.motion(motionShaders, material, false, batch.motionColorDepth != null, nextPipeline++);
                    motionTranslucent.add(batch);
                } else if (translucentOrderedReplay) {
                    boolean writesDepth = ParticleMotionPassPlan.writesTranslucentColorDepth(material);
                    if (!realMotion && !writesDepth) continue;
                    if (motionShaders == null) motionShaders = new MotionShaderAdapter(vertexMaterials);
                    if (realMotion) {
                        validateVertexMotionContracts(batch, material);
                        batch.motionZeroClear = false;
                    } else {
                        batch.motionZeroClear = true;
                    }
                    if (translucentHardwareLog) {
                        batch.motionPipeline = batch.state.motionTranslucentLog(motionShaders, realMotion, nextPipeline++);
                        if (translucentLogSpecs == null) translucentLogSpecs = new ArrayList<>();
                        appendTranslucentLogDrawSpecs(batch, new TranslucentMotionReplayLog.DrawSpec(
                                material.depth().compare(), writesDepth, realMotion,
                                realMotion && material.velocityFromDepth()), translucentLogSpecs);
                    }
                    motionTranslucent.add(batch);
                }
            }
            for (int g = 0; g < 2; g++) {
                var group = g == 0 ? motionOpaque : motionTranslucent;
                if (group.isEmpty()) continue;
                var depth = g == 0
                        ? (opaqueMotionDepth != null ? opaqueMotionDepth : target.getDepthTextureView())
                        : capture.particleMotionDepth(encoder, target);
                if (depth == null || target.width != targets.width() || target.height != targets.height()) {
                    throw new IllegalStateException("Particle velocity and world coverage must share the current depth viewport");
                }
                if (g == 1 && translucentOrderedReplay) {
                    if (translucentHardwareLog && translucentLogSpecs != null && !translucentLogSpecs.isEmpty()) {
                        long maxPrimitives = computeMaxTranslucentPrimitives(group);
                        boolean handled = capture.executeTranslucentMotionLog(encoder, target, targets, scissor,
                                translucentLogSpecs, maxPrimitives,
                                (pass, cursor) -> drawBatches(pass, group, vertices, uniforms, block, transform, sampler,
                                        minecraft, gpuIndices, gpuIndexType, true, false, null, null, false, null, null, cursor),
                                singlePixelScissor -> drawTranslucentStepReplay(encoder, target, targets, group, vertices,
                                        uniforms, block, transform, sampler, minecraft, gpuIndices, gpuIndexType, singlePixelScissor));
                        if (!handled) {
                            drawTranslucentStepReplay(encoder, target, targets, group, vertices, uniforms, block, transform,
                                    sampler, minecraft, gpuIndices, gpuIndexType, scissor);
                        }
                    } else {
                        drawTranslucentStepReplay(encoder, target, targets, group, vertices, uniforms, block, transform,
                                sampler, minecraft, gpuIndices, gpuIndexType, scissor);
                    }
                    if (translucentHasRealMotion) capture.recordParticleMotionHistory(motionHistoryValid);
                    continue;
                }
                String passLabel = g == 0
                        ? "LumenFX particle velocity coverage"
                        : "LumenFX translucent particle velocity";
                var descriptor = RenderPassDescriptor.create(() -> passLabel)
                        .withColorAttachment(targets.previousNdc()).withColorAttachment(targets.depthCoverage())
                        .withDepthAttachment(depth).withRenderArea(new RenderPass.RenderArea(0, 0, targets.width(), targets.height()));
                if (g == 0) {
                    descriptor.withColorAttachment(Objects.requireNonNull(targets.motionOffset()));
                }
                try (var pass = scissor != null
                        ? ParticleRasterPass.open(encoder, descriptor, scissor)
                        : encoder.createRenderPass(descriptor)) {
                    drawBatches(pass, group, vertices, uniforms, block, transform, sampler, minecraft, gpuIndices, gpuIndexType, true, false, null);
                }
                if (g == 0 ? opaqueHasRealMotion : translucentHasRealMotion) {
                    capture.recordParticleMotionHistory(motionHistoryValid);
                }
            }
        } finally {
            motionOpaque.clear();
            motionTranslucent.clear();
        }
    }

    private static long computeMaxTranslucentPrimitives(List<Batch> batches) {
        long total = 0L;
        for (int i = 0, n = batches.size(); i < n; i++) {
            var batch = batches.get(i);
            long batchPrimitives = 0L;
            if (batch.key.kind() == EffectMesh.Kind.MESH) {
                var section = batch.key.mesh().geometry().sections().get(batch.key.mesh().section());
                for (var geometry : batch.meshChunks) {
                    if (!geometry.section().equals(section)) continue;
                    long trisPerInstance = Math.max(1L, geometry.indexCount() / 3L);
                    long instanceUpperBound;
                    if (batch.gpuMesh != null) {
                        var command = batch.meshCommands.get(geometry);
                        instanceUpperBound = command == null
                                ? 1L
                                : Math.max(1L, batch.gpuMesh.particles().current().stride());
                    } else {
                        instanceUpperBound = Math.max(0L, batch.instances);
                    }
                    batchPrimitives = saturatingAdd(batchPrimitives, saturatingMultiply(trisPerInstance, instanceUpperBound));
                }
            } else if (batch.gpuRibbon != null) {
                batchPrimitives = Math.max(1L, batch.gpuRibbon.indices().size() / (3L * Integer.BYTES));
            } else if (batch.gpuSprite != null) {
                long trisPerInstance = batch.key.cutout() == null ? 2L : Math.max(1L, batch.key.cutout().indexCount() / 3L);
                long instanceUpperBound = batch.gpuSprite.command() == null
                        ? 1L
                        : Math.max(1L, batch.gpuSprite.maxInstances());
                batchPrimitives = saturatingMultiply(trisPerInstance, instanceUpperBound);
            } else if (batch.key.kind() == EffectMesh.Kind.RIBBON) {
                batchPrimitives = saturatingMultiply(Math.max(1L, batch.key.widthSegments() * 2L), Math.max(0L, batch.instances));
            } else {
                long trisPerInstance = batch.key.cutout() == null ? 2L : Math.max(1L, batch.key.cutout().indexCount() / 3L);
                batchPrimitives = saturatingMultiply(trisPerInstance, Math.max(0L, batch.instances));
            }
            total = saturatingAdd(total, batchPrimitives);
        }
        return Math.max(1L, total);
    }

    private static long saturatingAdd(long a, long b) {
        if (a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    private static long saturatingMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) return 0L;
        if (a > Long.MAX_VALUE / b) return Long.MAX_VALUE;
        return a * b;
    }

    private void drawTranslucentStepReplay(
            CommandEncoder encoder, RenderTarget target,
            com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer.CoverageTargets targets,
            List<Batch> group, GpuBufferSlice vertices, GpuBufferSlice uniforms, int block, GpuBufferSlice transform,
            GpuSampler legacySampler, Minecraft minecraft, GpuBuffer gpuIndices, IndexType gpuIndexType,
            @Nullable ScissorState scissor) {
        var capture = WorldHdrCapture.INSTANCE;
        capture.beginTranslucentStepReplay(encoder, target);
        for (int i = 0, n = group.size(); i < n; i++) {
            var batch = group.get(i);
            var material = batch.key.material();
            boolean realMotion = !batch.motionZeroClear;
            boolean writesDepth = ParticleMotionPassPlan.writesTranslucentColorDepth(material);
            MaterialDepth.Compare compare = material.depth().compare();
            if (batch.key.kind() == EffectMesh.Kind.MESH) {
                var section = batch.key.mesh().geometry().sections().get(batch.key.mesh().section());
                for (var geometry : batch.meshChunks) {
                    if (!geometry.section().equals(section)) continue;
                    int triCount = geometry.indexCount() / 3;
                    if (triCount <= 0) continue;
                    GpuBuffer indices = batch.reversedMeshIndices.getOrDefault(geometry, geometry.indices());
                    if (batch.gpuMesh != null) {
                        var command = batch.meshCommands.get(geometry);
                        boolean indirect = command != null;
                        RenderPipeline stepPipeline = batch.state.motionTranslucentStep(
                                motionShaders, material, realMotion, writesDepth, indirect, nextPipeline++);
                        int maxInstances = indirect ? Math.max(1, batch.gpuMesh.particles().current().stride()) : 1;
                        int cmdOffsetUints = indirect ? Math.toIntExact(command.offset() / Integer.BYTES) : 0;
                        GpuBufferSlice indirectBufSlice = indirect
                                ? new GpuBufferSlice(command.buffer(), 0, command.buffer().size()) : null;
                        for (int inst = 0; inst < maxInstances; inst++) {
                            final int currentInst = inst;
                            for (int tri = 0; tri < triCount; tri++) {
                                final int firstIndex = tri * 3;
                                capture.runTranslucentStepPrimitive(encoder, target, targets, compare, writesDepth,
                                        currentInst, cmdOffsetUints, indirect, scissor, (pass, stepBase) -> {
                                            pass.setPipeline(stepPipeline);
                                            pass.setUniform(TranslucentMotionLogLayout.STEP_BASE_UNIFORM, stepBase);
                                            if (indirectBufSlice != null) {
                                                pass.setUniform(TranslucentMotionLogLayout.STEP_INDIRECT_UNIFORM, indirectBufSlice);
                                            }
                                            bindBatchCommonResources(pass, batch, batch.state, vertices, uniforms, block, transform,
                                                    legacySampler, gpuIndices, gpuIndexType, true, null, null, null, null, false);
                                            pass.setUniform("NiagaraMeshData", batch.meshUniforms);
                                            if (batch.meshScene != null) bindMeshScene(pass, batch.meshScene);
                                            pass.setUniform("NiagaraMeshVertices", geometry.vertices());
                                            pass.setUniform("NiagaraMeshInfo", geometry.info().slice());
                                            pass.setIndexBuffer(indices, IndexType.INT);
                                            pass.drawIndexed(3, 1, firstIndex, 0, 0);
                                        });
                            }
                        }
                    } else if (batch.extraChunks.isEmpty()) {
                        RenderPipeline stepPipeline = batch.state.motionTranslucentStep(
                                motionShaders, material, realMotion, writesDepth, false, nextPipeline++);
                        int stride = EffectMesh.Kind.MESH.floatsPerInstance() * Float.BYTES;
                        for (int inst = 0; inst < batch.instances; inst++) {
                            final int currentInst = inst;
                            GpuBufferSlice instSlice = vertices.slice(batch.offset + (long) currentInst * stride, stride);
                            for (int tri = 0; tri < triCount; tri++) {
                                final int firstIndex = tri * 3;
                                capture.runTranslucentStepPrimitive(encoder, target, targets, compare, writesDepth,
                                        currentInst, 0, false, scissor, (pass, stepBase) -> {
                                            pass.setPipeline(stepPipeline);
                                            pass.setUniform(TranslucentMotionLogLayout.STEP_BASE_UNIFORM, stepBase);
                                            bindBatchCommonResources(pass, batch, batch.state, vertices, uniforms, block, transform,
                                                    legacySampler, gpuIndices, gpuIndexType, true, null, null, null, null, false);
                                            pass.setVertexBuffer(0, instSlice);
                                            pass.setUniform("NiagaraMeshData", batch.meshUniforms);
                                            if (batch.meshScene != null) bindMeshScene(pass, batch.meshScene);
                                            pass.setUniform("NiagaraMeshVertices", geometry.vertices());
                                            pass.setUniform("NiagaraMeshInfo", geometry.info().slice());
                                            pass.setIndexBuffer(indices, IndexType.INT);
                                            pass.drawIndexed(3, 1, firstIndex, 0, 0);
                                        });
                            }
                        }
                    } else {
                        RenderPipeline stepPipeline = batch.state.motionTranslucentStep(
                                motionShaders, material, realMotion, writesDepth, false, nextPipeline++);
                        int stride = EffectMesh.Kind.MESH.floatsPerInstance() * Float.BYTES;
                        for (var chunk : batch.extraChunks) {
                            for (int localInst = 0; localInst < chunk.instances(); localInst++) {
                                final int currentLocal = localInst;
                                GpuBufferSlice instSlice = vertices.slice(
                                        batch.offset + (long) (chunk.firstInstance() + currentLocal) * stride, stride);
                                for (int tri = 0; tri < triCount; tri++) {
                                    final int firstIndex = tri * 3;
                                    capture.runTranslucentStepPrimitive(encoder, target, targets, compare, writesDepth,
                                            currentLocal, 0, false, scissor, (pass, stepBase) -> {
                                                pass.setPipeline(stepPipeline);
                                                pass.setUniform(TranslucentMotionLogLayout.STEP_BASE_UNIFORM, stepBase);
                                                bindBatchCommonResources(pass, batch, batch.state, vertices, uniforms, block, transform,
                                                        legacySampler, gpuIndices, gpuIndexType, true, null, null, null, null, false);
                                                pass.setUniform("NiagaraMeshData", batch.meshUniforms);
                                                if (batch.meshScene != null) bindMeshScene(pass, batch.meshScene);
                                                if (batch.needsMeshScene()) bindMeshScene(pass, batch.meshSceneChunks.get(chunk));
                                                pass.setVertexBuffer(0, instSlice);
                                                if (realMotion || batch.state.extraBindings) {
                                                    pass.setUniform("NiagaraExtraData", chunk.buffer());
                                                    pass.setUniform("NiagaraExtraInfo", chunk.info());
                                                }
                                                pass.setUniform("NiagaraMeshVertices", geometry.vertices());
                                                pass.setUniform("NiagaraMeshInfo", geometry.info().slice());
                                                pass.setIndexBuffer(indices, IndexType.INT);
                                                pass.drawIndexed(3, 1, firstIndex, 0, 0);
                                            });
                                }
                            }
                        }
                    }
                }
            } else if (batch.gpuRibbon != null) {
                RenderPipeline stepPipeline = batch.state.motionTranslucentStep(
                        motionShaders, material, realMotion, writesDepth, false, nextPipeline++);
                int maxTris = (int) Math.min(Integer.MAX_VALUE / 3L,
                        batch.gpuRibbon.indices().size() / (3L * Integer.BYTES));
                int drawOffsetUints = Math.toIntExact(batch.gpuRibbon.drawArguments().offset() / Integer.BYTES);
                for (int tri = 0; tri < maxTris; tri++) {
                    final int firstIndex = tri * 3;
                    capture.runTranslucentStepPrimitive(encoder, target, targets, compare, writesDepth,
                            firstIndex, drawOffsetUints, true, scissor, (pass, stepBase) -> {
                                pass.setPipeline(stepPipeline);
                                pass.setUniform(TranslucentMotionLogLayout.STEP_BASE_UNIFORM, stepBase);
                                bindBatchCommonResources(pass, batch, batch.state, vertices, uniforms, block, transform,
                                        legacySampler, gpuIndices, gpuIndexType, true, null, null, null, null, false);
                                pass.drawIndexed(3, 1, firstIndex, 0, 0);
                            });
                }
            } else if (batch.gpuSprite != null) {
                var command = batch.gpuSprite.command();
                boolean indirect = command != null;
                RenderPipeline stepPipeline = batch.state.motionTranslucentStep(
                        motionShaders, material, realMotion, writesDepth, indirect, nextPipeline++);
                int indexCount = batch.key.cutout() == null ? 6 : batch.key.cutout().indexCount();
                int triCount = indexCount / 3;
                int maxInstances = indirect ? Math.max(1, batch.gpuSprite.maxInstances()) : 1;
                int cmdOffsetUints = indirect ? Math.toIntExact(command.offset() / Integer.BYTES) : 0;
                GpuBufferSlice indirectBufSlice = indirect
                        ? new GpuBufferSlice(command.buffer(), 0, command.buffer().size()) : null;
                for (int inst = 0; inst < maxInstances; inst++) {
                    final int currentInst = inst;
                    for (int tri = 0; tri < triCount; tri++) {
                        final int firstIndex = tri * 3;
                        capture.runTranslucentStepPrimitive(encoder, target, targets, compare, writesDepth,
                                currentInst, cmdOffsetUints, indirect, scissor, (pass, stepBase) -> {
                                    pass.setPipeline(stepPipeline);
                                    pass.setUniform(TranslucentMotionLogLayout.STEP_BASE_UNIFORM, stepBase);
                                    if (indirectBufSlice != null) {
                                        pass.setUniform(TranslucentMotionLogLayout.STEP_INDIRECT_UNIFORM, indirectBufSlice);
                                    }
                                    bindBatchCommonResources(pass, batch, batch.state, vertices, uniforms, block, transform,
                                            legacySampler, gpuIndices, gpuIndexType, true, null, null, null, null, false);
                                    pass.drawIndexed(3, 1, firstIndex, 0, 0);
                                });
                    }
                }
            } else {
                RenderPipeline stepPipeline = batch.state.motionTranslucentStep(
                        motionShaders, material, realMotion, writesDepth, false, nextPipeline++);
                int stride = batch.key.kind().floatsPerInstance() * Float.BYTES;
                boolean ribbon = batch.key.kind() == EffectMesh.Kind.RIBBON;
                int triCount = ribbon
                        ? batch.key.widthSegments() * 2
                        : (batch.cutoutBuffers == null ? 2 : batch.key.cutout().indexCount() / 3);
                if (batch.extraChunks.isEmpty()) {
                    for (int inst = 0; inst < batch.instances; inst++) {
                        final int currentInst = inst;
                        GpuBufferSlice instSlice = vertices.slice(batch.offset + (long) currentInst * stride, stride);
                        for (int tri = 0; tri < triCount; tri++) {
                            final int first = tri * 3;
                            capture.runTranslucentStepPrimitive(encoder, target, targets, compare, writesDepth,
                                    currentInst, 0, false, scissor, (pass, stepBase) -> {
                                        pass.setPipeline(stepPipeline);
                                        pass.setUniform(TranslucentMotionLogLayout.STEP_BASE_UNIFORM, stepBase);
                                        bindBatchCommonResources(pass, batch, batch.state, vertices, uniforms, block, transform,
                                                legacySampler, gpuIndices, gpuIndexType, true, null, null, null, null, false);
                                        pass.setVertexBuffer(0, instSlice);
                                        if (ribbon || batch.cutoutBuffers == null) pass.draw(3, 1, first, 0);
                                        else pass.drawIndexed(3, 1, first, 0, 0);
                                    });
                        }
                    }
                } else {
                    for (var chunk : batch.extraChunks) {
                        for (int localInst = 0; localInst < chunk.instances(); localInst++) {
                            final int currentLocal = localInst;
                            GpuBufferSlice instSlice = vertices.slice(
                                    batch.offset + (long) (chunk.firstInstance() + currentLocal) * stride, stride);
                            for (int tri = 0; tri < triCount; tri++) {
                                final int first = tri * 3;
                                capture.runTranslucentStepPrimitive(encoder, target, targets, compare, writesDepth,
                                        currentLocal, 0, false, scissor, (pass, stepBase) -> {
                                            pass.setPipeline(stepPipeline);
                                            pass.setUniform(TranslucentMotionLogLayout.STEP_BASE_UNIFORM, stepBase);
                                            bindBatchCommonResources(pass, batch, batch.state, vertices, uniforms, block, transform,
                                                    legacySampler, gpuIndices, gpuIndexType, true, null, null, null, null, false);
                                            pass.setVertexBuffer(0, instSlice);
                                            if (realMotion || batch.state.extraBindings) {
                                                pass.setUniform("NiagaraExtraData", chunk.buffer());
                                                pass.setUniform("NiagaraExtraInfo", chunk.info());
                                            }
                                            if (ribbon || batch.cutoutBuffers == null) pass.draw(3, 1, first, 0);
                                            else pass.drawIndexed(3, 1, first, 0, 0);
                                        });
                            }
                        }
                    }
                }
            }
        }
    }

    private static void appendTranslucentLogDrawSpecs(Batch batch, TranslucentMotionReplayLog.DrawSpec spec,
                                                      List<TranslucentMotionReplayLog.DrawSpec> out) {
        if (batch.key.kind() == EffectMesh.Kind.MESH) {
            var section = batch.key.mesh().geometry().sections().get(batch.key.mesh().section());
            for (var geometry : batch.meshChunks) {
                if (!geometry.section().equals(section)) continue;
                if (batch.gpuMesh != null || batch.extraChunks.isEmpty()) {
                    out.add(spec);
                } else {
                    for (int c = 0, cn = batch.extraChunks.size(); c < cn; c++) out.add(spec);
                }
            }
        } else if (batch.gpuRibbon != null) {
            out.add(spec);
        } else if (batch.gpuSprite == null && !batch.extraChunks.isEmpty()) {
            for (int c = 0, cn = batch.extraChunks.size(); c < cn; c++) out.add(spec);
        } else {
            out.add(spec);
        }
    }

    private void validateVertexMotionContracts(Batch batch, MaterialDefinition material) {
        var vertex = material.vertexMaterial();
        if (vertex == null) return;
        int root = batch.firstPerson() ? vertex.firstPersonInputs() : 0;
        boolean wpo = vertex.worldPositionOffset();
        if ((wpo && vertex.previousClock() || (root & 256) != 0)
                && (!Double.isFinite(frameMotionRealTime) || !Double.isFinite(frameMotionEngineTime)))
            throw new MotionShaderContractException("Vertex material motion requires the captured previous render-frame clocks");
        if ((wpo && vertex.previousView() || (root & 512) != 0) && !frameMotionProjectionKnown)
            throw new MotionShaderContractException("Vertex material motion requires the captured previous render projection");
        if ((wpo && vertex.previousMatrices() || (root & 1024) != 0) && !frameMotionMatricesKnown)
            throw new MotionShaderContractException("Vertex material motion requires the captured previous model-view matrix");
        if ((wpo && vertex.previousFirstPerson() || (root & 2048) != 0) && !frameMotionFirstPersonKnown)
            throw new MotionShaderContractException("Vertex material motion requires the captured previous first-person view parameters");
    }

    private void drawBatches(
            final RenderPass pass,
            final List<Batch> batches,
            final GpuBufferSlice vertices,
            final GpuBufferSlice uniforms,
            final int block,
            final GpuBufferSlice transform,
            final GpuSampler legacySampler,
            final Minecraft minecraft,
            final GpuBuffer gpuIndices,
            final IndexType gpuIndexType,
            final boolean motion,
            final boolean modulation,
            final OpaqueParticleSurfaces.@Nullable Frame particleSurfaces
    ) {
        drawBatches(pass, batches, vertices, uniforms, block, transform, legacySampler, minecraft, gpuIndices, gpuIndexType,
                motion, modulation, particleSurfaces, null, false, null);
    }

    private void drawBatches(RenderPass pass, List<Batch> batches, GpuBufferSlice vertices, GpuBufferSlice uniforms, int block,
                             GpuBufferSlice transform, GpuSampler legacySampler, Minecraft minecraft, GpuBuffer gpuIndices,
                             IndexType gpuIndexType, boolean motion, boolean modulation,
                             OpaqueParticleSurfaces.@Nullable Frame particleSurfaces,
                             @Nullable ShadowDraw shadow, boolean decalDepth, @Nullable CustomDepthDraw customDepthDraw) {
        drawBatches(pass, batches, vertices, uniforms, block, transform, legacySampler, minecraft, gpuIndices, gpuIndexType,
                motion, modulation, particleSurfaces, shadow, decalDepth, customDepthDraw, null);
    }

    private void drawBatches(RenderPass pass, List<Batch> batches, GpuBufferSlice vertices, GpuBufferSlice uniforms, int block,
                             GpuBufferSlice transform, GpuSampler legacySampler, Minecraft minecraft, GpuBuffer gpuIndices,
                             IndexType gpuIndexType, boolean motion, boolean modulation,
                             OpaqueParticleSurfaces.@Nullable Frame particleSurfaces,
                             @Nullable ShadowDraw shadow, boolean decalDepth, @Nullable CustomDepthDraw customDepthDraw,
                             AdaptiveParticleBuffer.@Nullable Replay adaptiveReplay) {
        drawBatches(pass, batches, vertices, uniforms, block, transform, legacySampler, minecraft, gpuIndices, gpuIndexType,
                motion, modulation, particleSurfaces, shadow, decalDepth, customDepthDraw, adaptiveReplay, null);
    }

    private void drawBatches(RenderPass pass, List<Batch> batches, GpuBufferSlice vertices, GpuBufferSlice uniforms, int block,
                             GpuBufferSlice transform, GpuSampler legacySampler, Minecraft minecraft, GpuBuffer gpuIndices,
                             IndexType gpuIndexType, boolean motion, boolean modulation,
                             OpaqueParticleSurfaces.@Nullable Frame particleSurfaces,
                             @Nullable ShadowDraw shadow, boolean decalDepth, @Nullable CustomDepthDraw customDepthDraw,
                             AdaptiveParticleBuffer.@Nullable Replay adaptiveReplay,
                             TranslucentMotionReplayLog.@Nullable DrawCursor motionLogCursor) {
        for (Batch batch : batches) {
            var capture = WorldHdrCapture.INSTANCE;
            boolean directionalRibbonBasis = shadow != null && shadow.directionalCaster() != null && !batch.parts.isEmpty()
                    && batch.parts.getFirst().directionalRibbonBasis();
            var drawState = modulation && batch.modulationState != null ? batch.modulationState : batch.state;
            var pipeline = motion ? batch.motionPipeline : drawState.pipeline;
            if (customDepthDraw != null) pipeline = customDepthDraw.pipeline();
            if (decalDepth) pipeline = particleDecalDepth.pipeline(pipeline,
                    batch.key.receivesDecals() && batch.key.material().receivesDecals(), vertexMaterials);
            boolean substrate = particleSurfaces != null && (batch.key.material().particleLightingFeatures()
                    & MaterialDefinition.SUBSTRATE_SURFACE_PACKING) != 0;
            if (adaptiveReplay != null) {
                var surfaces = java.util.Objects.requireNonNull(particleSurfaces);
                pipeline = adaptiveParticleReplays.get(pipeline, batch.key.material(), adaptiveReplay,
                        surfaces.bottomNormal(), surfaces.tangentAnisotropy() != null, vertexMaterials);
            } else if (particleSurfaces != null) {
                var primitive = materialPrimitive(batch);
                String profile = batch.key.material().subsurfaceProfile();
                int profileIndex = profile == null ? -1 : VfxEngine.INSTANCE.subsurfaceProfileIndex(profile);
                pipeline = opaqueParticleSurfaces.pipeline(pipeline, batch.key.material(),
                        primitive == null ? PrimitiveLighting.DEFAULT : primitive.lighting(),
                        particleSurfaces, batch.opaqueIndirectDiffuse, primitive != null && primitive.firstPerson(), profileIndex, vertexMaterials);
            }
            if (shadow != null) {
                Winding winding = Winding.BOTH;
                if (batch.key.material().cull() && !batch.state.key.disableCull()) {
                    var primitive = java.util.Objects.requireNonNull(materialPrimitive(batch), "Shadow winding requires the component pose");
                    var pose = primitive.current();
                    boolean mirrored = mirroredComponent(batch, pose);
                    winding = mirrored ? Winding.FRONT : Winding.BACK;
                }
                pipeline = shadowPipelines.get(pipeline, winding, shadow.spotCaster() != null, shadow.directionalCaster() != null,
                        directionalRibbonBasis, batch.state.extraBindings, vertexMaterials);
            }
            pass.setPipeline(pipeline);
            if (motion && motionLogCursor != null) {
                pass.setUniform(TranslucentMotionLogLayout.NAME, motionLogCursor.logBuffer());
            }
            if (motion && batch.motionColorDepth != null) {
                pass.bindTexture(MotionShaderAdapter.COLOR_DEPTH_SAMPLER, batch.motionColorDepth,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            }
            bindBatchCommonResources(pass, batch, drawState, vertices, uniforms, block, transform, legacySampler,
                    gpuIndices, gpuIndexType, motion, particleSurfaces, shadow, customDepthDraw, adaptiveReplay, substrate);
            boolean activeMotion = motion && !batch.motionZeroClear;
            if (batch.key.kind() == EffectMesh.Kind.MESH) drawMesh(pass, batch, vertices, activeMotion, motionLogCursor);
            else if (batch.gpuRibbon != null) {
                if (motionLogCursor != null) motionLogCursor.bindNext(pass);
                pass.drawIndexedIndirect(batch.gpuRibbon.drawArguments(), 1);
            } else if (batch.gpuSprite == null && batch.extraChunks.isEmpty()) {
                if (motionLogCursor != null) motionLogCursor.bindNext(pass);
                if (batch.cutoutBuffers == null)
                    pass.draw(batch.key.kind() == EffectMesh.Kind.SPRITE ? 6 : batch.key.widthSegments() * 6, batch.instances, 0, 0);
                else pass.drawIndexed(batch.key.cutout().indexCount(), batch.instances, 0, 0, 0);
            }
            else if (batch.gpuSprite == null) {
                int stride = batch.key.kind().floatsPerInstance() * Float.BYTES;
                for (var chunk : batch.extraChunks) {
                    pass.setVertexBuffer(0, vertices.slice(batch.offset + (long) chunk.firstInstance() * stride,
                            (long) chunk.instances() * stride));
                    if (activeMotion || batch.state.extraBindings || directionalRibbonBasis) {
                        pass.setUniform("NiagaraExtraData", chunk.buffer());
                        pass.setUniform("NiagaraExtraInfo", chunk.info());
                    }
                    if (motionLogCursor != null) motionLogCursor.bindNext(pass);
                    if (batch.cutoutBuffers == null)
                        pass.draw(batch.key.kind() == EffectMesh.Kind.SPRITE ? 6 : batch.key.widthSegments() * 6,
                                chunk.instances(), 0, 0);
                    else pass.drawIndexed(batch.key.cutout().indexCount(), chunk.instances(), 0, 0, 0);
                }
            }
            else if (batch.gpuSprite.command() == null) {
                if (motionLogCursor != null) motionLogCursor.bindNext(pass);
                pass.drawIndexed(batch.key.cutout() == null ? 6 : batch.key.cutout().indexCount(), 1, 0, 0, 0);
            } else {
                if (motionLogCursor != null) motionLogCursor.bindNext(pass);
                pass.drawIndexedIndirect(batch.gpuSprite.command(), 1);
            }
        }
    }

    private void bindBatchCommonResources(
            RenderPass pass, Batch batch, DrawState drawState,
            GpuBufferSlice vertices, GpuBufferSlice uniforms, int block, GpuBufferSlice transform,
            GpuSampler legacySampler, GpuBuffer gpuIndices, IndexType gpuIndexType,
            boolean motion, OpaqueParticleSurfaces.@Nullable Frame particleSurfaces,
            @Nullable ShadowDraw shadow, @Nullable CustomDepthDraw customDepthDraw,
            AdaptiveParticleBuffer.@Nullable Replay adaptiveReplay, boolean substrate) {
        var capture = WorldHdrCapture.INSTANCE;
        if (adaptiveReplay == null && particleSurfaces != null)
            bindOpaqueIndirect(pass, batch.key.material(), batch.opaqueIndirectDiffuse);
        if (substrate && (adaptiveReplay == null || batch.key.material().adaptiveSubstrateWords() != 0))
            pass.setUniform("LfxSubstratePacking",
                    java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(particleSurfaces).substratePacking()));
        RenderSystem.bindDefaultUniforms(pass);
        if (customDepthDraw != null && customDepthDraw.stencil() != null)
            pass.setUniform("CustomStencilPrimitive", customDepthDraw.stencil());
        if (shadow != null) {
            pass.setUniform("ParticlePointShadowClip", shadow.clip());
            if (shadow.spotCaster() != null) pass.setUniform(SpotShadowCaster.UNIFORM, shadow.spotCaster());
            if (shadow.directionalCaster() != null) pass.setUniform(DirectionalShadowCaster.UNIFORM, shadow.directionalCaster());
        }
        if (motion && !batch.motionZeroClear) {
            pass.setUniform("ParticleMotionView", motionUniforms);
        }
        if (!depthOnly && batch.key.material().usesTranslucencyVolume())
            java.util.Objects.requireNonNull(translucencyLightingSample)
                    .bind(pass, batch.key.material().samplesTranslucencyPerPixel());
        if (!depthOnly && batch.key.material().usesVolumeIndirect())
            java.util.Objects.requireNonNull(batch.vertexTranslucencyIndirect != null
                    ? batch.vertexTranslucencyIndirect : vertexTranslucencyIndirect).bind(pass);
        if (!depthOnly && batch.key.material().usesForwardTranslucency()) {
            var primitive = materialPrimitive(batch);
            int channels = (primitive == null ? PrimitiveLighting.DEFAULT : primitive.lighting()).channels();
            Objects.requireNonNull(batch.forwardTranslucencySample != null
                    ? batch.forwardTranslucencySample : forwardTranslucencySample).bind(pass, channels, batch.primitiveCapture,
                    batch.state.key.material().usesForwardAtmosphere());
        }
        if (!depthOnly && batch.key.material().translucencyVolume() == MaterialDefinition.TranslucencyVolume.SURFACE_VOLUME)
            Objects.requireNonNull(batch.surfaceTranslucencyEnvironment != null
                    ? batch.surfaceTranslucencyEnvironment : surfaceTranslucencyEnvironment).bind(pass, batch.primitiveCapture);
        bindMaterialFog(pass, batch.key.material());
        if (batch.state.key.material().usesAerialPerspective()) Objects.requireNonNull(aerialPerspective).bind(pass);
        pass.setUniform("DynamicTransforms", transform);
        pass.setUniform("EffectFrame", uniforms.slice(batch.uniformOffset, 48));
        if (batch.instanceUniforms != null) pass.setUniform("MaterialInstance", batch.instanceUniforms);
        MaterialCollectionUniforms.bind(pass, batch.collectionUniforms);
        if (batch.needsMaterialView()) pass.setUniform("MaterialView", java.util.Objects.requireNonNull(materialViewUniforms));
        if (batch.primitiveUniforms != null) pass.setUniform("MaterialPrimitive", batch.primitiveUniforms);
        pass.setUniform("EffectView", uniforms.slice(batch.uniformOffset + block,
                48 + (batch.key.material().macroUV() ? 16 : 0) + (batch.key.material().depthProjection() ? 16 : 0)));
        if (batch.key.material().sceneDepth())
            pass.bindTexture("SceneDepthSampler", shadow == null ? sceneDepth.view()
                    : java.util.Objects.requireNonNull(shadowSceneTexture).getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        if (batch.key.material().sceneColor())
            pass.bindTexture("LfxSceneColor", shadow != null ? java.util.Objects.requireNonNull(shadowSceneTexture).getColorTextureView()
                            : batch.state.storesTransmission ? capture.materialSceneColor() : isolatedSceneColor.view(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        bindMaterialSceneBuffers(pass, batch.key.material(), batch.state.dbufferLookup);
        if (batch.gpuMesh != null) {
            pass.setVertexBuffer(0, null);
            pass.setUniform("NiagaraParticleData", batch.gpuMesh.uniforms());
            pass.setUniform("NiagaraFloatData", batch.gpuMesh.floatData());
            pass.setUniform("NiagaraHalfData", batch.gpuMesh.halfData());
            var sortedBuffer = batch.gpuMesh.sortedIndices();
            pass.setUniform("NiagaraSortedIndices", new GpuBufferSlice(sortedBuffer, 0, sortedBuffer.size()));
        } else if (batch.gpuRibbon != null) {
            var ribbon = batch.gpuRibbon;
            pass.setVertexBuffer(0, null);
            pass.setUniform("NiagaraRibbonData", ribbon.uniforms());
            pass.setUniform("NiagaraFloatData", ribbon.floatData());
            pass.setUniform("NiagaraHalfData", ribbon.halfData());
            pass.setUniform("NiagaraRibbonSorted", ribbon.sorted());
            pass.setUniform("NiagaraRibbonGroups", ribbon.groups());
            pass.setUniform("NiagaraRibbonPacked", ribbon.packed());
            pass.setUniform("NiagaraRibbonTangents", ribbon.tangents());
            pass.setUniform("NiagaraRibbonIndirect", ribbon.indirect());
            pass.setUniform("NiagaraRibbonSlice", ribbon.sliceVertices());
            pass.setIndexBuffer(ribbon.indices(), IndexType.INT);
        } else if (batch.gpuSprite == null) {
            if (batch.extraChunks.isEmpty()) pass.setVertexBuffer(0, vertices.slice(batch.offset, batch.bytes));
        } else {
            pass.setVertexBuffer(0, null);
            pass.setUniform("NiagaraParticleData", batch.gpuSprite.uniforms());
            pass.setUniform("NiagaraFloatData", batch.gpuSprite.floatData());
            pass.setUniform("NiagaraHalfData", batch.gpuSprite.halfData());
            pass.setUniform("NiagaraSortedIndices", batch.gpuSprite.sortedIndices());
            pass.setIndexBuffer(gpuIndices, gpuIndexType);
        }
        bindMaterialTextures(pass, batch.key.material(), drawState, legacySampler);
        if (batch.cutoutBuffers != null) {
            pass.setUniform("SpriteCutoutUVs", batch.cutoutBuffers.coordinates());
            pass.setUniform("SpriteCutoutData", batch.cutoutBuffers.info().slice());
            pass.setIndexBuffer(batch.cutoutBuffers.indices(), IndexType.INT);
        }
    }

    private static void drawMesh(RenderPass pass, Batch batch, GpuBufferSlice vertices, boolean motion,
                                 TranslucentMotionReplayLog.@Nullable DrawCursor motionLogCursor) {
        pass.setUniform("NiagaraMeshData", batch.meshUniforms);
        if (batch.meshScene != null) bindMeshScene(pass, batch.meshScene);
        var section = batch.key.mesh().geometry().sections().get(batch.key.mesh().section());
        for (var geometry : batch.meshChunks) {
            if (!geometry.section().equals(section)) continue;
            pass.setUniform("NiagaraMeshVertices", geometry.vertices());
            pass.setUniform("NiagaraMeshInfo", geometry.info().slice());
            pass.setIndexBuffer(batch.reversedMeshIndices.getOrDefault(geometry, geometry.indices()), IndexType.INT);
            if (batch.gpuMesh != null) {
                if (motionLogCursor != null) motionLogCursor.bindNext(pass);
                var command = batch.meshCommands.get(geometry);
                if (command == null) pass.drawIndexed(geometry.indexCount(), 1, 0, 0, 0);
                else pass.drawIndexedIndirect(command, 1);
            } else if (batch.extraChunks.isEmpty()) {
                if (motionLogCursor != null) motionLogCursor.bindNext(pass);
                pass.drawIndexed(geometry.indexCount(), batch.instances, 0, 0, 0);
            } else {
                int stride = EffectMesh.Kind.MESH.floatsPerInstance() * Float.BYTES;
                for (var chunk : batch.extraChunks) {
                    if (batch.needsMeshScene()) bindMeshScene(pass, batch.meshSceneChunks.get(chunk));
                    pass.setVertexBuffer(0, vertices.slice(batch.offset + (long)chunk.firstInstance() * stride,
                            (long)chunk.instances() * stride));
                    if (motion || batch.state.extraBindings) {
                        pass.setUniform("NiagaraExtraData", chunk.buffer());
                        pass.setUniform("NiagaraExtraInfo", chunk.info());
                    }
                    if (motionLogCursor != null) motionLogCursor.bindNext(pass);
                    pass.drawIndexed(geometry.indexCount(), chunk.instances(), 0, 0, 0);
                }
            }
        }
    }

    private static boolean mirroredComponent(Batch batch, Pose pose) {
        // Unity's index selection already includes the owner determinant in local space.
        return (batch.key.mesh() == null || !batch.key.mesh().instanceWinding())
                && (double) pose.scaleX() * pose.scaleY() * pose.scaleZ() < 0;
    }

    private static @Nullable MaterialPrimitiveFrame materialPrimitive(Batch batch) {
        return batch.gpuSprite != null ? batch.gpuSprite.materialPrimitive()
                : batch.gpuRibbon != null ? batch.gpuRibbon.materialPrimitive()
                : batch.gpuMesh != null ? batch.gpuMesh.materialPrimitive() : batch.parts.getFirst().materialPrimitive();
    }

    private static void bindMeshScene(RenderPass pass, com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms.Output output) {
        var buffer = output.transforms().buffer();
        pass.setUniform("NiagaraMeshSceneTransforms", new GpuBufferSlice(buffer, 0, buffer.size()));
        pass.setUniform("NiagaraMeshSceneInfo", output.info());
    }

    private static final class DrawState {
        final RenderPipeline pipeline;
        final boolean extraBindings;
        final boolean gpu;
        final boolean dbufferLookup;
        final boolean storesTransmission;
        final Key key;
        final boolean dualSource;
        final SlabTreeLighting slabTreeLighting;
        final LegacyEnergy forwardEnergy;
        final Map<MotionKey, RenderPipeline> motionPipelines = new HashMap<>();
        final Map<DepthStencilState, RenderPipeline> motionZeroOpaquePipelines = new HashMap<>();
        final Map<Boolean, RenderPipeline> motionTranslucentLogPipelines = new HashMap<>();
        final Map<TranslucentStepKey, RenderPipeline> motionTranslucentStepPipelines = new HashMap<>();
        private @Nullable RenderPipeline naniteMotionPipeline;
        private record MotionKey(boolean translucent, boolean fromDepth, CompareOp depthCompare,
                                 boolean writeDepth, int colorDepthCompare, boolean colorReplay) { }
        private record TranslucentStepKey(boolean hasVelocity, boolean fromDepth, CompareOp depthCompare,
                                          boolean writeDepth, boolean indirectGuard) { }

        boolean reflectionEnergy() { return slabTreeLighting.reflection() || forwardEnergy.needsTable(); }

        RenderPipeline naniteMotion(MotionShaderAdapter adapter, int index) {
            if (naniteMotionPipeline == null) {
                var set = adapter.naniteCoverage(pipeline);
                var result = MotionPipelineFactory.coverage(pipeline, set, LumenFx.id("pipeline/motion/nanite/" + index), null, true);
                if (!RenderSystem.getDevice().precompilePipeline(result, set.source()).isValid())
                    throw new MotionShaderContractException("Nanite velocity pipeline failed to compile: " + result.getLocation());
                naniteMotionPipeline = result;
            }
            return naniteMotionPipeline;
        }

        RenderPipeline motionZeroOpaque(MotionShaderAdapter adapter, MaterialDefinition material,
                                        boolean opaqueDepthReplay, int index) {
            var depthState = motionDepthStencilState(material, opaqueDepthReplay);
            return motionZeroOpaquePipelines.computeIfAbsent(depthState, ignored -> {
                var set = adapter.zeroOpaqueCoverage(pipeline);
                var result = MotionPipelineFactory.zeroOpaqueCoverage(pipeline, set,
                        LumenFx.id("pipeline/motion/particle_zero/" + index), depthState);
                if (!RenderSystem.getDevice().precompilePipeline(result, set.source()).isValid())
                    throw new MotionShaderContractException("Particle zero-velocity pipeline failed to compile: " + result.getLocation());
                return result;
            });
        }

        RenderPipeline motionTranslucentLog(MotionShaderAdapter adapter, boolean hasVelocity, int index) {
            return motionTranslucentLogPipelines.computeIfAbsent(hasVelocity, ignored -> {
                var set = adapter.translucentMotionLog(pipeline, hasVelocity);
                var extra = BindGroupLayout.builder();
                TranslucentMotionLogLayout.addCaptureBindings(extra);
                if (hasVelocity) {
                    extra.withUniform("ParticleMotionView", UniformType.UNIFORM_BUFFER);
                    if (!gpu && !extraBindings) {
                        extra.withUniform("NiagaraExtraInfo", UniformType.UNIFORM_BUFFER)
                                .withUniform("NiagaraExtraData", UniformType.TEXEL_BUFFER, GpuFormat.R32_FLOAT);
                    }
                }
                var result = MotionPipelineFactory.translucentMotionLog(pipeline, set,
                        LumenFx.id("pipeline/motion/particle_translucent_log/" + index), extra.build(), hasVelocity);
                if (!RenderSystem.getDevice().precompilePipeline(result, set.source()).isValid())
                    throw new MotionShaderContractException("Translucent motion log pipeline failed to compile: " + result.getLocation());
                return result;
            });
        }

        RenderPipeline motionTranslucentStep(MotionShaderAdapter adapter, MaterialDefinition material,
                                             boolean hasVelocity, boolean writesDepth, boolean indirectGuard, int index) {
            var depthState = new DepthStencilState(depthCompare(material.depth().compare()), writesDepth);
            var key = new TranslucentStepKey(hasVelocity, hasVelocity && material.velocityFromDepth(),
                    depthState.depthTest(), depthState.writeDepth(), indirectGuard);
            return motionTranslucentStepPipelines.computeIfAbsent(key, ignored -> {
                var set = adapter.translucentStepCapture(pipeline, key.fromDepth(), hasVelocity, indirectGuard);
                var extra = BindGroupLayout.builder()
                        .withUniform(TranslucentMotionLogLayout.STEP_BASE_UNIFORM, UniformType.UNIFORM_BUFFER);
                if (indirectGuard) {
                    extra.withUniform(TranslucentMotionLogLayout.STEP_INDIRECT_UNIFORM, UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT);
                }
                if (hasVelocity) {
                    extra.withUniform("ParticleMotionView", UniformType.UNIFORM_BUFFER);
                    if (!gpu && !extraBindings) {
                        extra.withUniform("NiagaraExtraInfo", UniformType.UNIFORM_BUFFER)
                                .withUniform("NiagaraExtraData", UniformType.TEXEL_BUFFER, GpuFormat.R32_FLOAT);
                    }
                }
                var result = MotionPipelineFactory.translucentStepCapture(pipeline, set,
                        LumenFx.id("pipeline/motion/particle_translucent_step/" + index),
                        extra.build(), depthState, hasVelocity, indirectGuard);
                if (!RenderSystem.getDevice().precompilePipeline(result, set.source()).isValid())
                    throw new MotionShaderContractException("Translucent step motion pipeline failed to compile: " + result.getLocation());
                return result;
            });
        }

        RenderPipeline motion(MotionShaderAdapter adapter, MaterialDefinition material, boolean opaqueDepthReplay,
                              boolean useColorDepthGuard, int index) {
            boolean translucent = material.blend() != MaterialDefinition.Blend.OPAQUE;
            var depthState = motionDepthStencilState(material, opaqueDepthReplay);
            boolean colorReplay = translucent && !useColorDepthGuard
                    && ParticleMotionPassPlan.usesTranslucentColorReplay(material);
            int colorDepthCompare = translucent && useColorDepthGuard
                    ? ParticleMotionPassPlan.colorDepthCompareCode(material.depth().compare())
                    : MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE;
            var key = new MotionKey(translucent, translucent && material.velocityFromDepth(),
                    depthState.depthTest(), depthState.writeDepth(), colorDepthCompare, colorReplay);
            return motionPipelines.computeIfAbsent(key, ignored -> {
                var set = adapter.particleCoverage(pipeline, key.fromDepth(), !translucent, colorDepthCompare, colorReplay);
                var extra = BindGroupLayout.builder().withUniform("ParticleMotionView", UniformType.UNIFORM_BUFFER);
                if (colorDepthCompare != MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE) {
                    extra.withSampler(MotionShaderAdapter.COLOR_DEPTH_SAMPLER);
                }
                if (!gpu && !extraBindings) extra.withUniform("NiagaraExtraInfo", UniformType.UNIFORM_BUFFER)
                        .withUniform("NiagaraExtraData", UniformType.TEXEL_BUFFER, GpuFormat.R32_FLOAT);
                var result = MotionPipelineFactory.coverage(pipeline, set,
                        LumenFx.id("pipeline/motion/particle/" + index), extra.build(), true, depthState, !translucent, colorReplay);
                if (!RenderSystem.getDevice().precompilePipeline(result, set.source()).isValid())
                    throw new MotionShaderContractException("Particle velocity pipeline failed to compile: " + result.getLocation());
                return result;
            });
        }

        DrawState(Key key, int index, MaterialVertexShaders vertexMaterials, boolean pointShadow, boolean customDepth, boolean nanite) {
            var material = key.material();
            this.key = key;
            boolean depthOnly = pointShadow || customDepth;
            slabTreeLighting = depthOnly ? SlabTreeLighting.NONE : material.slabTreeLighting();
            forwardEnergy = !depthOnly && material.usesForwardTranslucency()
                    ? material.forwardFeatures().legacyEnergy() : LegacyEnergy.NONE;
            dualSource = !depthOnly && !key.modulationOnly() && !key.gammaTarget()
                    && (material.blend() == MaterialDefinition.Blend.COLORED_TRANSMITTANCE
                        || material.blend() == MaterialDefinition.Blend.SOFT_ADDITIVE
                            && material.blendOp() == MaterialBlendOp.ADD
                            && material.fragmentShader().equals(UNITY_PARTICLE_SHADER)
                            && key.storesTransmission())
                    && ParticleDualSourceBlend.supported(RenderSystem.getDevice());
            gpu = key.gpuSprite() || key.gpuRibbon();
            dbufferLookup = key.dbufferLookup();
            storesTransmission = key.storesTransmission();
            extraBindings = !nanite && !gpu && (key.kind() == EffectMesh.Kind.MESH || (material.dynamicSlots() & 14) != 0
                    || key.kind() == EffectMesh.Kind.RIBBON && (material.ribbonParticleData()
                    || material.vertexMaterial() != null && material.vertexMaterial().vertexBasis()));
            var bindings = BindGroupLayout.builder().withUniform("EffectFrame", UniformType.UNIFORM_BUFFER)
                    .withUniform("EffectView", UniformType.UNIFORM_BUFFER);
            if (pointShadow && !nanite) bindings.withUniform("ParticlePointShadowClip", UniformType.UNIFORM_BUFFER);
            if (material.instanceParameters()) bindings.withUniform("MaterialInstance", UniformType.UNIFORM_BUFFER);
            for (String uniform : material.materialCollections()) bindings.withUniform(uniform, UniformType.UNIFORM_BUFFER);
            if (key.gpuSprite()) bindings.withUniform("NiagaraParticleData", UniformType.UNIFORM_BUFFER)
                    .withUniform("NiagaraFloatData", UniformType.TEXEL_BUFFER, GpuFormat.R32_FLOAT)
                    .withUniform("NiagaraHalfData", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                    .withUniform("NiagaraSortedIndices", UniformType.TEXEL_BUFFER, key.meshSceneCulling() ? GpuFormat.RGBA32_SINT : GpuFormat.R32_SINT);
            else if (key.gpuRibbon()) bindings.withUniform("NiagaraRibbonData", UniformType.UNIFORM_BUFFER)
                    .withUniform("NiagaraFloatData", UniformType.TEXEL_BUFFER, GpuFormat.R32_FLOAT)
                    .withUniform("NiagaraHalfData", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                    .withUniform("NiagaraRibbonSorted", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                    .withUniform("NiagaraRibbonGroups", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                    .withUniform("NiagaraRibbonPacked", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                    .withUniform("NiagaraRibbonTangents", UniformType.TEXEL_BUFFER, GpuFormat.R32_FLOAT)
                    .withUniform("NiagaraRibbonIndirect", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                    .withUniform("NiagaraRibbonSlice", UniformType.TEXEL_BUFFER, GpuFormat.R32_FLOAT);
            else if (!nanite && (key.kind() == EffectMesh.Kind.MESH || (material.dynamicSlots() & 14) != 0 || key.kind() == EffectMesh.Kind.RIBBON && (material.ribbonParticleData()
                    || material.vertexMaterial() != null && material.vertexMaterial().vertexBasis())))
                bindings.withUniform("NiagaraExtraInfo", UniformType.UNIFORM_BUFFER)
                        .withUniform("NiagaraExtraData", UniformType.TEXEL_BUFFER, GpuFormat.R32_FLOAT);
            for (String sampler : new java.util.TreeSet<>(material.samplers())) bindings.withSampler(sampler);
            SpecularProfileBindings.layout(bindings, !material.specularProfiles().isEmpty());
            if (material.adaptiveProfile()) bindings.withSampler("LfxSubsurfaceProfiles");
            if (reflectionEnergy()) bindings.withSampler(ParticleLightRenderer.GGX_REFLECTION_SAMPLER);
            if (slabTreeLighting.fuzz()) bindings.withSampler(ParticleLightRenderer.SHEEN_LTC_SAMPLER);
            MaterialVirtualTextureBindings.layout(bindings, material.virtualTextureReads(), material.virtualTextureFeedback());
            if (material.sceneDepth()) bindings.withSampler("SceneDepthSampler");
            if (material.sceneColor()) bindings.withSampler("LfxSceneColor");
            if ((material.customSceneTextures() & 1) != 0) bindings.withSampler("LfxMaterialCustomDepth");
            if ((material.customSceneTextures() & 2) != 0) bindings.withSampler("LfxMaterialCustomStencil");
            for (int slot = 0; slot < MATERIAL_DEFERRED_TEXTURES.length; slot++)
                if ((material.deferredSceneTextures() & 1 << slot) != 0) bindings.withSampler(MATERIAL_DEFERRED_SAMPLERS[slot]);
            if (material.materialView() != 0 || material.vertexMaterial() != null && material.vertexMaterial().firstPersonInputs() != 0)
                bindings.withUniform("MaterialView", UniformType.UNIFORM_BUFFER);
            AtmosphereMaterialTextures.layout(bindings, material.materialView());
            if (material.vertexMaterial() != null && material.vertexMaterial().primitiveMatrices())
                bindings.withUniform("MaterialPrimitive", UniformType.UNIFORM_BUFFER);
            if (dbufferLookup) for (var texture : DBufferTexture.ALL)
                if ((material.dbufferTextures() & texture.mask()) != 0) bindings.withSampler(texture.sampler());
            if (key.cutout()) bindings.withUniform("SpriteCutoutData", UniformType.UNIFORM_BUFFER)
                    .withUniform("SpriteCutoutUVs", UniformType.TEXEL_BUFFER, GpuFormat.RG32_FLOAT);
            if (!nanite && key.kind() == EffectMesh.Kind.MESH) bindings.withUniform("NiagaraMeshData", UniformType.UNIFORM_BUFFER)
                    .withUniform("NiagaraMeshInfo", UniformType.UNIFORM_BUFFER)
                    .withUniform("NiagaraMeshVertices", UniformType.TEXEL_BUFFER, key.dynamicMesh() ? GpuFormat.R32_UINT : GpuFormat.RGBA32_FLOAT);
            if (key.meshScene()) bindings.withUniform("NiagaraMeshSceneInfo", UniformType.UNIFORM_BUFFER)
                    .withUniform("NiagaraMeshSceneTransforms", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT);
            Optional<BlendFunction> blend = authoredBlend(material.blend(), material.blendOp());
            if (key.storesTransmission() && material.blend() != MaterialDefinition.Blend.MODULATE
                    && material.blend() != MaterialDefinition.Blend.HOLDOUT && !dualSource) {
                var authored = blend.orElseThrow(() -> new IllegalArgumentException(
                        "Opaque material cannot be submitted as separate translucency"));
                blend = Optional.of(PremultipliedBlendPlan.transmission(authored));
            }
            if (dualSource) blend = Optional.of(key.storesTransmission() ? ParticleDualSourceBlend.SEPARATE : ParticleDualSourceBlend.SCENE);
            if (key.modulationOnly()) blend = Optional.of(new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ZERO));
            boolean additive = material.blend() == MaterialDefinition.Blend.ADD
                    || material.blend() == MaterialDefinition.Blend.ADDITIVE
                    || material.blend() == MaterialDefinition.Blend.SOFT_ADDITIVE;
            var builder = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                    .withShaderDefine("LFX_KIND_SPRITE", key.kind() == EffectMesh.Kind.SPRITE ? 1 : 0)
                    .withShaderDefine("LFX_KIND_MESH", key.kind() == EffectMesh.Kind.MESH ? 1 : 0)
                    .withShaderDefine("LFX_KIND_RIBBON", key.kind() == EffectMesh.Kind.RIBBON ? 1 : 0)
                    .withShaderDefine("LFX_MATERIAL_INSTANCE", material.instanceParameters() ? 1 : 0)
                    .withShaderDefine("LFX_HAS_SAMPLER0", material.samplers().contains("Sampler0") ? 1 : 0)
                    .withShaderDefine("LFX_HAS_SAMPLER1", material.samplers().contains("Sampler1") ? 1 : 0)
                    .withShaderDefine("LFX_HAS_SAMPLER2", material.samplers().contains("Sampler2") ? 1 : 0)
                    .withShaderDefine("LFX_HAS_SAMPLER3", material.samplers().contains("Sampler3") ? 1 : 0)
                    .withShaderDefine("LFX_UNITY_PARTICLE_LIT", material.fragmentShader().equals(UNITY_PARTICLE_SHADER)
                            && material.vertexMaterial() != null
                            && material.vertexMaterial().vertexBasis()
                            && material.vertexMaterial().shader().equals(UNITY_PARTICLE_LIT_VERTEX) ? 1 : 0)
                    .withShaderDefine("LFX_NANITE_MATERIAL", nanite ? 1 : 0)
                    .withShaderDefine("LFX_VERTEX_MATERIAL", material.vertexMaterial() != null ? 1 : 0)
                    .withShaderDefine("LFX_SG_UV4_MASK", material.sgUv4Mask())
                    .withShaderDefine("LFX_MATERIAL_BASIS", material.vertexMaterial() != null && material.vertexMaterial().vertexBasis() ? 1 : 0)
                    .withShaderDefine("LFX_SPHERICAL_SPRITE_NORMALS", key.kind() == EffectMesh.Kind.SPRITE
                            && material.vertexMaterial() != null && material.vertexMaterial().sphericalParticleNormals() ? 1 : 0)
                    .withShaderDefine("LFX_APPLY_FOG", !depthOnly && material.fog().enabled() ? 1 : 0)
                    .withShaderDefine("LFX_TRANSLUCENCY_LIGHTING", !depthOnly && material.usesTranslucencyVolume() ? 1 : 0)
                    .withShaderDefine("LFX_TLV_VERTEX", depthOnly ? 0 : material.translucencyVolume().vertexMode())
                    .withShaderDefine("LFX_TLV_INDIRECT", !depthOnly && material.usesVolumeIndirect() ? 1 : 0)
                    .withShaderDefine("LFX_TLV_VLM", !depthOnly && material.usesVolumeIndirect()
                            && material.forwardFeatures().volumetricLightmap() ? 1 : 0)
                    .withShaderDefine("LFX_FORWARD_TRANSLUCENCY", !depthOnly && material.usesForwardTranslucency() ? 1 : 0)
                    .withShaderDefine("LFX_SURFACE_VOLUME_ENVIRONMENT", !depthOnly && material.usesSurfaceVolumeEnvironment() ? 1 : 0)
                    .withShaderDefine("LFX_FORWARD_RECT_LIGHTS", !depthOnly && material.forwardFeatures().rectLights() ? 1 : 0)
                    .withShaderDefine("LFX_FORWARD_VOLUMETRIC_LIGHTMAP", !depthOnly && material.forwardFeatures().volumetricLightmap() ? 1 : 0)
                    .withShaderDefine("LFX_FORWARD_SKY_REFLECTION", !depthOnly && material.forwardFeatures().skyReflection() ? 1 : 0)
                    .withShaderDefine("LFX_FORWARD_PREINTEGRATED_GF", !depthOnly && material.forwardFeatures().preintegratedGf() ? 1 : 0)
                    .withShaderDefine("LFX_FORWARD_SKY_BLEND", !depthOnly && material.forwardFeatures().skyBlend() ? 1 : 0)
                    .withShaderDefine("LFX_FORWARD_LOCAL_CAPTURES", !depthOnly && material.forwardFeatures().localCaptures() ? 1 : 0)
                    .withShaderDefine("LFX_FORWARD_BLEND_LOCAL_CAPTURES", material.singleLocalCapture() ? 0 : 1)
                    .withShaderDefine("LFX_FORCE_FORWARD_SKY_BLEND", !depthOnly && material.forwardFeatures().forceSkyBlend() ? 1 : 0)
                    .withShaderDefine("LFX_PIXEL_FOG", material.fog().perPixel() ? 1 : 0)
                    .withShaderDefine("LFX_LINEAR_TARGET", key.linearTarget() ? 1 : 0)
                    .withShaderDefine("LFX_EXPONENTIAL_FOG", material.fogKind() != FogKind.HOST ? 1 : 0)
                    .withShaderDefine("LFX_VOLUMETRIC_FOG", material.fogKind() == FogKind.VOLUMETRIC ? 1 : 0)
                    .withShaderDefine("LFX_EXTRA_MATERIAL", (material.dynamicSlots() & 14) != 0 ? 1 : 0)
                    .withShaderDefine("LFX_MESH_SCENE", key.meshScene() ? 1 : 0)
                    .withShaderDefine("LFX_DYNAMIC_MESH", key.dynamicMesh() ? 1 : 0)
                    .withShaderDefine("LFX_MESH_SCENE_CULLING", key.meshSceneCulling() ? 1 : 0)
                    .withShaderDefine("LFX_RIBBON_PARTICLE_DATA", material.ribbonParticleData() ? 1 : 0)
                    .withShaderDefine("LFX_SCENE_DEPTH", material.sceneDepth() ? 1 : 0)
                    .withShaderDefine("LFX_SCENE_COLOR", material.sceneColor() ? 1 : 0)
                    .withShaderDefine("LFX_CUSTOM_SCENE_TEXTURES", material.customSceneTextures())
                    .withShaderDefine("LFX_DEFERRED_SCENE_TEXTURES", material.deferredSceneTextures())
                    .withShaderDefine("LFX_DBUFFER_LOOKUP_MASK", material.dbufferTextures())
                    .withShaderDefine("LFX_DBUFFER_LOOKUP_ACTIVE", dbufferLookup ? 1 : 0)
                    .withShaderDefine("LFX_RECEIVES_DECALS", key.receivesDecals() && material.receivesDecals() ? 1 : 0)
                    .withShaderDefine("LFX_MACRO_UV", material.macroUV() ? 1 : 0)
                    .withShaderDefine("LFX_DEPTH_PROJECTION", material.depthProjection() ? 1 : 0)
                    .withShaderDefine("LFX_SPRITE_CUTOUT", key.cutout() ? 1 : 0)
                    .withShaderDefine("LFX_PARTICLE_POINT_SHADOW", pointShadow ? 1 : 0)
                    .withShaderDefine("LFX_PARTICLE_CUSTOM_DEPTH", customDepth ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_DUAL_SOURCE", dualSource ? 1 : 0)
                    .withShaderDefine("LFX_SUBSTRATE_MODULATION_ONLY", key.modulationOnly() ? 1 : 0)
                    .withShaderDefine("LFX_PARTICLE_GAMMA_TARGET", key.gammaTarget() ? 1 : 0)
                    .withShaderDefine("LFX_UNITY_PARTICLE_MULTIPLY", material.fragmentShader().equals(UNITY_PARTICLE_SHADER)
                            && material.blend() == MaterialDefinition.Blend.MODULATE ? 1 : 0)
                    .withShaderDefine("LFX_FOG_BLEND", additive ? 1
                            : material.blend() == MaterialDefinition.Blend.PREMULTIPLIED || material.blend() == MaterialDefinition.Blend.HOLDOUT ? 2 : 0)
                    .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).withBindGroupLayout(bindings.build())
                    .withLocation(LumenFx.id((nanite ? "pipeline/nanite_material_" : pointShadow ? "pipeline/point_shadow_particle_"
                            : customDepth ? "pipeline/custom_depth_particle_base_" : "pipeline/effect_") + index))
                    .withVertexShader(LumenFx.id(nanite ? "post/fullscreen" : key.gpuRibbon() ? "core/niagara_gpu_ribbon" : key.gpuSprite()
                            ? key.kind() == EffectMesh.Kind.MESH ? "core/niagara_gpu_mesh" : "core/niagara_gpu_sprite"
                            : key.kind() == EffectMesh.Kind.SPRITE ? "core/instance_sprite"
                            : key.kind() == EffectMesh.Kind.MESH ? "core/instance_mesh" : "core/instance_ribbon"))
                    .withFragmentShader(material.fragmentShader())
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(!nanite && !pointShadow && material.cull() && !key.disableCull())
                    .withDepthStencilState(depthOnly ? new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true)
                            : nanite ? new DepthStencilState(depthCompare(material.depth().compare()),
                                    material.blend() == MaterialDefinition.Blend.OPAQUE && material.depth().write()
                                            && material.depth().compare() != MaterialDepth.Compare.DISABLED
                                            && material.depth().compare() != MaterialDepth.Compare.NEVER)
                            : new DepthStencilState(depthCompare(material.depth().compare()),
                                    !key.modulationOnly() && material.depth().write()
                                            && material.depth().compare() != MaterialDepth.Compare.DISABLED
                                            && material.depth().compare() != MaterialDepth.Compare.NEVER))
                    .withColorTargetState(new ColorTargetState(depthOnly ? Optional.empty() : blend, key.format(),
                            depthOnly ? 0 : key.modulationOnly() || material.blend() == MaterialDefinition.Blend.MODULATE
                                    || material.hdr() && additive ? 7 : 15));
            if (key.gammaTarget()) builder.withColorTargetState(1,
                    new ColorTargetState(Optional.empty(), GammaParticleBlendTargets.COVERAGE_FORMAT, 1));
            if (nanite) builder.withBindGroupLayout(NaniteMaterialGeometry.LAYOUT);
            if (!nanite && !key.gpuSprite() && !key.gpuRibbon()) builder.withVertexBinding(0,
                    key.kind() == EffectMesh.Kind.SPRITE ? SPRITES : key.kind() == EffectMesh.Kind.MESH ? MESHES : RIBBONS);
            if (!depthOnly && material.fog().enabled()) {
                if (material.fogKind() == FogKind.VOLUMETRIC)
                    builder.withBindGroupLayout(BindGroupLayout.builder().withUniform("ExponentialFog", UniformType.UNIFORM_BUFFER)
                            .withUniform("FogVolume", UniformType.UNIFORM_BUFFER).withSampler("FogVolumeSampler").build());
                else if (material.fogKind() == FogKind.EXPONENTIAL)
                    builder.withBindGroupLayout(BindGroupLayout.builder().withUniform("ExponentialFog", UniformType.UNIFORM_BUFFER).build());
                else builder.withBindGroupLayout(BindGroupLayouts.FOG);
            }
            if (material.usesAerialPerspective()) {
                builder.withShaderDefine(AerialPerspectiveBinding.DEFINE, 1);
                builder.withBindGroupLayout(AerialPerspectiveBinding.layout());
            }
            if (!depthOnly && material.usesTranslucencyVolume()) {
                var volumeBindings = BindGroupLayout.builder();
                TranslucencyLightingVolume.addBindings(volumeBindings, material.samplesTranslucencyPerPixel());
                if (material.usesVolumeIndirect())
                    SceneIndirectDiffuse.layout(volumeBindings, material.forwardFeatures().volumetricLightmap());
                builder.withBindGroupLayout(volumeBindings.build());
            }
            if (!depthOnly && material.usesForwardTranslucency()) {
                if (material.forwardFeatures().clearCoatBottomNormal())
                    builder.withShaderDefine("LFX_CLEAR_COAT_BOTTOM_NORMAL");
                if (material.forwardFeatures().roughDiffuse())
                    builder.withShaderDefine("LFX_LEGACY_ROUGH_DIFFUSE", 1);
                var forwardBindings = BindGroupLayout.builder();
                ForwardTranslucentLights.layout(forwardBindings, material.forwardFeatures(), material.singleLocalCapture());
                if (material.usesForwardAtmosphere()) {
                    builder.withShaderDefine(ForwardAtmosphereTransmittanceBinding.SHADER_DEFINE, 1);
                    ForwardAtmosphereTransmittanceBinding.layout(forwardBindings);
                }
                builder.withBindGroupLayout(forwardBindings.build());
            } else if (!depthOnly && material.usesSurfaceVolumeEnvironment()) {
                var surfaceBindings = BindGroupLayout.builder();
                SurfaceTranslucencyEnvironment.layout(surfaceBindings, material.forwardFeatures(), material.singleLocalCapture());
                builder.withBindGroupLayout(surfaceBindings.build());
            }
            if (!nanite && material.vertexMaterial() != null) {
                var base = LumenFx.id(key.gpuRibbon() ? "core/niagara_gpu_ribbon" : key.gpuSprite()
                        ? key.kind() == EffectMesh.Kind.MESH ? "core/niagara_gpu_mesh" : "core/niagara_gpu_sprite"
                        : key.kind() == EffectMesh.Kind.SPRITE ? "core/instance_sprite"
                        : key.kind() == EffectMesh.Kind.MESH ? "core/instance_mesh" : "core/instance_ribbon");
                builder.withVertexShader(vertexMaterials.create(base, material.vertexMaterial().shader()));
            }
            SpecularProfileBindings.define(builder, material.specularProfiles());
            ToonProfileBindings.define(builder, material.toonProfiles());
            for (int slot = 0; slot < material.slabSubsurfaceProfiles().size(); slot++) {
                int profileIndex = VfxEngine.INSTANCE.subsurfaceProfileIndex(material.slabSubsurfaceProfiles().get(slot));
                builder.withShaderDefine("LFX_SLAB_SUBSURFACE_PROFILE_" + slot, profileIndex);
            }
            if (reflectionEnergy()) builder.withShaderDefine("LFX_REFLECTION_ENERGY");
            if (slabTreeLighting.reflection()) {
                builder.withShaderDefine("LFX_SUBSTRATE_CONSERVATION", slabTreeLighting.conserve() ? 1 : 0)
                        .withShaderDefine("LFX_SUBSTRATE_PRESERVATION", slabTreeLighting.preserve() ? 1 : 0);
            }
            if (forwardEnergy.enabled()) builder.withShaderDefine("LFX_LEGACY_ENERGY", 1)
                    .withShaderDefine("LFX_LEGACY_ENERGY_CONSERVE", forwardEnergy.conserve() ? 1 : 0)
                    .withShaderDefine("LFX_LEGACY_ENERGY_PRESERVE", forwardEnergy.preserve() ? 1 : 0);
            if (slabTreeLighting.fuzz()) builder.withShaderDefine("LFX_SUBSTRATE_TREE_FUZZ");
            if (material.adaptiveProfile()) builder.withShaderDefine("LFX_SUBSTRATE_ADAPTIVE_PROFILE", 1);
            pipeline = builder.build();
            if (!nanite && material.vertexMaterial() != null && !RenderSystem.getDevice().precompilePipeline(pipeline, vertexMaterials).isValid())
                throw new IllegalStateException("Material vertex pipeline failed to compile: " + pipeline.getLocation());
        }
    }

    private static CompareOp depthCompare(MaterialDepth.Compare compare) {
        return switch (compare) {
            case DISABLED, ALWAYS -> CompareOp.ALWAYS_PASS;
            case NEVER -> CompareOp.NEVER_PASS;
            case LESS -> CompareOp.GREATER_THAN;
            case EQUAL -> CompareOp.EQUAL;
            case LESS_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
            case GREATER -> CompareOp.LESS_THAN;
            case NOT_EQUAL -> CompareOp.NOT_EQUAL;
            case GREATER_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
        };
    }

    public void prepareStaticMeshResidency(long resourceGeneration, java.util.Collection<StaticMeshGeometry> lods) {
        requireClientThread();
        if (lods.isEmpty()) return;
        selectGeneration(resourceGeneration);
        staticMeshes.prepare(RenderSystem.getDevice().createCommandEncoder(), lods, meshTexelLimit());
    }

    private void selectGeneration(long resourceGeneration) {
        if (generation == resourceGeneration) return;
        close();
        generation = resourceGeneration;
    }

    private long meshTexelLimit() {
        if (maximumMeshTexels == 0) {
            try (var runtime = com.kltyton.lumenfx.client.render.compute.ComputeRuntime.create(RenderSystem.getDevice())) {
                maximumMeshTexels = Math.min(runtime.maxTexelBufferElements(),
                        RenderSystem.getDevice().getDeviceInfo().limits().maxMemoryAllocationSize() / 16);
            }
        }
        return maximumMeshTexels;
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("VFX uploads require the client thread");
    }
    @Override public void close() { releaseBuffers(); }
    public void expirePointShadows(double time) { requireClientThread(); pointFunctions.expireShadows(time); }
    public Map<ScenePointLight, VolumeShadow> volumeShadows() { return pointFunctions.volumeShadows(); }
    public void removePointShadow(long identity) { requireClientThread(); pointFunctions.removeShadow(identity); }
    public void resetPointShadowCache() { requireClientThread(); pointFunctions.resetShadowCache(); }
    public void releaseBuffers() {
        requireClientThread();
        states.clear(); naniteStates.clear(); naniteProgrammable.clear(); batches.clear(); drawing.clear(); fogUniforms.clear();
        vertexMaterials.clear();
        if (motionShaders != null) motionShaders.clear();
        motionShaders = null; motionUniforms = null; motionHistoryValid = false;
        if (opaqueMotionDepthView != null) { opaqueMotionDepthView.close(); opaqueMotionDepthView = null; }
        if (opaqueMotionDepthTexture != null) { opaqueMotionDepthTexture.close(); opaqueMotionDepthTexture = null; }
        frameMotionRealTime = frameMotionEngineTime = Double.NaN;
        frameMotionProjectionKnown = false;
        frameMotionMatricesKnown = false;
        frameMotionFirstPersonKnown = false;
        cutouts.values().forEach(com.kltyton.lumenfx.client.render.niagara.SpriteCutoutBuffers::close);
        cutouts.clear();
        staticMeshes.close();
        lights.close();
        translucencyLighting.close();
        translucencyLightingSample = null;
        vertexTranslucencyIndirect = null;
        forwardTranslucency.close();
        surfaceTranslucencyEnvironment = null;
        aerialPerspective = null;
        directionalShadows.close();
        volumeDirectionalShadows.close();
        forwardTranslucencySample = null;
        materialReflectionEnergy = null;
        subsurfaceScattering.close();
        deferredSkyLighting.clear();
        directionalParticleLighting.clear();
        localReflections.close();
        opaqueParticleSurfaces.close();
        adaptiveParticleReplays.clear();
        particleDecalDepth.close();
        shadowPipelines.clear();
        customDepthPipelines.clear();
        sceneDepthDefaults.close();
        materialCustomDepth = materialCustomStencil = null;
        if (blackMaterialScene != null) { blackMaterialScene.close(); blackMaterialScene = null; }
        if (whiteMaterialScene != null) { whiteMaterialScene.close(); whiteMaterialScene = null; }
        java.util.Arrays.fill(materialDeferred, null);
        if (shadowSceneTexture != null) { shadowSceneTexture.close(); shadowSceneTexture = null; }
        pointFunctions.close();
        decals.close();
        nativeDecalMaterials.close();
        shaderpackDecalColors.clear();
        gammaBlendTargets.close();
        maximumMeshTexels = 0;
        sceneDepth.close();
        isolatedSceneColor.close();
        materialViewUniforms = null;
        materialExtras.close();
        vertexStreamRing.close();
    }
}
