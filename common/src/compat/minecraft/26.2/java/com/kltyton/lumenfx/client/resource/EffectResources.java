package com.kltyton.lumenfx.client.resource;

import com.kltyton.lumenfx.effect.asset.VectorFieldLoader;
import com.kltyton.lumenfx.client.resource.niagara.splat.NiagaraGaussianSplatLoader;
import com.kltyton.lumenfx.effect.niagara.interfaces.splat.NiagaraGaussianSplatAsset;

import com.lowdragmc.photon.client.fx.FXHelper;

import com.kltyton.lumenfx.client.resource.niagara.cache.NiagaraSimCacheLoader;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import com.kltyton.lumenfx.client.resource.volume.SparseVolumeTextureLoader;
import com.kltyton.lumenfx.client.resource.niagara.volume.NiagaraVolumeCacheLoader;
import com.kltyton.lumenfx.client.resource.niagara.groom.NiagaraGroomAssetLoader;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomAsset;
import com.kltyton.lumenfx.effect.niagara.interfaces.volume.NiagaraVolumeCacheAsset;
import java.io.UncheckedIOException;

import com.kltyton.lumenfx.client.resource.mesh.SkeletalMeshAssetLoader;
import com.kltyton.lumenfx.client.resource.mesh.SkeletalMeshStreaming;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshSorting;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteSorting;
import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextureLoader;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.effect.asset.unity.UnityShapeTextureLoader;
import com.kltyton.lumenfx.effect.unity.UnityShapeTexture;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.client.resource.texture.energy.ShadingEnergyTable;
import com.kltyton.lumenfx.client.resource.texture.energy.SimpleVolumeLookup;
import com.kltyton.lumenfx.client.resource.texture.lighting.PerlinNoiseTextures;
import com.kltyton.lumenfx.client.resource.texture.lighting.BlueNoiseTextures;
import com.kltyton.lumenfx.client.resource.texture.splat.GaussianSplatLutTextures;
import com.kltyton.lumenfx.client.resource.texture.subsurface.SubsurfaceProfiles;
import com.kltyton.lumenfx.client.resource.texture.specular.SpecularProfiles;
import com.kltyton.lumenfx.client.resource.texture.toon.ToonProfiles;
import com.kltyton.lumenfx.client.resource.light.DirectionalShadowResources;
import com.kltyton.lumenfx.client.resource.light.VolumetricLightmapResources;
import com.kltyton.lumenfx.client.resource.light.SceneIndirectLightingJson;
import com.kltyton.lumenfx.client.resource.light.SkyLightReflectionResources;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTexture;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.client.resource.shader.PreparedFogIntegration;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.client.resource.shader.NiagaraShaderResources;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraDrawShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraSortShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraIdShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraMeshSceneShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraNaniteShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraGaussianSplatShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNaniteVertexMaterial;
import com.kltyton.lumenfx.client.resource.shader.PreparedNaniteMaterials;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraStatelessShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedTextureMipShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedShadingEnergyShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial;
import com.kltyton.lumenfx.client.render.texture.target.TextureMipPrograms;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTextureResources;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTextureTileSource;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePlacement;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Settings;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import com.kltyton.lumenfx.client.resource.shader.PreparedRuntimeVirtualTextureShaders;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.kltyton.lumenfx.client.simulation.niagara.curve.NativeCurveSampler;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.effect.asset.mesh.StaticMeshAssetLoader;
import com.kltyton.lumenfx.effect.asset.mesh.StaticMeshStreaming;
import com.kltyton.lumenfx.effect.asset.mesh.StaticMeshGeometryLoader;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;

public final class EffectResources extends SimplePreparableReloadListener<EffectResources.Prepared> {
    private static final FileToIdConverter EFFECT_FILES = FileToIdConverter.json("lumenfx/effects");

    public static @Nullable JsonObject jsonObject(@Nullable JsonObject parent, String key) {
        return com.kltyton.lumenfx.effect.asset.EffectJsonFields.jsonObject(parent, key);
    }

    public static @Nullable JsonArray jsonArray(@Nullable JsonObject parent, String key) {
        return com.kltyton.lumenfx.effect.asset.EffectJsonFields.jsonArray(parent, key);
    }

