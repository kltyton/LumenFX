package com.kltyton.lumenfx.client.resource;

import static com.kltyton.lumenfx.effect.asset.EffectJsonFields.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomAsset;
import com.kltyton.lumenfx.client.resource.fog.FogJson;
import com.kltyton.lumenfx.client.resource.light.LightRendererJson;
import com.kltyton.lumenfx.client.resource.light.SceneIndirectLightingJson;
import com.kltyton.lumenfx.client.resource.material.MaterialCollectionJson;
import com.kltyton.lumenfx.client.resource.mesh.MeshRendererJson;
import com.kltyton.lumenfx.client.resource.niagara.NiagaraJson;
import com.kltyton.lumenfx.client.resource.scene.PhysicsFieldComponentJson;
import com.kltyton.lumenfx.client.resource.splat.SplatRendererJson;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTextureMaterialJson;
import com.kltyton.lumenfx.client.resource.volume.VolumeRendererJson;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.RenderSettings;
import com.kltyton.lumenfx.effect.asset.EffectEmitterJson;
import com.kltyton.lumenfx.effect.asset.EffectJsonFields;
import com.kltyton.lumenfx.effect.asset.unity.UnityEmitterJson;
import com.kltyton.lumenfx.effect.curve.FloatCurve;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.interfaces.splat.NiagaraGaussianSplatAsset;
import com.kltyton.lumenfx.effect.niagara.interfaces.volume.NiagaraVolumeCacheAsset;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.kltyton.lumenfx.effect.render.MaterialBlendOp;
import com.kltyton.lumenfx.effect.render.MaterialDepth;
import com.kltyton.lumenfx.effect.render.MaterialFog;
import com.kltyton.lumenfx.effect.render.TranslucencyPass;
import com.kltyton.lumenfx.effect.render.depth.CustomDepthStencil;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.kltyton.lumenfx.effect.render.light.PrimitiveLighting;
import com.kltyton.lumenfx.effect.render.material.MaterialSamplerSource;
import com.kltyton.lumenfx.effect.render.mesh.NaniteMaterialSource;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.vertex.VertexMaterialSettings;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding;
import com.kltyton.lumenfx.effect.unity.UnityShapeTexture;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class EffectJson {
    private EffectJson() { }

    public static EffectDefinition read(JsonObject root) {
        return read(root, id -> { throw new JsonParseException("Vector field resolver required for " + id); });
    }

    public static EffectDefinition read(JsonObject root, Function<Identifier, VectorFieldVolume> vectorFields) {
        return read(root, vectorFields, id -> { throw new JsonParseException("Static mesh resolver required for " + id); });
    }

    public static EffectDefinition read(JsonObject root, Function<Identifier, VectorFieldVolume> vectorFields,
                                        Function<Identifier, StaticMeshAsset> meshes) {
        return read(root, vectorFields, meshes, id -> { throw new JsonParseException("Skeletal mesh resolver required for " + id); });
    }

    public static EffectDefinition read(JsonObject root, Function<Identifier, VectorFieldVolume> vectorFields,
                                        Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal) {
        return read(root, vectorFields, meshes, skeletal, id -> { throw new JsonParseException("Simulation cache resolver required for " + id); });
    }

    public static EffectDefinition read(JsonObject root, Function<Identifier, VectorFieldVolume> vectorFields,
                                        Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal,
                                        Function<Identifier, NiagaraSimulationCache> caches) {
        return read(root, vectorFields, meshes, skeletal, caches, id -> { throw new JsonParseException("Sparse volume resolver required for " + id); });
    }

    public static EffectDefinition read(JsonObject root, Function<Identifier, VectorFieldVolume> vectorFields,
                                        Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal,
                                        Function<Identifier, NiagaraSimulationCache> caches,
                                        Function<Identifier, SparseVolumeTextureAsset> sparseVolumes) {
        return read(root, vectorFields, meshes, skeletal, caches, sparseVolumes,
                id -> { throw new JsonParseException("Volume Cache resolver required for " + id); });
    }

    public static EffectDefinition read(JsonObject root, Function<Identifier, VectorFieldVolume> vectorFields,
                                        Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal,
                                        Function<Identifier, NiagaraSimulationCache> caches,
                                        Function<Identifier, SparseVolumeTextureAsset> sparseVolumes,
                                        Function<Identifier, NiagaraVolumeCacheAsset> volumeCaches) {
        return read(root, vectorFields, meshes, skeletal, caches, sparseVolumes, volumeCaches,
                id -> { throw new JsonParseException("Groom resolver required for " + id); });
    }

    public static EffectDefinition read(JsonObject root, Function<Identifier, VectorFieldVolume> vectorFields,
                                        Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal,
                                        Function<Identifier, NiagaraSimulationCache> caches,
                                        Function<Identifier, SparseVolumeTextureAsset> sparseVolumes,
                                        Function<Identifier, NiagaraVolumeCacheAsset> volumeCaches,
                                        Function<Identifier, NiagaraGroomAsset> grooms) {
        return read(root, vectorFields, meshes, skeletal, caches, sparseVolumes, volumeCaches, grooms,
                id -> { throw new JsonParseException("Unity emission texture resolver required for " + id); });
    }

    public static EffectDefinition read(JsonObject root, Function<Identifier, VectorFieldVolume> vectorFields,
                                        Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal,
                                        Function<Identifier, NiagaraSimulationCache> caches,
                                        Function<Identifier, SparseVolumeTextureAsset> sparseVolumes,
                                        Function<Identifier, NiagaraVolumeCacheAsset> volumeCaches,
                                        Function<Identifier, NiagaraGroomAsset> grooms,
                                        Function<Identifier, UnityShapeTexture.Pixels> shapeTextures) {
        return read(root, vectorFields, meshes, skeletal, caches, sparseVolumes, volumeCaches, grooms, shapeTextures,
                id -> { throw new JsonParseException("Gaussian Splat resolver required for " + id); });
    }

    public static EffectDefinition read(JsonObject root, Function<Identifier, VectorFieldVolume> vectorFields,
                                        Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal,
                                        Function<Identifier, NiagaraSimulationCache> caches,
                                        Function<Identifier, SparseVolumeTextureAsset> sparseVolumes,
                                        Function<Identifier, NiagaraVolumeCacheAsset> volumeCaches,
                                        Function<Identifier, NiagaraGroomAsset> grooms,
                                        Function<Identifier, UnityShapeTexture.Pixels> shapeTextures,
                                        Function<Identifier, NiagaraGaussianSplatAsset> gaussianSplats) {
        if (root.get("format_version").getAsInt() != 1) throw new JsonParseException("Unsupported LumenFX format version");
        JsonObject rootRender = root.has("render") ? root.getAsJsonObject("render") : null;
        var rootIndirect = rootRender != null ? SceneIndirectLightingJson.read(rootRender)
                : com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting.NONE;
        Map<String, MaterialDefinition> materials = new LinkedHashMap<>();
        for (var entry : root.getAsJsonObject("materials").entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            JsonObject rendering = value.has("render") ? value.getAsJsonObject("render")
                    : rootRender != null ? rootRender : new JsonObject();
            JsonObject fog = value.has("fog") ? value.getAsJsonObject("fog") : new JsonObject();
            var blend = MaterialDefinition.Blend.valueOf(string(value, "blend", "alpha").toUpperCase(Locale.ROOT));
            var decal = value.has("decal") && !value.get("decal").isJsonNull()
                    ? com.kltyton.lumenfx.client.resource.decal.DecalMaterialJson.read(value.getAsJsonObject("decal")) : null;
            boolean lightFunction = bool(value, "light_function", false);
            int virtualTextureOutputs = materialMask(value, "runtime_virtual_texture_outputs", 255);
            var shadowCoverage = MaterialDefinition.PointShadowCoverage.valueOf(string(value, "point_shadow_coverage",
                    MaterialDefinition.legacyPointShadowCoverage(blend, decal, lightFunction, virtualTextureOutputs).name())
                    .toUpperCase(Locale.ROOT));
            boolean legacyDepthTest = bool(value, "depth_test", true);
            var depth = new MaterialDepth(MaterialDepth.Compare.valueOf(string(value, "depth_compare",
                    legacyDepthTest ? "less_equal" : "always").toUpperCase(Locale.ROOT)),
                    bool(value, "depth_write", legacyDepthTest && blend == MaterialDefinition.Blend.OPAQUE));
            Map<String, Identifier> textures = new LinkedHashMap<>();
            if (value.has("textures")) for (var texture : value.getAsJsonObject("textures").entrySet())
                textures.put(texture.getKey(), Identifier.parse(texture.getValue().getAsString()));
            var samplerSources = new LinkedHashMap<String, MaterialSamplerSource>();
            if (value.has("texture_sampler_sources") && !value.get("texture_sampler_sources").isJsonNull())
                for (var sampler : value.getAsJsonObject("texture_sampler_sources").entrySet())
                    samplerSources.put(sampler.getKey(), MaterialSamplerSource.parse(sampler.getValue().getAsString()));
            var textureSamplers = new LinkedHashMap<String, com.kltyton.lumenfx.effect.render.material.MaterialTextureSampler>();
            if (value.has("texture_samplers") && !value.get("texture_samplers").isJsonNull())
                for (var sampler : value.getAsJsonObject("texture_samplers").entrySet())
                    textureSamplers.put(sampler.getKey(), com.kltyton.lumenfx.effect.render.material.MaterialTextureSampler.read(sampler.getValue().getAsJsonObject()));
            float[] parameters = new float[4];
            List<String> toonProfiles = new ArrayList<>();
            if (value.has("toon_profiles"))
                for (JsonElement profile : value.getAsJsonArray("toon_profiles")) toonProfiles.add(profile.getAsString());
            List<String> specularProfiles = new ArrayList<>();
            if (value.has("specular_profiles"))
                for (JsonElement profile : value.getAsJsonArray("specular_profiles")) specularProfiles.add(profile.getAsString());
            List<String> slabSubsurfaceProfiles = new ArrayList<>();
            if (value.has("slab_subsurface_profiles"))
                for (JsonElement profile : value.getAsJsonArray("slab_subsurface_profiles")) slabSubsurfaceProfiles.add(profile.getAsString());
            if (value.has("parameters")) {
                JsonArray values = value.getAsJsonArray("parameters");
                if (values.size() != 4) throw new JsonParseException("Material parameters must have four components");
                for (int i = 0; i < 4; i++) parameters[i] = values.get(i).getAsFloat();
            }
            materials.put(entry.getKey(), new MaterialDefinition(
                    Identifier.parse(string(value, "fragment_shader", textures.isEmpty() ? "lumenfx:core/color" : "lumenfx:core/effect")), textures,
                    blend, depth, bool(value, "cull", false), parameters,
                    readRenderSettings(rendering, rootIndirect),
                    new MaterialFog(bool(fog, "enabled", false), bool(fog, "per_pixel", false), bool(fog, "sky", false)),
                    TranslucencyPass.valueOf(string(value, "translucency_pass", "after_dof").toUpperCase(Locale.ROOT)),
                    integer(value, "dynamic_slots", 0), bool(value, "ribbon_particle_data", false),
                    bool(value, "depth_projection", false), bool(value, "scene_depth", false), bool(value, "macro_uv", false), string(value, "source_object", ""),
                    bool(value, "write_translucent_velocity", false), bool(value, "velocity_from_depth", false),
                    decal,
                    materialMask(value, "dbuffer_textures", 7), bool(value, "receives_decals", true), bool(value, "scene_color", false),
                    materialMask(value, "material_view", MaterialDefinition.MATERIAL_VIEW_INPUTS)
                            | (bool(value, "scene_color", false) ? 2 : 0),
                    value.has("vertex_material") && !value.get("vertex_material").isJsonNull()
                            ? new VertexMaterialSettings(
                                    Identifier.parse(value.get("vertex_material").getAsString()), (float)number(value, "maximum_vertex_displacement", 0),
                                    materialMask(value, "customized_uv_mask", 255), bool(value, "world_position_offset", true),
                                    bool(value, "vertex_basis", false), bool(value, "spherical_particle_normals", false),
                                    bool(value, "previous_view", false), bool(value, "previous_matrices", false),
                                    bool(value, "primitive_matrices", false), bool(value, "previous_first_person", false),
                                    materialMask(value, "first_person_inputs", VertexMaterialSettings.FIRST_PERSON_INPUTS),
                                    bool(value, "previous_clock", bool(value, "world_position_offset", true)),
                                    value.has("nanite_vertex_material") && !value.get("nanite_vertex_material").isJsonNull()
                                            ? Identifier.parse(value.get("nanite_vertex_material").getAsString()) : null,
                                    value.has("nanite_interpolator_widths")
                                            ? StreamSupport.stream(value.getAsJsonArray("nanite_interpolator_widths").spliterator(), false)
                                                    .map(element -> element.getAsInt()).toList() : List.of()) : null,
                    lightFunction,
                    com.kltyton.lumenfx.client.resource.parameter.MaterialInstanceJson.read(value.getAsJsonArray("runtime_parameters"),
                            value.getAsJsonArray("runtime_textures"), value.getAsJsonArray("font_samplers"),
                            value.getAsJsonArray("texture_parameter_samplers")),
                    MaterialCollectionJson.bindings(value.getAsJsonArray("material_collections")),
                    virtualTextureOutputs,
                    VirtualTextureMaterialJson.read(value.getAsJsonArray("virtual_texture_reads")),
                    bool(value, "virtual_texture_feedback", false), bool(value, "default_lit_surface", false), shadowCoverage,
                    bool(value, "translucent_custom_depth", false), materialMask(value, "custom_scene_textures", 3),
                    materialMask(value, "deferred_scene_textures", MaterialDefinition.DEFERRED_SCENE_TEXTURES),
                    bool(value, "anisotropic_surface", false), bool(value, "custom_surface_data", false),
                    materialMask(value, "particle_lighting_features", MaterialDefinition.PARTICLE_LIGHTING_FEATURES),
                    value.has("subsurface_profile") && !value.get("subsurface_profile").isJsonNull()
                            ? value.get("subsurface_profile").getAsString() : null, slabSubsurfaceProfiles, specularProfiles,
                    integer(value, "adaptive_substrate_words", 0), bool(value, "light_function_atlas_compatible", false),
                    MaterialDefinition.TranslucencyVolume.valueOf(
                            string(value, "translucency_lighting_volume", "none").toUpperCase(Locale.ROOT)), naniteMaterialSource(value),
                    bool(value, "high_quality_forward_reflections", false), samplerSources, textureSamplers,
                    MaterialBlendOp.valueOf(string(value, "blend_op", "add").toUpperCase(Locale.ROOT)),
                    materialMask(value, "sg_uv4_mask", 255), toonProfiles,
                    integer(value, "automatic_sprite_coverage_channels",
                            com.kltyton.lumenfx.effect.render.SpriteCoverage.legacyAutomaticChannels(blend))));
        }
        List<EmitterDefinition> emitters = new ArrayList<>();
        Map<String, UnityRendererBinding> unityRenderers = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("emitters")) {
            JsonObject value = element.getAsJsonObject();
            String name = value.get("name").getAsString();
            var renderer = jsonObject(value, "unity_renderer");
            if (renderer != null) unityRenderers.put(name, UnityEmitterJson.renderer(renderer, meshes));
            var metadata = new EffectEmitterJson.RendererMetadata(
                    value.has("mesh") && !value.get("mesh").isJsonNull()
                            ? MeshRendererJson.read(value.getAsJsonObject("mesh"), meshes, materials) : null,
                    value.has("light") && !value.get("light").isJsonNull() ? LightRendererJson.read(value.getAsJsonObject("light")) : null,
                    value.has("decal") && !value.get("decal").isJsonNull()
                            ? com.kltyton.lumenfx.client.resource.decal.DecalRendererJson.read(value.getAsJsonObject("decal")) : null,
                    value.has("volume") && !value.get("volume").isJsonNull()
                            ? VolumeRendererJson.read(value.getAsJsonObject("volume")) : null,
                    value.has("splat") && !value.get("splat").isJsonNull()
                            ? SplatRendererJson.read(value.getAsJsonObject("splat")) : null);
            emitters.add(EffectEmitterJson.read(value, vectorFields, meshes, shapeTextures, metadata));
        }
        var primitiveLighting = jsonObject(root, "primitive_lighting");
        var customDepth = jsonObject(root, "custom_depth");
        var niagaraRoot = jsonObject(root, "niagara");
        var rendererObjects = jsonObject(root, "renderer_objects");
        var renderRoot = jsonObject(root, "render");
        return new EffectDefinition(materials, emitters, niagaraRoot != null
                ? NiagaraJson.read(niagaraRoot, vectorFields, meshes, skeletal, caches, sparseVolumes, volumeCaches,
                        gaussianSplats) : null,
                rendererObjects != null ? com.kltyton.lumenfx.client.resource.parameter.RendererObjectsJson.read(
                        rendererObjects, materials, meshes) : Map.of(),
                renderRoot != null ? readRenderSettings(renderRoot)
                        : materials.values().stream().findFirst().map(MaterialDefinition::renderSettings).orElse(RenderSettings.STANDARD),
                PhysicsFieldComponentJson.read(jsonObject(root, "physics_field_components")),
                groomAssets(jsonObject(root, "groom_assets"), grooms),
                materialFonts(jsonObject(root, "material_fonts")),
                primitiveLighting == null ? PrimitiveLighting.DEFAULT
                        : new PrimitiveLighting(integer(primitiveLighting, "channels", 1), bool(primitiveLighting, "casts_dynamic_shadow", false),
                                bool(primitiveLighting, "casts_contact_shadow", false),
                                bool(primitiveLighting, "ray_tracing_far_field", false)),
                bool(root, "receives_decals", true),
                customDepth == null ? CustomDepthStencil.DISABLED : new CustomDepthStencil(bool(customDepth, "enabled", false),
                        integer(customDepth, "stencil", 0), CustomDepthStencil.WriteMask.valueOf(
                                string(customDepth, "stencil_mask", "default").toUpperCase(Locale.ROOT))), unityRenderers);
    }

    private static @Nullable NaniteMaterialSource naniteMaterialSource(JsonObject value) {
        if (!value.has("nanite_material_source") || value.get("nanite_material_source").isJsonNull()) return null;
        var source = value.getAsJsonObject("nanite_material_source");
        var field = source.get("shading_model_field");
        return new NaniteMaterialSource(source.get("domain").getAsString(),
                NaniteMaterialSource.BlendMode.valueOf(source.get("blend_mode").getAsString()),
                NaniteMaterialSource.ShadingModel.valueOf(source.get("shading_model").getAsString()),
                field == null || field.isJsonNull() ? null : field.getAsInt(),
                source.get("used_with_nanite").getAsBoolean(), source.get("automatically_set_usage").getAsBoolean(),
                source.get("sky").getAsBoolean(), source.get("two_sided").getAsBoolean(),
                source.get("opacity_mask_clip").getAsDouble(), source.has("pixel_depth_offset") ? source.get("pixel_depth_offset").getAsBoolean() : null,
                bool(source, "dither_opacity_mask", false));
    }

    private static Map<String, Map<Integer, Identifier>> materialFonts(@Nullable JsonObject source) {
        if (source == null) return Map.of();
        var fonts = new LinkedHashMap<String, Map<Integer, Identifier>>();
        for (var font : source.entrySet()) {
            var pages = new LinkedHashMap<Integer, Identifier>();
            for (var page : font.getValue().getAsJsonObject().entrySet()) {
                int index = Integer.parseInt(page.getKey());
                if (pages.putIfAbsent(index, Identifier.parse(page.getValue().getAsString())) != null)
                    throw new JsonParseException("Duplicate offline font page " + index + " in " + font.getKey());
            }
            fonts.put(font.getKey(), pages);
        }
        return fonts;
    }

    private static Map<String, NiagaraGroomAsset> groomAssets(JsonObject sources, Function<Identifier, NiagaraGroomAsset> loader) {
        if (sources == null) return Map.of();
        var result = new LinkedHashMap<String, NiagaraGroomAsset>();
        for (var source : sources.entrySet()) {
            String key = SourceNames.canonical(source.getKey());
            if (result.putIfAbsent(key, loader.apply(Identifier.parse(source.getValue().getAsString()))) != null)
                throw new JsonParseException("Duplicate source Groom asset " + source.getKey());
        }
        return result;
    }

    private static int materialMask(JsonObject material, String name, int allowed) {
        JsonElement value = material.get(name);
        if (value == null) return 0;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new JsonParseException(name + " must be an integer mask");
        try {
            int mask = value.getAsBigDecimal().intValueExact();
            if ((mask & ~allowed) != 0) throw new JsonParseException(name + " must be in 0.." + allowed);
            return mask;
        } catch (ArithmeticException error) {
            throw new JsonParseException(name + " must be an integer mask", error);
        }
    }

    private static RenderSettings readRenderSettings(JsonObject rendering) {
        return readRenderSettings(rendering, com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting.NONE);
    }

    private static RenderSettings readRenderSettings(
            JsonObject rendering,
            com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting fallbackIndirect) {
        var indirect = rendering.has("indirect_lighting") ? SceneIndirectLightingJson.read(rendering) : fallbackIndirect;
        var fog = FogJson.read(rendering, indirect);
        boolean linearParticles = fog instanceof VolumetricFog;
        var blendColorSpace = RenderSettings.BlendColorSpace.valueOf(
                string(rendering, "blend_color_space", "linear").toUpperCase(Locale.ROOT));
        return new RenderSettings(linearParticles || bool(rendering, "hdr", false), fog,
                new com.kltyton.lumenfx.effect.render.mesh.MeshLodPolicy(bool(rendering, "mesh_min_lod_per_quality", false),
                        integer(rendering, "mesh_min_lod_quality_level", -1)),
                indirect, blendColorSpace);
    }


    public static ScalarFunction scalar(JsonElement value) {
        return EffectJsonFields.scalar(value);
    }

    public static FloatCurve curve(JsonElement value) {
        return EffectJsonFields.curve(value);
    }
}
