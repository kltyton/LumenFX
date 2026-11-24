package com.kltyton.lumenfx.effect.asset;

import static com.kltyton.lumenfx.effect.asset.EffectJsonFields.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.asset.unity.UnityEmitterJson;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.noise.FieldSampling;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.kltyton.lumenfx.effect.render.decal.DecalRendererSettings;
import com.kltyton.lumenfx.effect.render.light.LightRendererSettings;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.splat.SplatRendererSettings;
import com.kltyton.lumenfx.effect.render.volume.VolumeRendererSettings;
import com.kltyton.lumenfx.effect.unity.UnityShapeTexture;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Reads emitter settings used by both presentation and gameplay simulation. */
public final class EffectEmitterJson {
    private EffectEmitterJson() { }

    public record RendererMetadata(@Nullable MeshRendererSettings mesh, @Nullable LightRendererSettings light,
                                   @Nullable DecalRendererSettings decal, @Nullable VolumeRendererSettings volume,
                                   @Nullable SplatRendererSettings splat) {
        public static final RendererMetadata EMPTY = new RendererMetadata(null, null, null, null, null);
    }

    public static EmitterDefinition read(JsonObject value, Function<Identifier, VectorFieldVolume> vectorFields,
                                         Function<Identifier, StaticMeshAsset> meshes,
                                         Function<Identifier, UnityShapeTexture.Pixels> shapeTextures, RendererMetadata render) {
        List<EmitterDefinition.Burst> bursts = new ArrayList<>();
        var burstItems = jsonArray(value, "bursts");
        if (burstItems != null) for (JsonElement burst : burstItems) {
            JsonObject b = burst.getAsJsonObject();
            var count = b.get("count");
            if (count != null && !count.isJsonNull() && (b.has("min") || b.has("max")))
                throw new JsonParseException("Emission burst mixes a count curve with a constant range");
            bursts.add(new EmitterDefinition.Burst(number(b, "time", 0), count == null || count.isJsonNull() ? b.get("min").getAsInt() : 0,
                    count == null || count.isJsonNull() ? b.get("max").getAsInt() : 0, integer(b, "cycles", 1), number(b, "interval", 0),
                    number(b, "probability", 1), count == null || count.isJsonNull() ? null : scalar(count)));
        }
        JsonObject shape = jsonObject(value, "shape");
        if (shape == null) shape = new JsonObject();
        JsonObject noise = jsonObject(value, "noise");
        if (noise == null) noise = new JsonObject();
        JsonObject randomization = jsonObject(value, "randomization");
        if (randomization == null) randomization = new JsonObject();
        FieldSampling fieldSampling = null;
        String noiseField = jsonString(noise, "field");
        if (noiseField != null) fieldSampling = new FieldSampling(vectorFields.apply(Identifier.parse(noiseField)),
                vector(noise, "field_offset_scale", Vec3.ZERO), integer(noise, "seed_x", 0), integer(noise, "seed_y", 0), integer(noise, "seed_z", 0),
                bool(noise, "scale_lookup_by_owner", false), bool(noise, "tile_x", false), bool(noise, "tile_y", false), bool(noise, "tile_z", false));
        List<ScalarFunction> custom = new ArrayList<>();
        var customData = jsonArray(value, "custom_data");
        if (customData != null) for (JsonElement field : customData) custom.add(scalar(field));
        else for (int i = 0; i < 4; i++) custom.add(ScalarFunction.constant(0));
        var shapeSize = vector(shape, "size", Vec3.ZERO);
        return new EmitterDefinition(value.get("name").getAsString(),
                EmitterDefinition.Renderer.valueOf(value.get("renderer").getAsString().toUpperCase(Locale.ROOT)),
                string(value, "material", ""), bool(value, "local_space", false),
                number(value, "delay", 0), number(value, "duration", 1), bool(value, "loop", true),
                integer(value, "max_particles", 4096), scalar(value, "rate", 0), number(value, "distance_spacing", 0), number(value, "movement_threshold", Double.MAX_VALUE), bursts,
                scalar(value, "lifetime", 1), scalar(value, "mass", 1), scalar(value, "width", 1), scalar(value, "height", 1),
                scalar(value, "rotation", 0), scalar(value, "angular_velocity", 0),
                scalar(value, "start_speed", 0), vector(value, "start_direction", new Vec3(0, 1, 0)), color(value, "start_color"),
                vector(value, "velocity_min", Vec3.ZERO), vector(value, "velocity_max", Vec3.ZERO),
                vector(value, "acceleration", Vec3.ZERO), scalar(value, "drag", 0),
                components(value, "velocity_over_life", 3), components(value, "force_over_life", 3),
                new EmitterDefinition.Shape(EmitterDefinition.ShapeType.valueOf(string(shape, "type", "point").toUpperCase(Locale.ROOT)),
                        shapeSize, bool(shape, "surface", false), number(shape, "angle", 0),
                        UnityEmitterJson.shape(jsonObject(shape, "unity"), meshes, shapeTextures, shapeSize)),
                scalar(value, "size_over_life", 1), color(value, "color"), custom,
                new EmitterDefinition.Noise(vector(noise, "strength", Vec3.ZERO), number(noise, "frequency", 1), vector(noise, "scroll", Vec3.ZERO),
                        EmitterDefinition.NoiseMode.valueOf(string(noise, "mode", "displacement").toUpperCase(Locale.ROOT)), fieldSampling),
                EmitterDefinition.Integration.valueOf(string(value, "integrator", "analytic").toUpperCase(Locale.ROOT)),
                new EmitterDefinition.Randomization(integer(randomization, "drag_seed", 0), integer(randomization, "drag_context", 1),
                        bool(randomization, "independent_color", false), integer(randomization, "color_seed", integer(randomization, "drag_seed", 0)), integer(randomization, "color_context", 1)),
                EmitterDefinition.UvMode.valueOf(string(value, "uv_mode", "stretch").toUpperCase(Locale.ROOT)),
                number(value, "uv_distance", 1), integer(value, "atlas_columns", 1), integer(value, "atlas_rows", 1),
                scalar(value, "atlas_frame", 0), integer(value, "subdivisions", 1), integer(value, "width_segments", 1), bool(value, "attach_head", false),
                value.has("ribbon") && !value.get("ribbon").isJsonNull()
                        ? ribbon(value.getAsJsonObject("ribbon"), integer(value, "width_segments", 1)) : null,
                bool(value, "scale_size_with_owner", true), integer(value, "sort_order", 0),
                EmitterDefinition.SortMode.valueOf(string(value, "sort_mode", "distance").toUpperCase(Locale.ROOT)),
                integer(value, "atlas_row", -1), number(value, "macro_uv_radius", 0),
                value.has("sprite_cutout") && !value.get("sprite_cutout").isJsonNull()
                        ? spriteCutout(value.getAsJsonObject("sprite_cutout")) : null,
                render.mesh(), render.light(), render.decal(), render.volume(), render.splat(),
                bool(value, "cast_shadows", value.has("mesh") && value.get("mesh").isJsonObject()
                        ? bool(value.getAsJsonObject("mesh"), "cast_shadows", true) : true),
                value.has("rate_over_distance") && !value.get("rate_over_distance").isJsonNull()
                        ? scalar(value.get("rate_over_distance")) : null);
    }