    public static @Nullable String jsonString(@Nullable JsonObject parent, String key) {
        return com.kltyton.lumenfx.effect.asset.EffectJsonFields.jsonString(parent, key);
    }

    protected record Prepared(Map<Identifier, EffectDefinition> definitions, Map<Identifier, Resource> photonEffects,
                            Map<Identifier, PreparedTexture> textures, Map<Identifier, ShadingEnergyTable> shadingEnergy,
                            PreparedFogIntegration fogIntegration,
                            Map<Identifier, PreparedNiagaraShaders> niagaraShaders, PreparedNiagaraDrawShaders niagaraDrawShaders,
                            PreparedNiagaraSortShaders niagaraSortShaders, PreparedNiagaraIdShaders niagaraIdShaders,
                            PreparedNiagaraMeshSceneShaders meshSceneShaders, PreparedNiagaraStatelessShaders statelessShaders,
                            PreparedNiagaraNaniteShaders naniteShaders,
                            @Nullable PreparedNiagaraGaussianSplatShaders gaussianSplatShaders,
                            PreparedNaniteMaterials naniteMaterials,
                            Map<Identifier, RenderTargetTextureDefinition> textureTargets,
                            Map<Identifier, EffectTextureLoader.PreparedArrayTarget> arrayTargets,
                            Map<Identifier, EffectTextureLoader.PreparedCubeTarget> cubeTargets,
                            Map<Identifier, EffectTextureLoader.PreparedVolumeTarget> volumeTargets, PreparedTextureMipShaders textureMips,
                            Map<Identifier, PreparedVolumeMaterial> volumeMaterials,
                            VirtualTextureResources virtualTextures, @Nullable PreparedShadingEnergyShaders shadingEnergyShaders,
                            SubsurfaceProfiles subsurfaceProfiles, SpecularProfiles specularProfiles, ToonProfiles toonProfiles,
                            StaticMeshStreaming meshStreaming, SkeletalMeshStreaming skeletalMeshStreaming) { }

