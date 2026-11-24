package com.kltyton.lumenfx.client.resource.light;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.client.resource.EffectResources;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting;
import com.kltyton.lumenfx.effect.render.light.scene.SkyLightIrradiance;
import com.kltyton.lumenfx.effect.render.light.scene.SkyLightIrradiance.Coefficient;
import com.kltyton.lumenfx.effect.render.light.scene.VolumetricLightmap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class SceneIndirectLightingJson {
    private SceneIndirectLightingJson() { }

    public static void prepare(ResourceManager resources, JsonObject effect,
                               Map<Identifier, VolumetricLightmapResources.Asset> lightmaps,
                               Map<Identifier, SkyLightReflectionResources.Asset> skyReflections,
                               Map<Identifier, PreparedTexture> reflectionArrays) throws IOException {
        prepareView(resources, lightmaps, skyReflections, reflectionArrays, EffectResources.jsonObject(effect, "render"));
        var materials = EffectResources.jsonObject(effect, "materials");
        if (materials != null) for (var material : materials.entrySet()) {
            if (!material.getValue().isJsonObject()) continue;
            prepareView(resources, lightmaps, skyReflections, reflectionArrays,
                    EffectResources.jsonObject(material.getValue().getAsJsonObject(), "render"));
        }
    }

    private static void prepareView(ResourceManager resources, Map<Identifier, VolumetricLightmapResources.Asset> lightmaps,
                                    Map<Identifier, SkyLightReflectionResources.Asset> skyReflections,
                                    Map<Identifier, PreparedTexture> reflectionArrays,
                                    @Nullable JsonObject render) throws IOException {
        if (render == null) return;
        var indirect = EffectResources.jsonObject(render, "indirect_lighting");
        prepareResources(resources, lightmaps, skyReflections, indirect);
        if (indirect != null) ReflectionCaptureResources.prepare(resources, indirect, reflectionArrays);
        var fog = EffectResources.jsonObject(render, "fog");
        if (fog != null) prepareResources(resources, lightmaps, skyReflections, EffectResources.jsonObject(fog, "volume"));
    }

    private static void prepareResources(ResourceManager resources, Map<Identifier, VolumetricLightmapResources.Asset> lightmaps,
                                         Map<Identifier, SkyLightReflectionResources.Asset> skyReflections,
                                         @Nullable JsonObject lighting) throws IOException {
        if (lighting == null) return;
        if (lighting.has("sky_light") && lighting.get("sky_light").isJsonPrimitive()) {
            Identifier id = Identifier.parse(lighting.get("sky_light").getAsString());
            try (var reader = resources.getResourceOrThrow(id).openAsReader()) {
                lighting.add("sky_light", JsonParser.parseReader(reader).getAsJsonObject());
            }
        }
        if (lighting.has("precomputed_lightmap") && lighting.get("precomputed_lightmap").isJsonPrimitive()) {
            Identifier id = Identifier.parse(lighting.get("precomputed_lightmap").getAsString());
            lighting.add("precomputed_lightmap",
                    lightmaps.computeIfAbsent(id, key -> VolumetricLightmapResources.read(resources, key)).template());
        }
        var sky = EffectResources.jsonObject(lighting, "sky_light");
        var cubemap = EffectResources.jsonObject(sky, "specular_cubemap");
        if (cubemap != null) {
            var reflection = reflection(cubemap);
            prepareReflection(resources, skyReflections, reflection.texture(), reflection.mipCount(), reflection.preintegrated());
            if (reflection.blend() != null)
                prepareReflection(resources, skyReflections, reflection.blend().texture(), reflection.mipCount(), false);
        }
    }

    private static void prepareReflection(ResourceManager resources, Map<Identifier, SkyLightReflectionResources.Asset> reflections,
                                          Identifier id, int mipCount, boolean requireGf) {
        var textures = reflections.computeIfAbsent(id, key -> SkyLightReflectionResources.read(resources, key));
        if (textures.cube().mips().size() != mipCount)
            throw new JsonParseException("Sky reflection mip count disagrees with " + id);
        if (requireGf && textures.preintegrated() == null)
            throw new JsonParseException("Sky reflection requires the native preintegrated GF " + id);
    }

    public static SceneIndirectLighting read(JsonObject render) {
        var lighting = render.getAsJsonObject("indirect_lighting");
        if (lighting == null) return SceneIndirectLighting.NONE;
        var captures = lighting.getAsJsonObject("reflection_captures");
        return new SceneIndirectLighting(skyLight(lighting.getAsJsonObject("sky_light")), lightmap(lighting),
                lighting.has("precomputed_color_scale") ? vector(lighting, "precomputed_color_scale") : new Vec3(1, 1, 1),
                captures == null ? null : ReflectionCaptureResources.read(captures));
    }

    public static @Nullable VolumetricLightmap lightmap(JsonObject lighting) {
        var source = lighting.getAsJsonObject("precomputed_lightmap");
        if (source == null) return null;
        return new VolumetricLightmap(Identifier.parse(source.get("asset").getAsString()), vector(source, "min"),
                vector(source, "max"), source.get("brick_size").getAsInt(), source.get("sky_bent_normal").getAsBoolean(),
                source.get("directional_shadow").getAsBoolean());
    }

    public static @Nullable SkyLightIrradiance skyLight(@Nullable JsonObject source) {
        if (source == null) return null;
        JsonArray values = source.getAsJsonArray("diffuse_sh");
        if (values == null || values.size() != 7) throw new JsonParseException("Sky light requires seven packed irradiance coefficients");
        var coefficients = new ArrayList<Coefficient>(values.size());
        for (var value : values) {
            JsonArray vector = value.getAsJsonArray();
            if (vector.size() != 4) throw new JsonParseException("Sky irradiance coefficient requires four components");
            coefficients.add(new Coefficient(vector.get(0).getAsFloat(), vector.get(1).getAsFloat(),
                    vector.get(2).getAsFloat(), vector.get(3).getAsFloat()));
        }
        boolean staticLighting = source.get("static_lighting").getAsBoolean();
        boolean staticShadowing = source.has("static_shadowing") && source.get("static_shadowing").getAsBoolean();
        var reflection = source.has("specular_cubemap") ? reflection(source.getAsJsonObject("specular_cubemap")) : null;
        boolean stationary = source.has("stationary") ? source.get("stationary").getAsBoolean()
                : staticShadowing || reflection != null && !reflection.movable() && !staticLighting;
        return new SkyLightIrradiance(coefficients, vector(source, "color"), source.get("volumetric_scattering").getAsFloat(),
                staticLighting, staticShadowing,
                !source.has("affects_world") || source.get("affects_world").getAsBoolean(),
                reflection, stationary, source.has("occlusion_tint") ? vector(source, "occlusion_tint") : Vec3.ZERO);
    }

    private static SkyLightIrradiance.Reflection reflection(JsonObject source) {
        var blend = source.getAsJsonObject("blend");
        return new SkyLightIrradiance.Reflection(Identifier.parse(source.get("texture").getAsString()),
                source.get("mip_count").getAsInt(), source.get("average_brightness").getAsFloat(), source.get("movable").getAsBoolean(),
                source.has("preintegrated_gf") && source.get("preintegrated_gf").getAsBoolean(),
                source.has("luminance_weights") ? vector(source, "luminance_weights") : new Vec3(.2126, .7152, .0722),
                blend == null ? null : new SkyLightIrradiance.Blend(Identifier.parse(blend.get("texture").getAsString()),
                        blend.get("fraction").getAsFloat()));
    }

    private static Vec3 vector(JsonObject source, String key) {
        var values = source.getAsJsonArray(key);
        if (values == null || values.size() != 3) throw new JsonParseException("Invalid scene lighting vector " + key);
        return new Vec3(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
    }
}
