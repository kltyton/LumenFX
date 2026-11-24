package com.kltyton.lumenfx.effect;

import java.util.Map;
import java.util.List;
import java.util.HashSet;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionBinding;
import com.kltyton.lumenfx.effect.render.decal.DecalMaterialSettings;
import com.kltyton.lumenfx.effect.render.decal.DBufferTexture;
import org.jspecify.annotations.Nullable;
import com.kltyton.lumenfx.effect.render.MaterialFog;
import com.kltyton.lumenfx.effect.render.MaterialDepth;
import com.kltyton.lumenfx.effect.render.MaterialBlendOp;
import com.kltyton.lumenfx.effect.render.SpriteCoverage;
import com.kltyton.lumenfx.effect.render.TranslucencyPass;
import com.kltyton.lumenfx.effect.render.vertex.VertexMaterialSettings;
import com.kltyton.lumenfx.effect.render.mesh.NaniteMaterialSource;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import com.kltyton.lumenfx.effect.render.material.MaterialSamplerSource;
import com.kltyton.lumenfx.effect.render.material.MaterialTextureSampler;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues;
import net.minecraft.resources.Identifier;

public record MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                                 MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                                 MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                                 boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                                  boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                                  int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                                  @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                                  MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                                  int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                                  boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                                  boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                                  boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                                  @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                                  int adaptiveSubstrateWords, boolean lightFunctionAtlasCompatible,
                                  TranslucencyVolume translucencyVolume, @Nullable NaniteMaterialSource naniteSource,
                                  boolean highQualityForwardReflections, Map<String, MaterialSamplerSource> textureSamplerSources,
                                  Map<String, MaterialTextureSampler> textureSamplers,
                                  MaterialBlendOp blendOp, int sgUv4Mask, List<String> toonProfiles,
                                  int automaticSpriteCoverageChannels) {
    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                              int adaptiveSubstrateWords, boolean lightFunctionAtlasCompatible,
                              TranslucencyVolume translucencyVolume, @Nullable NaniteMaterialSource naniteSource,
                              boolean highQualityForwardReflections, Map<String, MaterialSamplerSource> textureSamplerSources,
                              Map<String, MaterialTextureSampler> textureSamplers, MaterialBlendOp blendOp, int sgUv4Mask,
                              List<String> toonProfiles) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots,
                ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity, velocityFromDepth,
                decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial, lightFunction,
                instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads, virtualTextureFeedback,
                defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures, deferredSceneTextures,
                anisotropicSurface, customSurfaceData, particleLightingFeatures, subsurfaceProfile, slabSubsurfaceProfiles,
                specularProfiles, adaptiveSubstrateWords, lightFunctionAtlasCompatible, translucencyVolume, naniteSource,
                highQualityForwardReflections, textureSamplerSources, textureSamplers, blendOp, sgUv4Mask, toonProfiles,
                SpriteCoverage.legacyAutomaticChannels(blend));
    }
    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                              int adaptiveSubstrateWords, boolean lightFunctionAtlasCompatible,
                              TranslucencyVolume translucencyVolume, @Nullable NaniteMaterialSource naniteSource,
                              boolean highQualityForwardReflections, Map<String, MaterialSamplerSource> textureSamplerSources,
                              Map<String, MaterialTextureSampler> textureSamplers, MaterialBlendOp blendOp, int sgUv4Mask) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots,
                ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity, velocityFromDepth,
                decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial, lightFunction,
                instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads, virtualTextureFeedback,
                defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures, deferredSceneTextures,
                anisotropicSurface, customSurfaceData, particleLightingFeatures, subsurfaceProfile, slabSubsurfaceProfiles,
                specularProfiles, adaptiveSubstrateWords, lightFunctionAtlasCompatible, translucencyVolume, naniteSource,
                highQualityForwardReflections, textureSamplerSources, textureSamplers, blendOp, sgUv4Mask, List.of());
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                              int adaptiveSubstrateWords, boolean lightFunctionAtlasCompatible,
                              TranslucencyVolume translucencyVolume, @Nullable NaniteMaterialSource naniteSource,
                              boolean highQualityForwardReflections, Map<String, MaterialSamplerSource> textureSamplerSources,
                              Map<String, MaterialTextureSampler> textureSamplers, MaterialBlendOp blendOp) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots,
                ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity, velocityFromDepth,
                decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial, lightFunction,
                instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads, virtualTextureFeedback,
                defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures, deferredSceneTextures,
                anisotropicSurface, customSurfaceData, particleLightingFeatures, subsurfaceProfile, slabSubsurfaceProfiles,
                specularProfiles, adaptiveSubstrateWords, lightFunctionAtlasCompatible, translucencyVolume, naniteSource,
                highQualityForwardReflections, textureSamplerSources, textureSamplers, blendOp, 0);
    }
    public static final int MAX_ADAPTIVE_SUBSTRATE_WORDS = 128;
    public static final int VIEW_STATE_FRAME_INDEX = 256;
    public static final int ATMOSPHERE_LIGHT_VIEW_INPUT = 4096;
    public static final int DISTANT_SKY_LIGHT_VIEW_INPUT = 8192;
    public static final int ATMOSPHERE_ILLUMINANCE_VIEW_INPUT = 16384;
    public static final int ATMOSPHERE_SKY_VIEW_INPUT = 32768;
    public static final int ATMOSPHERE_DISK_VIEW_INPUT = 65536;
    public static final int ATMOSPHERE_AERIAL_VIEW_INPUT = 131072;
    public static final int EYE_ADAPTATION_VIEW_INPUT = 262144;
    public static final int POST_VOLUME_FLAGS_VIEW_INPUT = 524288;
    public static final int MATERIAL_VIEW_INPUTS = 255 | VIEW_STATE_FRAME_INDEX | ATMOSPHERE_LIGHT_VIEW_INPUT | DISTANT_SKY_LIGHT_VIEW_INPUT
            | ATMOSPHERE_ILLUMINANCE_VIEW_INPUT | ATMOSPHERE_SKY_VIEW_INPUT | ATMOSPHERE_DISK_VIEW_INPUT
            | ATMOSPHERE_AERIAL_VIEW_INPUT | EYE_ADAPTATION_VIEW_INPUT | POST_VOLUME_FLAGS_VIEW_INPUT;
    public static final int GBUFFER_SCENE_TEXTURES = 7 | 32 | 64 | 128 | 256 | 512;
    public static final int RAW_SCENE_COLOR = 8;
    public static final int SCENE_AMBIENT_OCCLUSION = 16;
    public static final int DEFERRED_SCENE_TEXTURES = GBUFFER_SCENE_TEXTURES | RAW_SCENE_COLOR | SCENE_AMBIENT_OCCLUSION;
    public static final int SUBSTRATE_SLAB = 1, SUBSTRATE_FUZZ = 2, PREINTEGRATED_SKIN = 4, LEGACY_HAIR = 8;
    public static final int LEGACY_SUBSURFACE_PROFILE = 16;
    public static final int SUBSTRATE_HAIR = 32;
    public static final int SUBSTRATE_EYE = 64;
    public static final int SUBSTRATE_WRAP = 128;
    public static final int SUBSTRATE_SPECULAR_PROFILE = 256;
    public static final int SUBSTRATE_VOLUME = 512;
    public static final int SUBSTRATE_DIFFUSION = 1024;
    public static final int SUBSTRATE_TOON = 2048;
    public static final int UNITY_CLEAR_COAT = 4096;
    public static final int SUBSURFACE_DIFFUSION = LEGACY_SUBSURFACE_PROFILE | SUBSTRATE_DIFFUSION;
    public static final int SUBSTRATE_SURFACE_PACKING = SUBSTRATE_SLAB | SUBSTRATE_HAIR | SUBSTRATE_EYE | SUBSTRATE_TOON;
    public static final int FLOATING_SURFACE_DATA = SUBSTRATE_SLAB | SUBSTRATE_EYE | SUBSTRATE_TOON | UNITY_CLEAR_COAT;
    public static final int PARTICLE_LIGHTING_FEATURES = SUBSTRATE_SLAB | SUBSTRATE_FUZZ | PREINTEGRATED_SKIN | LEGACY_HAIR
            | LEGACY_SUBSURFACE_PROFILE | SUBSTRATE_HAIR | SUBSTRATE_EYE | SUBSTRATE_WRAP | SUBSTRATE_SPECULAR_PROFILE
            | SUBSTRATE_VOLUME | SUBSTRATE_DIFFUSION | SUBSTRATE_TOON | UNITY_CLEAR_COAT;
    public enum PointShadowCoverage { NONE, SOLID, MASKED }
    public enum TranslucencyVolume {
        NONE(0), PIXEL(0), VERTEX_NON_DIRECTIONAL(1), VERTEX_DIRECTIONAL(2), SURFACE_PER_PIXEL(0), SURFACE_VOLUME(0);

        private final int vertexMode;
        TranslucencyVolume(int vertexMode) { this.vertexMode = vertexMode; }
        public int vertexMode() { return vertexMode; }
        public boolean usesVolume() { return this != NONE && this != SURFACE_PER_PIXEL; }
        public boolean samplesPerPixel() { return this == PIXEL || this == SURFACE_VOLUME; }
        public boolean usesSurfaceEnvironment() { return this == SURFACE_PER_PIXEL || this == SURFACE_VOLUME; }
    }

    public boolean usesTranslucencyVolume() { return translucencyVolume.usesVolume(); }
    public boolean usesVolumeIndirect() { return translucencyVolume.vertexMode() > 0 || translucencyVolume == TranslucencyVolume.PIXEL; }
    public boolean samplesTranslucencyPerPixel() { return translucencyVolume.samplesPerPixel(); }
    public boolean usesForwardTranslucency() { return translucencyVolume == TranslucencyVolume.SURFACE_PER_PIXEL; }

    public boolean appliesBasePassFog() {
        return fog.enabled() && !sceneColor;
    }

    public boolean usesAerialPerspectiveFog() {
        return blend != Blend.OPAQUE && appliesBasePassFog() && !fog.sky();
    }

    public int materialView(boolean firstPerson) {
        return materialView | (firstPerson && vertexMaterial != null ? vertexMaterial.firstPersonViewInputs() : 0);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                              int adaptiveSubstrateWords, boolean lightFunctionAtlasCompatible, TranslucencyVolume translucencyVolume,
                              @Nullable NaniteMaterialSource naniteSource, boolean highQualityForwardReflections) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots,
                ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity, velocityFromDepth,
                decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial, lightFunction,
                instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads, virtualTextureFeedback,
                defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures, deferredSceneTextures,
                anisotropicSurface, customSurfaceData, particleLightingFeatures, subsurfaceProfile, slabSubsurfaceProfiles,
                specularProfiles, adaptiveSubstrateWords, lightFunctionAtlasCompatible, translucencyVolume, naniteSource,
                highQualityForwardReflections, Map.of());
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                              int adaptiveSubstrateWords, boolean lightFunctionAtlasCompatible,
                              TranslucencyVolume translucencyVolume, @Nullable NaniteMaterialSource naniteSource,
                              boolean highQualityForwardReflections, Map<String, MaterialSamplerSource> textureSamplerSources) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures,
                deferredSceneTextures, anisotropicSurface, customSurfaceData, particleLightingFeatures, subsurfaceProfile,
                slabSubsurfaceProfiles, specularProfiles, adaptiveSubstrateWords, lightFunctionAtlasCompatible, translucencyVolume, naniteSource,
                highQualityForwardReflections, textureSamplerSources, Map.of(), MaterialBlendOp.ADD);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                              int adaptiveSubstrateWords, boolean lightFunctionAtlasCompatible,
                              TranslucencyVolume translucencyVolume, @Nullable NaniteMaterialSource naniteSource,
                              boolean highQualityForwardReflections, Map<String, MaterialSamplerSource> textureSamplerSources,
                              MaterialBlendOp blendOp) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures,
                deferredSceneTextures, anisotropicSurface, customSurfaceData, particleLightingFeatures, subsurfaceProfile,
                slabSubsurfaceProfiles, specularProfiles, adaptiveSubstrateWords, lightFunctionAtlasCompatible, translucencyVolume,
                naniteSource, highQualityForwardReflections, textureSamplerSources, Map.of(), blendOp);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                              int adaptiveSubstrateWords, boolean lightFunctionAtlasCompatible, TranslucencyVolume translucencyVolume,
                              @Nullable NaniteMaterialSource naniteSource) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots,
                ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity, velocityFromDepth,
                decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial, lightFunction,
                instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads, virtualTextureFeedback,
                defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures, deferredSceneTextures,
                anisotropicSurface, customSurfaceData, particleLightingFeatures, subsurfaceProfile, slabSubsurfaceProfiles,
                specularProfiles, adaptiveSubstrateWords, lightFunctionAtlasCompatible, translucencyVolume, naniteSource, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                              int adaptiveSubstrateWords, boolean lightFunctionAtlasCompatible, TranslucencyVolume translucencyVolume) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots,
                ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity, velocityFromDepth,
                decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial, lightFunction,
                instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads, virtualTextureFeedback,
                defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures, deferredSceneTextures,
                anisotropicSurface, customSurfaceData, particleLightingFeatures, subsurfaceProfile, slabSubsurfaceProfiles,
                specularProfiles, adaptiveSubstrateWords, lightFunctionAtlasCompatible, translucencyVolume, null);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                              int adaptiveSubstrateWords, boolean lightFunctionAtlasCompatible) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures,
                deferredSceneTextures, anisotropicSurface, customSurfaceData, particleLightingFeatures, subsurfaceProfile,
                slabSubsurfaceProfiles, specularProfiles, adaptiveSubstrateWords, lightFunctionAtlasCompatible, TranslucencyVolume.NONE);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile, List<String> slabSubsurfaceProfiles, List<String> specularProfiles,
                              int adaptiveSubstrateWords) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures,
                deferredSceneTextures, anisotropicSurface, customSurfaceData, particleLightingFeatures, subsurfaceProfile,
                slabSubsurfaceProfiles, specularProfiles, adaptiveSubstrateWords, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures,
                              @Nullable String subsurfaceProfile) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures,
                deferredSceneTextures, anisotropicSurface, customSurfaceData, particleLightingFeatures,
                subsurfaceProfile, List.of(), List.of(), 0);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData, int particleLightingFeatures) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures,
                deferredSceneTextures, anisotropicSurface, customSurfaceData, particleLightingFeatures, null);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface, boolean customSurfaceData) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, translucentCustomDepth,
                customSceneTextures, deferredSceneTextures, anisotropicSurface, customSurfaceData, 0);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures, int deferredSceneTextures,
                              boolean anisotropicSurface) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, translucentCustomDepth,
                customSceneTextures, deferredSceneTextures, anisotropicSurface, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth, int customSceneTextures) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, translucentCustomDepth, customSceneTextures, 0, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage,
                              boolean translucentCustomDepth) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, translucentCustomDepth, 0);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface, PointShadowCoverage pointShadowCoverage) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, pointShadowCoverage, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback, boolean defaultLitSurface) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, defaultLitSurface, legacyPointShadowCoverage(blend, decalSettings, lightFunction, runtimeVirtualTextureOutputs));
    }

    public static PointShadowCoverage legacyPointShadowCoverage(Blend blend, @Nullable DecalMaterialSettings decalSettings,
                                                                boolean lightFunction, int virtualTextureOutputs) {
        return blend == Blend.OPAQUE && decalSettings == null && !lightFunction && virtualTextureOutputs == 0
                ? PointShadowCoverage.SOLID : PointShadowCoverage.NONE;
    }
    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections,
                              int runtimeVirtualTextureOutputs, List<VirtualTextureRead> virtualTextureReads,
                              boolean virtualTextureFeedback) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, runtimeVirtualTextureOutputs, virtualTextureReads,
                virtualTextureFeedback, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction,
                              MaterialInstanceParameters instanceParameters, List<MaterialCollectionBinding> materialCollections) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots,
                ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, materialCollections, 0, List.of(), false);
    }
    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction, MaterialInstanceParameters instanceParameters) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots,
                ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, instanceParameters, List.of());
    }
    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial, boolean lightFunction) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots,
                ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial,
                lightFunction, MaterialInstanceParameters.NONE);
    }
    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView,
                              @Nullable VertexMaterialSettings vertexMaterial) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots,
                ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity,
                velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, vertexMaterial, false);
    }
    public enum Blend {
        ADD, ADDITIVE, SOFT_ADDITIVE, ALPHA, PREMULTIPLIED, OPAQUE, HOLDOUT, MODULATE, COLORED_TRANSMITTANCE;

        public boolean translucent() {
            return switch (this) {
                case OPAQUE -> false;
                case ADD, ADDITIVE, SOFT_ADDITIVE, ALPHA, PREMULTIPLIED, HOLDOUT, MODULATE, COLORED_TRANSMITTANCE -> true;
            };
        }
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor, int materialView) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject,
                writeTranslucentVelocity, velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, materialView, null);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals, boolean sceneColor) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject,
                writeTranslucentVelocity, velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor, sceneColor ? 2 : 0);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings,
                              int dbufferTextures, boolean receivesDecals) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject,
                writeTranslucentVelocity, velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                              boolean writeTranslucentVelocity, boolean velocityFromDepth, @Nullable DecalMaterialSettings decalSettings) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject,
                writeTranslucentVelocity, velocityFromDepth, decalSettings, 0, true);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                                 MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                                 MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                                 boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject,
                                 boolean writeTranslucentVelocity, boolean velocityFromDepth) {
        this(fragmentShader, textures, blend, depth, cull, parameters,
                renderSettings, fog, translucencyPass, dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, writeTranslucentVelocity, velocityFromDepth, null);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV, String sourceObject) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject, false, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth, boolean macroUV) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, "");
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData,
                              boolean depthProjection, boolean sceneDepth) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots, boolean ribbonParticleData) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass,
                dynamicSlots, ribbonParticleData, false, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass, int dynamicSlots) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, dynamicSlots, false);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              MaterialDepth depth, boolean cull, float[] parameters, RenderSettings renderSettings,
                              MaterialFog fog, TranslucencyPass translucencyPass) {
        this(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog, translucencyPass, 0);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              boolean depthTest, boolean cull, float[] parameters) {
        this(fragmentShader, textures, blend, MaterialDepth.legacy(depthTest, blend == Blend.OPAQUE), cull, parameters,
                RenderSettings.STANDARD, MaterialFog.NONE, TranslucencyPass.AFTER_DOF);
    }

    public MaterialDefinition(Identifier fragmentShader, Map<String, Identifier> textures, Blend blend,
                              boolean depthTest, boolean cull, float[] parameters, RenderSettings renderSettings) {
        this(fragmentShader, textures, blend, MaterialDepth.legacy(depthTest, blend == Blend.OPAQUE), cull, parameters,
                renderSettings, MaterialFog.NONE, TranslucencyPass.AFTER_DOF);
    }

    public MaterialDefinition {
        if (automaticSpriteCoverageChannels != 0 && automaticSpriteCoverageChannels != 8 && automaticSpriteCoverageChannels != 15)
            throw new IllegalArgumentException("Invalid automatic sprite coverage for material");
        if (blend == Blend.MODULATE && translucencyPass == TranslucencyPass.AFTER_MOTION_BLUR)
            throw new IllegalArgumentException("Modulate requires a translucency pass before motion blur");
        java.util.Objects.requireNonNull(instanceParameters);
        java.util.Objects.requireNonNull(pointShadowCoverage);
        java.util.Objects.requireNonNull(blendOp);
        materialCollections = List.copyOf(materialCollections);
        virtualTextureReads = List.copyOf(virtualTextureReads);
        var textureReadNames = new HashSet<String>();
        for (var read : virtualTextureReads)
            if (!textureReadNames.add(read.name())) throw new IllegalArgumentException("Duplicate virtual texture stack " + read.name());
        if (virtualTextureFeedback && virtualTextureReads.isEmpty()) throw new IllegalArgumentException("Material feedback requires virtual texture stacks");
        if (materialCollections.size() > 2) throw new IllegalArgumentException("A material may reference at most two collections");
        var collectionUniforms = new HashSet<String>();
        for (MaterialCollectionBinding binding : materialCollections) {
            if (!collectionUniforms.add(binding.uniform())) throw new IllegalArgumentException("Duplicate material collection uniform: " + binding.uniform());
            if ((binding.stages() & 1) != 0 && vertexMaterial == null) throw new IllegalArgumentException("Vertex MPC has no vertex material");
        }
        for (var binding : instanceParameters.textureLayout())
            if (!textures.containsKey(binding.sampler())) throw new IllegalArgumentException("Material texture binding has no shader sampler: " + binding.sampler());
        if (!textures.keySet().containsAll(instanceParameters.fontSamplers().keySet()))
            throw new IllegalArgumentException("Material font binding has no shader sampler");
        textureSamplerSources = Map.copyOf(textureSamplerSources);
        if (!textures.keySet().containsAll(textureSamplerSources.keySet()))
            throw new IllegalArgumentException("Shared material sampler has no texture binding");
        textureSamplers = Map.copyOf(textureSamplers);
        if (!textures.keySet().containsAll(textureSamplers.keySet()))
            throw new IllegalArgumentException("Material texture sampler has no texture binding");
        textures = Map.copyOf(textures);
        parameters = parameters.clone();
        if (fragmentShader == null || blend == null || depth == null || renderSettings == null || fog == null || translucencyPass == null || sourceObject == null || parameters.length != 4 || textures.size() > 16)
            throw new IllegalArgumentException("Invalid VFX material");
        sourceObject = sourceObject.replace(':', '.');
        if ((dynamicSlots & ~15) != 0) throw new IllegalArgumentException("Invalid Niagara dynamic material slots");
        if ((runtimeVirtualTextureOutputs & ~255) != 0 || runtimeVirtualTextureOutputs != 0 && (lightFunction || decalSettings != null))
            throw new IllegalArgumentException("Invalid runtime virtual texture material output");
        if (sceneDepth && !depthProjection) throw new IllegalArgumentException("Scene depth requires depth projection parameters");
        if ((customSceneTextures & ~3) != 0 || (customSceneTextures & 1) != 0 && !depthProjection)
            throw new IllegalArgumentException("Invalid custom scene texture inputs");
        if ((deferredSceneTextures & ~DEFERRED_SCENE_TEXTURES) != 0
                || deferredSceneTextures != 0 && (!blend.translucent() || decalSettings != null || lightFunction))
            throw new IllegalArgumentException("Deferred scene textures require a translucent surface");
        if (sceneColor && (blend == Blend.OPAQUE || decalSettings != null || textures.containsKey("LfxSceneColor")))
            throw new IllegalArgumentException("Scene color requires a translucent surface and an unoccupied scene sampler");
        if ((materialView & ~MATERIAL_VIEW_INPUTS) != 0 || (materialView & 16) != 0 && (materialView & 8) == 0
                || sceneColor && (materialView & 2) == 0)
            throw new IllegalArgumentException("Invalid material view inputs or missing scene color exposure parameters");
        if ((dbufferTextures & ~7) != 0 || dbufferTextures != 0 && (blend != Blend.OPAQUE || decalSettings != null))
            throw new IllegalArgumentException("DBuffer lookup requires an opaque or masked surface and a valid texture mask");
        if (vertexMaterial != null && decalSettings != null)
            throw new IllegalArgumentException("Deferred decals do not use a surface vertex material");
        if (lightFunction && (vertexMaterial != null || decalSettings != null || blend != Blend.OPAQUE || sceneColor || dbufferTextures != 0))
            throw new IllegalArgumentException("Light functions require their own pixel material domain");
        if (defaultLitSurface && (blend != Blend.OPAQUE || decalSettings != null || lightFunction || runtimeVirtualTextureOutputs != 0))
            throw new IllegalArgumentException("Default Lit output requires an opaque or masked surface material");
        if (anisotropicSurface && !defaultLitSurface)
            throw new IllegalArgumentException("Anisotropic surface output requires a Default Lit material");
        if (customSurfaceData && !defaultLitSurface)
            throw new IllegalArgumentException("Custom material data requires a lit surface output");
        if ((particleLightingFeatures & ~PARTICLE_LIGHTING_FEATURES) != 0
                || (particleLightingFeatures & (SUBSTRATE_FUZZ | SUBSTRATE_WRAP | SUBSTRATE_SPECULAR_PROFILE
                        | SUBSTRATE_VOLUME | SUBSTRATE_DIFFUSION)) != 0
                        && (particleLightingFeatures & SUBSTRATE_SLAB) == 0
                || particleLightingFeatures != 0 && !customSurfaceData)
            throw new IllegalArgumentException("Specialized particle lighting requires compatible material data");
        slabSubsurfaceProfiles = slabSubsurfaceProfiles.stream().map(path -> path.replace(':', '.')).toList();
        for (String path : slabSubsurfaceProfiles)
            if (!path.startsWith("/") || !path.contains(".") || path.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid Unreal Slab subsurface profile object path: " + path);
        boolean legacyProfile = (particleLightingFeatures & LEGACY_SUBSURFACE_PROFILE) != 0;
        if (subsurfaceProfile != null && !legacyProfile
                || legacyProfile && subsurfaceProfile == null && slabSubsurfaceProfiles.isEmpty()
                || !slabSubsurfaceProfiles.isEmpty() && (particleLightingFeatures & SUBSTRATE_SLAB) == 0)
            throw new IllegalArgumentException("Subsurface profile identity and material lighting must be declared together");
        if (subsurfaceProfile != null && !subsurfaceProfile.isEmpty()) {
            subsurfaceProfile = subsurfaceProfile.replace(':', '.');
            if (!subsurfaceProfile.startsWith("/") || !subsurfaceProfile.contains("."))
                throw new IllegalArgumentException("Invalid Unreal subsurface profile object path: " + subsurfaceProfile);
        }
        specularProfiles = specularProfiles.stream().map(path -> path.replace(':', '.')).toList();
        for (String path : specularProfiles)
            if (!path.startsWith("/") || !path.contains(".") || path.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid Unreal specular profile object path: " + path);
        if ((particleLightingFeatures & SUBSTRATE_SPECULAR_PROFILE) != 0 && specularProfiles.isEmpty()
                || !specularProfiles.isEmpty() && (textures.containsKey("LfxSpecularProfiles") || lightFunction || runtimeVirtualTextureOutputs != 0))
            throw new IllegalArgumentException("Invalid specular profile material bindings");
        toonProfiles = toonProfiles.stream().map(path -> path.replace(':', '.')).toList();
        for (String path : toonProfiles)
            if (!path.equals("None") && (!path.startsWith("/") || !path.contains(".") || path.indexOf('\0') >= 0))
                throw new IllegalArgumentException("Invalid Unreal Toon profile object path: " + path);
        boolean toon = (particleLightingFeatures & SUBSTRATE_TOON) != 0;
        if (toon != !toonProfiles.isEmpty() || toon && (lightFunction || decalSettings != null || runtimeVirtualTextureOutputs != 0))
            throw new IllegalArgumentException("Invalid Toon profile material bindings");
        if (adaptiveSubstrateWords < 0 || adaptiveSubstrateWords > MAX_ADAPTIVE_SUBSTRATE_WORDS
                || adaptiveSubstrateWords != 0 && (particleLightingFeatures & SUBSTRATE_SLAB) == 0
                || (particleLightingFeatures & SUBSTRATE_DIFFUSION) != 0 && adaptiveSubstrateWords < 5)
            throw new IllegalArgumentException("Invalid adaptive Substrate material word count");
        for (var texture : DBufferTexture.ALL) if ((dbufferTextures & texture.mask()) != 0 && textures.containsKey(texture.sampler()))
            throw new IllegalArgumentException("Material texture uses a reserved DBuffer sampler: " + texture.sampler());
        for (float value : parameters) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite material parameter");
    }

    @Override public float[] parameters() { return parameters.clone(); }
    public void writeParameters(java.nio.ByteBuffer destination) { for (float value : parameters) destination.putFloat(value); }
    public MaterialDefinition instantiateParameters() {
        if (!instanceParameters.hasBindings()) return this;
        return instantiateParameters(instanceParameters.dynamicValues());
    }
    public MaterialDefinition instantiateParameters(@Nullable DynamicMaterialValues values) {
        return new MaterialDefinition(fragmentShader, textures, blend, depth, cull, parameters, renderSettings, fog,
                translucencyPass, dynamicSlots, ribbonParticleData, depthProjection, sceneDepth, macroUV, sourceObject,
                writeTranslucentVelocity, velocityFromDepth, decalSettings, dbufferTextures, receivesDecals, sceneColor,
                materialView, vertexMaterial, lightFunction, instanceParameters.instantiate(programDefinition(), values), materialCollections,
                runtimeVirtualTextureOutputs, virtualTextureReads, virtualTextureFeedback, defaultLitSurface, pointShadowCoverage,
                translucentCustomDepth, customSceneTextures, deferredSceneTextures, anisotropicSurface, customSurfaceData,
                particleLightingFeatures, subsurfaceProfile, slabSubsurfaceProfiles, specularProfiles, adaptiveSubstrateWords,
                lightFunctionAtlasCompatible, translucencyVolume, naniteSource, highQualityForwardReflections, textureSamplerSources,
                textureSamplers, blendOp, sgUv4Mask, toonProfiles, automaticSpriteCoverageChannels);
    }
    public MaterialDefinition programDefinition() {
        MaterialDefinition program = instanceParameters.programDefinition();
        return program == null ? this : program;
    }
    public boolean depthTest() { return depth.tests(); }
    public boolean writesVelocity() {
        if (lightFunction || depth.compare() == MaterialDepth.Compare.NEVER) return false;
        return blend == Blend.OPAQUE
                ? depth.write() && depth.compare() != MaterialDepth.Compare.DISABLED
                : writeTranslucentVelocity;
    }
    public boolean writesCustomDepth() {
        return !lightFunction && decalSettings == null && runtimeVirtualTextureOutputs == 0
                && (blend == Blend.OPAQUE || translucentCustomDepth);
    }
    public boolean usesVertexBasis() { return vertexMaterial != null && vertexMaterial.vertexBasis(); }
}