    @Override
    protected Prepared prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<Identifier, EffectDefinition> result = new HashMap<>();
        Map<Identifier, DirectionalShadowResources.Asset> directionalShadows = new HashMap<>();
        Map<Identifier, VolumetricLightmapResources.Asset> volumetricLightmaps = new HashMap<>();
        Map<Identifier, SkyLightReflectionResources.Asset> skyReflections = new HashMap<>();
        Map<Identifier, PreparedTexture> reflectionCaptureArrays = new HashMap<>();
        Map<Identifier, VectorFieldVolume> fields = new HashMap<>();
        Map<Identifier, StaticMeshAsset> meshes = new HashMap<>();
        Map<Identifier, UnityShapeTexture.Pixels> shapeTextures = new HashMap<>();
        Map<Identifier, SkeletalMeshAsset> skeletal = new HashMap<>();
        Map<Identifier, NiagaraSimulationCache> caches = new HashMap<>();
        Map<Identifier, SparseVolumeTextureAsset> sparseVolumes = new HashMap<>();
        Map<Identifier, NiagaraVolumeCacheAsset> volumeCaches = new HashMap<>();
        Map<Identifier, NiagaraGroomAsset> grooms = new HashMap<>();
        Map<Identifier, NiagaraGaussianSplatAsset> gaussianSplats = new HashMap<>();
        Map<Identifier, PreparedVolumeMaterial> volumeMaterials = new HashMap<>();
        Map<Identifier, PreparedNaniteVertexMaterial> naniteVertexMaterials = new HashMap<>();
        Map<Identifier, com.kltyton.lumenfx.client.render.compute.ShaderProgram> naniteRasterMaterials = new HashMap<>();
        Map<Identifier, StaticMeshGeometry> meshGeometry = new HashMap<>();
        var meshStreaming = new StaticMeshStreaming();
        var skeletalMeshStreaming = new SkeletalMeshStreaming();
        Map<Identifier, VirtualTextureTileSource> sharedVirtualTextures = new HashMap<>();
        Map<Identifier, Map<String, VirtualTextureTileSource>> virtualTextures = new HashMap<>();
        Map<Identifier, Map<String, RuntimeVirtualTextureDefinition>> runtimeTextures = new HashMap<>();
        Map<Identifier, Map<String, RuntimeVirtualTexturePlacement>> runtimePlacements = new HashMap<>();
        Map<Identifier, Map<String, Settings>> runtimePrimitives = new HashMap<>();
        for (var entry : EFFECT_FILES.listMatchingResources(resources).entrySet()) {
            Identifier file = entry.getKey();
            try (var reader = entry.getValue().openAsReader()) {
                Identifier effect = EFFECT_FILES.fileToId(file);
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                DirectionalShadowResources.prepare(resources, json, directionalShadows);
                SceneIndirectLightingJson.prepare(resources, json, volumetricLightmaps, skyReflections, reflectionCaptureArrays);
                var virtualAssets = VirtualTextureResources.prepare(resources, json, sharedVirtualTextures);
                if (!virtualAssets.isEmpty()) virtualTextures.put(effect, virtualAssets);
                var runtimeAssets = VirtualTextureResources.prepareRuntime(json);
                if (!runtimeAssets.isEmpty()) runtimeTextures.put(effect, runtimeAssets);
                var placements = VirtualTextureResources.preparePlacements(json);
                if (!placements.isEmpty()) runtimePlacements.put(effect, placements);
                var primitives = VirtualTextureResources.preparePrimitives(json);
                if (!primitives.isEmpty()) runtimePrimitives.put(effect, primitives);
                EffectDefinition definition = EffectJson.read(json,
                        id -> fields.computeIfAbsent(id, key -> VectorFieldLoader.load(resources, key)),
                        id -> meshes.computeIfAbsent(id, key -> StaticMeshAssetLoader.load(resources, key,
                                geometry -> meshGeometry.computeIfAbsent(geometry, value -> StaticMeshGeometryLoader.load(resources, value)),
                                meshStreaming)),
                        id -> skeletal.computeIfAbsent(id, key -> SkeletalMeshAssetLoader.load(resources, key, skeletalMeshStreaming)),
                        id -> caches.computeIfAbsent(id, key -> {
                            try { return NiagaraSimCacheLoader.load(resources, key); }
                            catch (IOException failure) { throw new UncheckedIOException(failure); }
                        }), id -> sparseVolumes.computeIfAbsent(id, key -> SparseVolumeTextureLoader.load(resources, key)),
                        id -> volumeCaches.computeIfAbsent(id, key -> NiagaraVolumeCacheLoader.load(resources, key)),
                        id -> grooms.computeIfAbsent(id, key -> NiagaraGroomAssetLoader.load(resources, key)),
                        id -> shapeTextures.computeIfAbsent(id, key -> UnityShapeTextureLoader.load(resources, key)),
                        id -> gaussianSplats.computeIfAbsent(id, key -> NiagaraGaussianSplatLoader.load(resources, key)));
                if (definition.niagara() != null) NativeCurveSampler.prepare(definition.niagara());
                for (var material : definition.materials().values()) {
                    for (Identifier texture : material.textures().values()) {
                        if (!PerlinNoiseTextures.contains(texture) && resources.getResource(texture).isEmpty())
                            throw new IllegalArgumentException("Missing effect texture " + texture);
                    }
                    Identifier shader = Identifier.fromNamespaceAndPath(material.fragmentShader().getNamespace(),
                            "shaders/" + material.fragmentShader().getPath() + ".fsh");
                    var materialId = material.fragmentShader();
                    Identifier volumeShader = Identifier.fromNamespaceAndPath(materialId.getNamespace(),
                            "shaders/" + materialId.getPath() + ".volume.json");
                    if (resources.getResource(volumeShader).isPresent()) {
                        if (!volumeMaterials.containsKey(materialId))
                            volumeMaterials.put(materialId, PreparedVolumeMaterial.prepare(
                                    resources, materialId, key -> sparseVolumes.computeIfAbsent(key,
                                            id -> SparseVolumeTextureLoader.load(resources, id))));
                    } else if (resources.getResource(shader).isEmpty())
                        throw new IllegalArgumentException("Missing effect fragment shader " + shader);
                    if (material.vertexMaterial() != null) {
                        Identifier vertex = material.vertexMaterial().shader();
                        Identifier source = Identifier.fromNamespaceAndPath(vertex.getNamespace(), "shaders/" + vertex.getPath() + ".vsh");
                        if (resources.getResource(source).isEmpty())
                            throw new IllegalArgumentException("Missing effect vertex material " + source);
                        var nanite = material.vertexMaterial().naniteShader();
                        if (nanite != null) {
                            var prepared = naniteVertexMaterials.get(nanite);
                            if (prepared == null) {
                                prepared = PreparedNaniteVertexMaterial.prepare(resources, nanite);
                                naniteVertexMaterials.put(nanite, prepared);
                            }
                            if (!prepared.interpolatorWidths().equals(material.vertexMaterial().naniteInterpolatorWidths()))
                                throw new IllegalArgumentException("Nanite material interpolation layout differs from its shader: " + nanite);
                        }
                    }
                    var raster = PreparedNaniteMaterials.prepareRaster(resources, material.fragmentShader());
                    if (raster != null) naniteRasterMaterials.putIfAbsent(material.fragmentShader(), raster);
                }
                result.put(effect, definition);
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Cannot load VFX resource " + file, error);
            }
        }
        if (!volumeMaterials.isEmpty()) {
            try {
                volumeMaterials.put(PreparedVolumeMaterial.PRESHADING_PROGRAM, PreparedVolumeMaterial.prepare(resources,
                        PreparedVolumeMaterial.PRESHADING_PROGRAM,
                        id -> sparseVolumes.computeIfAbsent(id, key -> SparseVolumeTextureLoader.load(resources, key))));
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot prepare the canonical Niagara volume shader", failure);
            }
        }