    private static com.kltyton.lumenfx.effect.render.SpriteCutout spriteCutout(JsonObject value) {
        int vertices = value.get("vertices_per_frame").getAsInt();
        if (vertices != 4 && vertices != 8) throw new JsonParseException("Sprite cutout requires four or eight vertices");
        JsonArray frames = value.getAsJsonArray("frames");
        float[] coordinates = new float[Math.multiplyExact(frames.size(), vertices * 2)];
        int offset = 0;
        for (var frame : frames) {
            if (frame.getAsJsonArray().size() != vertices) throw new JsonParseException("Invalid sprite cutout frame vertex count");
            for (var point : frame.getAsJsonArray()) {
                if (point.getAsJsonArray().size() != 2) throw new JsonParseException("Sprite cutout vertices require two coordinates");
                coordinates[offset++] = point.getAsJsonArray().get(0).getAsFloat();
                coordinates[offset++] = point.getAsJsonArray().get(1).getAsFloat();
            }
        }
        return new com.kltyton.lumenfx.effect.render.SpriteCutout(vertices, coordinates, bool(value, "custom", false));
    }

    private static RibbonSettings ribbon(JsonObject value, int legacyWidthSegments) {
        var binding = value.get("shape_interface");
        var key = binding == null || binding.isJsonNull() ? null : binding.getAsJsonObject();
        return new RibbonSettings(number(value, "tension", 0), number(value, "minimum_segment_length", .01),
                integer(value, "maximum_tessellation", 16), number(value, "tessellation_angle", Math.PI / 12),
                bool(value, "constant_tessellation", false), bool(value, "screen_space", true),
                ribbonUv(value.has("uv0") ? value.getAsJsonObject("uv0") : new JsonObject()),
                ribbonUv(value.has("uv1") ? value.getAsJsonObject("uv1") : new JsonObject()), ribbonShape(value, legacyWidthSegments),
                RibbonSettings.TessellationMode.valueOf(string(value, "tessellation_mode", "Automatic").toUpperCase(Locale.ROOT)),
                switch (string(value, "draw_direction", "FrontToBack")) {
                    case "FrontToBack" -> RibbonSettings.DrawDirection.FRONT_TO_BACK;
                    case "BackToFront" -> RibbonSettings.DrawDirection.BACK_TO_FRONT;
                    default -> throw new IllegalArgumentException("Unknown ribbon draw direction");
                }, key == null ? null : new Key(string(key, "name", null), string(key, "type", null),
                        integer(key, "underlying", 1), integer(key, "flags", 0), Storage.INTERFACE),
                bool(value, "gpu_initialization", false));
    }

