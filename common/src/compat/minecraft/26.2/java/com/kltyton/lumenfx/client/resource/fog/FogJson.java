package com.kltyton.lumenfx.client.resource.fog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.client.resource.light.SceneIndirectLightingJson;
import com.kltyton.lumenfx.effect.render.fog.ExponentialHeightFog;
import com.kltyton.lumenfx.effect.render.fog.FogSettings;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class FogJson {
    private FogJson() { }

    public static FogSettings read(JsonObject render, SceneIndirectLighting indirect) {
        if (!render.has("fog")) return FogSettings.HOST;
        JsonObject fog = render.getAsJsonObject("fog");
        if (!fog.has("model")) throw new JsonParseException("Missing scene fog model");
        String model = fog.get("model").getAsString();
        if (model.equals("host")) return FogSettings.HOST;
        if (!model.equals("exponential_height") && !model.equals("volumetric"))
            throw new JsonParseException("Unknown scene fog model: " + model);
        ExponentialHeightFog height = height(fog);
        if (model.equals("exponential_height")) return height;
        JsonObject volume = fog.getAsJsonObject("volume");
        if (!volume.has("lighting")) throw new JsonParseException("Missing source volume lighting model");
        String lighting = volume.get("lighting").getAsString();
        if (!lighting.equals("emissive") && !lighting.equals("scene"))
            throw new JsonParseException("Unknown source volume lighting model: " + lighting);
        var skyLight = SceneIndirectLightingJson.skyLight(volume.getAsJsonObject("sky_light"));
        return new VolumetricFog(height, number(volume, "start_cm"), number(volume, "end_cm"), number(volume, "near_fade_cm"),
                number(volume, "extinction_scale"), color(volume, "emissive"), volume.get("emissive_scales_with_density").getAsBoolean(),
                volume.get("grid_pixel_size").getAsInt(), volume.get("grid_z").getAsInt(), number(volume, "depth_distribution"),
                volume.get("temporal").getAsBoolean(), number(volume, "history_weight"), volume.get("conservative_depth").getAsBoolean(),
                number(volume, "center_offset"), number(volume, "center_threshold"), volume.get("maximum_volume_dimension").getAsInt(),
                volume.has("albedo") ? color(volume, "albedo") : new Vec3(1, 1, 1),
                volume.has("scattering_distribution") ? number(volume, "scattering_distribution") : .2f,
                volume.has("inverse_squared_bias_scale") ? number(volume, "inverse_squared_bias_scale") : 1,
                lighting.equals("scene"), !volume.has("jitter") || volume.get("jitter").getAsBoolean(),
                volume.has("history_miss_samples") ? volume.get("history_miss_samples").getAsInt() : 4,
                volume.has("sample_jitter_multiplier") ? number(volume, "sample_jitter_multiplier") : 0,
                volume.has("light_soft_fading") ? number(volume, "light_soft_fading") : 0,
                volume.has("rect_light_texture") && volume.get("rect_light_texture").getAsBoolean(),
                volume.has("override_light_colors_with_fog_inscattering_colors")
                        && volume.get("override_light_colors_with_fog_inscattering_colors").getAsBoolean(),
                skyLight == null ? indirect.skyLight() : skyLight, precomputed(volume, indirect));
    }

    private static VolumetricFog.@Nullable Precomputed precomputed(JsonObject volume, SceneIndirectLighting indirect) {
        var lightmap = SceneIndirectLightingJson.lightmap(volume);
        if (lightmap == null) lightmap = indirect.lightmap();
        if (lightmap == null) return null;
        float intensity = volume.has("static_lighting_scattering_intensity") ? number(volume, "static_lighting_scattering_intensity") : 1f;
        return new VolumetricFog.Precomputed(lightmap, intensity);
    }

    private static ExponentialHeightFog height(JsonObject fog) {
        JsonArray layers = fog.getAsJsonArray("layers");
        if (layers.size() != 2) throw new JsonParseException("Exponential height fog requires two source layers");
        JsonObject light = fog.getAsJsonObject("directional");
        var directional = new ExponentialHeightFog.Directional(color(light, "color"), color(light, "direction"),
                number(light, "exponent"), number(light, "start_cm"));
        return new ExponentialHeightFog(layer(layers.get(0).getAsJsonObject()), layer(layers.get(1).getAsJsonObject()),
                color(fog, "color"), number(fog, "maximum_opacity"), number(fog, "start_cm"),
                number(fog, "end_cm"), number(fog, "cutoff_cm"), directional);
    }

    private static ExponentialHeightFog.Layer layer(JsonObject layer) {
        return new ExponentialHeightFog.Layer(number(layer, "density"), number(layer, "falloff"), number(layer, "height_cm"));
    }

    private static float number(JsonObject value, String name) {
        if (!value.has(name)) throw new JsonParseException("Missing source fog field: " + name);
        return value.get(name).getAsFloat();
    }

    private static Vec3 color(JsonObject value, String name) {
        JsonArray components = value.getAsJsonArray(name);
        if (components == null || components.size() != 3) throw new JsonParseException("Expected source fog vector: " + name);
        return new Vec3(components.get(0).getAsDouble(), components.get(1).getAsDouble(), components.get(2).getAsDouble());
    }
}