        Set<Identifier> textureIds = new LinkedHashSet<>();
        if (result.values().stream().flatMap(effect -> effect.materials().values().stream())
                .anyMatch(material -> (material.particleLightingFeatures() & MaterialDefinition.PREINTEGRATED_SKIN) != 0))
            textureIds.add(ShadingEnergyTable.PREINTEGRATED_SKIN);
        Set<Identifier> interfaceTextures = new LinkedHashSet<>();
        Set<Identifier> fontTextures = new LinkedHashSet<>();
        Set<String> profilePaths = new LinkedHashSet<>();
        Set<String> toonProfilePaths = new LinkedHashSet<>();
        Set<String> specularProfilePaths = new LinkedHashSet<>();
        for (EffectDefinition definition : result.values()) {
            for (var pages : definition.materialFonts().values()) fontTextures.addAll(pages.values());
            for (var material : definition.materials().values()) {
                textureIds.addAll(material.textures().values());
                fontTextures.addAll(material.instanceParameters().parentFontPages().values());
                if (material.subsurfaceProfile() != null) profilePaths.add(material.subsurfaceProfile());
                profilePaths.addAll(material.slabSubsurfaceProfiles());
                if ((material.particleLightingFeatures() & MaterialDefinition.SUBSTRATE_DIFFUSION) != 0) profilePaths.add("");
                specularProfilePaths.addAll(material.specularProfiles());
                toonProfilePaths.addAll(material.toonProfiles());
            }
        }
        for (EffectDefinition definition : result.values())
            interfaceTextures.addAll(com.kltyton.lumenfx.client.resource.niagara.texture.NiagaraTextureResources.collect(definition));
        textureIds.addAll(interfaceTextures);
        textureIds.addAll(fontTextures);
        Map<Identifier, PreparedTexture> textures = new LinkedHashMap<>(reflectionCaptureArrays);
        skyReflections.values().forEach(value -> {
            textures.put(value.cube().id(), value.cube());
            var gf = value.preintegrated();
            if (gf != null) textures.put(gf.id(), gf);
        });
        directionalShadows.forEach((id, value) -> textures.put(id, value.texture()));
        volumetricLightmaps.values().forEach(value -> value.textures().forEach(texture -> textures.put(texture.id(), texture)));
        Map<Identifier, RenderTargetTextureDefinition> targets = new LinkedHashMap<>();
        Map<Identifier, EffectTextureLoader.PreparedArrayTarget> arrayTargets = new LinkedHashMap<>();
        Map<Identifier, EffectTextureLoader.PreparedCubeTarget> cubeTargets = new LinkedHashMap<>();
        Map<Identifier, EffectTextureLoader.PreparedVolumeTarget> volumeTargets = new LinkedHashMap<>();
        for (Identifier texture : textureIds) {
            var noiseLookup = PerlinNoiseTextures.prepare(texture);
            if (noiseLookup != null) {
                textures.put(texture, noiseLookup);
                continue;
            }
            if (texture.equals(SimpleVolumeLookup.PUNCTUAL)) {
                var lookup = SimpleVolumeLookup.prepare(texture, resources, 72);
                if (lookup == null) throw new IllegalArgumentException("Substrate vertical layering requires the native punctual volume lookup: " + texture);
                textures.put(texture, lookup);
                continue;
            }
            if (texture.getPath().endsWith(".lfart.json")) {
                arrayTargets.put(texture, EffectTextureLoader.prepareArrayTarget(texture, resources));
                continue;
            }
            if (texture.getPath().endsWith(".lfcrt.json")) {
                cubeTargets.put(texture, EffectTextureLoader.prepareCubeTarget(texture, resources));
                continue;
            }
            if (texture.getPath().endsWith(".lfvrt.json")) {
                volumeTargets.put(texture, EffectTextureLoader.prepareVolumeTarget(texture, resources));
                continue;
            }
            if (texture.getPath().endsWith(".lfrt.json")) {
                targets.put(texture, RenderTargetTextureDefinition.read(resources, texture));
                continue;
            }
            PreparedTexture prepared = EffectTextureLoader.prepare(texture, resources);
            if (prepared == null && texture.equals(ShadingEnergyTable.PREINTEGRATED_SKIN))
                throw new IllegalArgumentException("Preintegrated Skin requires its native BRDF texture: " + texture);
            if (prepared == null && fontTextures.contains(texture))
                throw new IllegalArgumentException("Offline material font requires its source mip metadata: " + texture);
            if (prepared == null && interfaceTextures.contains(texture))
                throw new IllegalArgumentException("Niagara texture requires its source mip metadata: " + texture);
            if (prepared != null) textures.put(texture, prepared);
        }
        BlueNoiseTextures.prepare(resources, textures);
        GaussianSplatLutTextures.prepare(resources, textures);
        Map<Identifier, ShadingEnergyTable> shadingEnergy = new LinkedHashMap<>();
        for (var entry : resources.listResources(ShadingEnergyTable.DIRECTORY, id -> id.getPath().endsWith(".lfea")).entrySet()) {
            try { shadingEnergy.put(entry.getKey(), ShadingEnergyTable.prepare(entry.getKey(), entry.getValue())); }
            catch (IOException failure) { throw new UncheckedIOException("Cannot read shading energy table " + entry.getKey(), failure); }
        }
        var profiles = result.values().stream().flatMap(effect -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(effect.renderSettings()),
                effect.materials().values().stream().map(com.kltyton.lumenfx.effect.MaterialDefinition::renderSettings))).toList();
        boolean needsVolume = profiles.stream().anyMatch(profile -> profile.fog() instanceof VolumetricFog);
        Map<Identifier, PreparedNiagaraShaders> niagaraShaders = new LinkedHashMap<>();
        boolean cpuRibbon = false, needsGpuRibbon = false, needsStateless = false, needsGpuStateless = false, needsNeighborQuery = false;
        for (EffectDefinition definition : result.values()) {
            if (definition.niagara() == null) continue;
            for (var emitter : definition.niagara().emitters()) {
                var simulation = emitter.simulation();
                boolean gpu = simulation instanceof NiagaraDefinition.GpuSimulation;
                if (simulation instanceof NiagaraDefinition.StatelessSimulation stateless && stateless.target().equals("GPUComputeSim")) {
                    gpu = true;
                    needsGpuStateless = true;
                    needsStateless |= (stateless.metadata().get("allowed_features").getAsInt() & 1) != 0;
                }
                for (var renderer : emitter.renderers()) {
                    if (!definition.emitters().stream().anyMatch(value -> value.name().equals(renderer.name())
                            && value.renderer() == com.kltyton.lumenfx.effect.EmitterDefinition.Renderer.RIBBON)) continue;
                    if (gpu) needsGpuRibbon = true;
                    else if (!(simulation instanceof NiagaraDefinition.DisabledSimulation)) cpuRibbon = true;
                }
                if (simulation instanceof NiagaraDefinition.GpuSimulation scripted) {
                    for (var item : scripted.metadata().getAsJsonArray("interfaces"))
                        if (item.getAsJsonObject().has("neighbor_query")) needsNeighborQuery = true;
                    Identifier key = Identifier.fromNamespaceAndPath(scripted.hlslSource().getNamespace(), scripted.shaderPipeline());
                    niagaraShaders.computeIfAbsent(key,
                            id -> NiagaraShaderResources.prepare(resources, id.getNamespace(), id.getPath(), scripted.metadata(), emitter.attributes(),
                                    field -> fields.computeIfAbsent(field, value -> VectorFieldLoader.load(resources, value))));
                }
            }
        }
        boolean needsMeshScene = result.values().stream().flatMap(effect -> effect.emitters().stream())
                .anyMatch(emitter -> emitter.meshSettings() != null && emitter.meshSettings().sceneTransforms()
                        != com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings.SceneTransforms.LEGACY);
        boolean needsNanite = result.values().stream().flatMap(effect -> effect.emitters().stream())
                .anyMatch(emitter -> emitter.meshSettings() != null && emitter.meshSettings().nanite() != null);
        boolean needsSplat = PreparedNiagaraGaussianSplatShaders.isRequired(result.values());
        boolean loadGpuRibbons = needsGpuRibbon || cpuRibbon && PreparedNiagaraSortShaders.hasRibbonPipelineMetadata(resources);
        boolean loadCpuSort = PreparedNiagaraSortShaders.hasSortPipelines(resources)
                && (NiagaraGpuSpriteSorting.hasCpuSpriteWork(result.values()) || NiagaraGpuMeshSorting.hasCpuMeshWork(result.values()));
        boolean needsGpuUtilities = !niagaraShaders.isEmpty() || needsMeshScene || needsGpuStateless
                || needsNanite || needsSplat || loadGpuRibbons || loadCpuSort;
        return new Prepared(Map.copyOf(result), FXHelper.index(resources), Map.copyOf(textures), Map.copyOf(shadingEnergy),
                needsVolume ? PreparedFogIntegration.prepare(resources) : null, Map.copyOf(niagaraShaders),
                needsGpuUtilities ? PreparedNiagaraDrawShaders.prepare(resources) : null,
                needsGpuUtilities ? PreparedNiagaraSortShaders.prepare(resources, loadGpuRibbons, needsNeighborQuery) : null,
                needsGpuUtilities ? PreparedNiagaraIdShaders.prepare(resources) : null,
                needsMeshScene ? PreparedNiagaraMeshSceneShaders.prepare(resources) : null,
                needsStateless ? PreparedNiagaraStatelessShaders.prepare(resources) : null,
                needsNanite ? PreparedNiagaraNaniteShaders.prepare(resources) : null,
                needsSplat ? PreparedNiagaraGaussianSplatShaders.prepare(resources) : null,
                new PreparedNaniteMaterials(Map.copyOf(naniteVertexMaterials), Map.copyOf(naniteRasterMaterials)),
                Map.copyOf(targets), Map.copyOf(arrayTargets), Map.copyOf(cubeTargets), Map.copyOf(volumeTargets),
                PreparedTextureMipShaders.prepare(resources), Map.copyOf(volumeMaterials),
                new VirtualTextureResources(virtualTextures, runtimeTextures, runtimePlacements, runtimePrimitives,
                        PreparedRuntimeVirtualTextureShaders.prepare(resources, runtimeTextures.values().stream()
                                .flatMap(values -> values.values().stream())
                                .anyMatch(value -> value.compress() && value.material() != Material.WorldHeight))),
                PreparedShadingEnergyShaders.prepare(resources), SubsurfaceProfiles.prepare(resources, profilePaths),
                SpecularProfiles.prepare(resources, specularProfilePaths), ToonProfiles.prepare(resources, toonProfilePaths), meshStreaming, skeletalMeshStreaming);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resources, ProfilerFiller profiler) {
        RenderSystem.assertOnRenderThread();
        VfxEngine.PreparedReload candidate = VfxEngine.INSTANCE.prepareReload(prepared.definitions(),
                prepared.fogIntegration(), prepared.niagaraShaders(),
                prepared.niagaraDrawShaders(), prepared.niagaraSortShaders(), prepared.niagaraIdShaders(), prepared.meshSceneShaders(), prepared.statelessShaders(), prepared.naniteShaders(), prepared.gaussianSplatShaders(), prepared.naniteMaterials());
        Map<Identifier, EffectTexture> uploaded = new LinkedHashMap<>();
        Map<Identifier, RenderTargetTexture> targets = new LinkedHashMap<>();
        TextureMipPrograms mipPrograms = new TextureMipPrograms(prepared.textureMips());
        boolean published = false;
        try {
            for (var entry : prepared.textures().entrySet())
                uploaded.put(entry.getKey(), EffectTextureLoader.upload(entry.getValue()));
            for (var entry : prepared.shadingEnergy().entrySet())
                uploaded.put(entry.getKey(), entry.getValue().upload());
            EffectTexture subsurfaceAtlas = prepared.subsurfaceProfiles().upload();
            if (subsurfaceAtlas != null) uploaded.put(SubsurfaceProfiles.ATLAS, subsurfaceAtlas);
            EffectTexture specularAtlas = prepared.specularProfiles().upload();
            if (specularAtlas != null) uploaded.put(SpecularProfiles.ATLAS, specularAtlas);
            EffectTexture toonAtlas = prepared.toonProfiles().upload();
            if (toonAtlas != null) uploaded.put(ToonProfiles.ATLAS, toonAtlas);
            for (var entry : prepared.arrayTargets().entrySet())
                uploaded.put(entry.getKey(), EffectTextureLoader.uploadArrayTarget(entry.getValue()));
            for (var entry : prepared.cubeTargets().entrySet())
                uploaded.put(entry.getKey(), EffectTextureLoader.uploadCubeTarget(entry.getValue()));
            for (var entry : prepared.volumeTargets().entrySet())
                uploaded.put(entry.getKey(), EffectTextureLoader.uploadVolumeTarget(entry.getValue()));
            for (var entry : prepared.textureTargets().entrySet())
                targets.put(entry.getKey(), new RenderTargetTexture(entry.getKey(), entry.getValue(), mipPrograms));
            // Assets without a sidecar keep the vanilla TextureManager path.
            prepared.definitions().values().stream()
                .flatMap(effect -> effect.materials().values().stream())
                .flatMap(material -> material.textures().values().stream()).distinct()
                .filter(texture -> !prepared.textures().containsKey(texture) && !prepared.textureTargets().containsKey(texture)
                        && !prepared.arrayTargets().containsKey(texture) && !prepared.cubeTargets().containsKey(texture)
                        && !prepared.volumeTargets().containsKey(texture))
                    .forEach(texture -> Minecraft.getInstance().getTextureManager().getTexture(texture));
            var previous = VfxEngine.INSTANCE.commitReload(candidate, uploaded, targets, mipPrograms,
                    prepared.volumeMaterials(), prepared.virtualTextures(), prepared.shadingEnergyShaders(),
                    prepared.subsurfaceProfiles(), prepared.specularProfiles(), prepared.toonProfiles(), prepared.meshStreaming(), prepared.skeletalMeshStreaming());
            published = true;
            FXHelper.publishIndex(prepared.photonEffects());
            EffectTextures.releaseReplaced(previous);
        } finally {
            candidate.close();
            if (!published) {
                try { uploaded.values().forEach(EffectTexture::close); }
                finally {
                    try { targets.values().forEach(RenderTargetTexture::close); }
                    finally { mipPrograms.close(); }
                }
            }
        }
    }
}