    private static RibbonSettings.Shape ribbonShape(JsonObject value, int legacyWidthSegments) {
        RibbonSettings.ShapeMode mode = switch (string(value, "shape", "Plane")) {
            case "Plane" -> RibbonSettings.ShapeMode.PLANE;
            case "MultiPlane" -> RibbonSettings.ShapeMode.MULTI_PLANE;
            case "Tube" -> RibbonSettings.ShapeMode.TUBE;
            case "Custom" -> RibbonSettings.ShapeMode.CUSTOM;
            default -> throw new IllegalArgumentException("Unknown Niagara ribbon shape");
        };
        RibbonSettings.Facing facing = switch (string(value, "facing", "Screen")) {
            case "Screen" -> RibbonSettings.Facing.SCREEN;
            case "Custom" -> RibbonSettings.Facing.CUSTOM;
            case "CustomSideVector" -> RibbonSettings.Facing.CUSTOM_SIDE_VECTOR;
            default -> throw new IllegalArgumentException("Unknown Niagara ribbon facing");
        };
        List<RibbonSettings.ShapeVertex> vertices = new ArrayList<>();
        if (value.has("custom_vertices")) for (var element : value.getAsJsonArray("custom_vertices")) {
            JsonObject vertex = element.getAsJsonObject();
            vertices.add(new RibbonSettings.ShapeVertex((float) number(vertex, "x", 0), (float) number(vertex, "y", 0),
                    (float) number(vertex, "normal_x", 0), (float) number(vertex, "normal_y", 0),
                    (float) number(vertex, "texture_v", 0)));
        }
        return new RibbonSettings.Shape(mode, facing, integer(value, "width_segments", legacyWidthSegments), integer(value, "plane_count", 2),
                integer(value, "tube_subdivisions", 3), bool(value, "accurate_geometry", false),
                bool(value, "material_backface_culling", false), vertices, bool(value, "use_geometry_normals", true),
                bool(value, "custom_shape_closed", true), false);
    }

    private static RibbonSettings.Uv ribbonUv(JsonObject value) {
        return new RibbonSettings.Uv(RibbonSettings.Distribution.valueOf(string(value, "distribution", "length").toUpperCase(Locale.ROOT)),
                number(value, "tile_length", 1), number(value, "scale_u", 1), number(value, "scale_v", 1),
                number(value, "offset_u", 0), number(value, "offset_v", 0),
                ribbonEdge(string(value, "leading_edge", "Locked")), ribbonEdge(string(value, "trailing_edge", "Locked")),
                bool(value, "u_override", false), bool(value, "v_override", false));
    }

    private static RibbonSettings.Edge ribbonEdge(String value) {
        return switch (value) {
            case "SmoothTransition" -> RibbonSettings.Edge.SMOOTH_TRANSITION;
            case "Locked" -> RibbonSettings.Edge.LOCKED;
            default -> throw new IllegalArgumentException("Unknown Niagara ribbon UV edge mode");
        };
    }
}
