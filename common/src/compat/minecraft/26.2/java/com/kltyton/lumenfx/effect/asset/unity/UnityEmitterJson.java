package com.kltyton.lumenfx.effect.asset.unity;

import static com.kltyton.lumenfx.effect.asset.EffectJsonFields.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.effect.curve.ColorFunction.Color;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.unity.UnityBillboard;
import com.kltyton.lumenfx.effect.unity.UnityBillboard.ViewportSize;
import com.kltyton.lumenfx.effect.unity.UnityCollision;
import com.kltyton.lumenfx.effect.unity.UnityCustomData;
import com.kltyton.lumenfx.effect.unity.UnityEmitterTransform;
import com.kltyton.lumenfx.effect.unity.UnityInheritVelocity;
import com.kltyton.lumenfx.effect.unity.UnityLifetimeByEmitterSpeed;
import com.kltyton.lumenfx.effect.unity.UnityMeshEmission;
import com.kltyton.lumenfx.effect.unity.UnityMeshMotion;
import com.kltyton.lumenfx.effect.unity.UnityNoise;
import com.kltyton.lumenfx.effect.unity.UnityParticleLights;
import com.kltyton.lumenfx.effect.unity.UnityParticleTrail;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding;
import com.kltyton.lumenfx.effect.unity.UnityShapeSettings;
import com.kltyton.lumenfx.effect.unity.UnityShapeTexture;
import com.kltyton.lumenfx.effect.unity.UnitySpaceMotion;
import com.kltyton.lumenfx.effect.unity.UnitySpaceTransform;
import com.kltyton.lumenfx.effect.unity.UnitySpeedModules;
import com.kltyton.lumenfx.effect.unity.UnitySpeedRange;
import com.kltyton.lumenfx.effect.unity.UnitySubEmitter;
import com.kltyton.lumenfx.effect.unity.UnityTrailTexture;
import com.kltyton.lumenfx.effect.unity.UnityTrigger;
import com.kltyton.lumenfx.effect.unity.UnityVelocityLimit;
import com.lowdragmc.photon.client.gameobject.emitter.data.InheritVelocitySetting.Mode;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Mesh.Type;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.ShapeArcMode;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.jspecify.annotations.Nullable;

/** Reads the authored simulation and presentation bindings shared by Unity emitter hosts. */
public final class UnityEmitterJson {
    private UnityEmitterJson() { }

    public static UnityRendererBinding renderer(JsonObject renderer, Function<Identifier, StaticMeshAsset> meshes) {
        var trail = jsonObject(renderer, "particle_trail");
        var texture = jsonObject(renderer, "trail_texture");
        var velocity = jsonObject(renderer, "inherit_velocity");
        var limit = jsonObject(renderer, "velocity_limit");
        var motion = jsonObject(renderer, "space_motion");
        var orbit = jsonObject(motion, "orbit");
        var billboard = jsonObject(renderer, "billboard");
        var mesh = jsonObject(renderer, "mesh_motion");
        var startDepth = jsonObject(renderer, "start_depth");
        var sorting = jsonObject(renderer, "sorting");
        return new UnityRendererBinding(string(renderer, "source", null), color(renderer, "tint").sample(0, 0),
                trail == null ? null : new UnityParticleTrail(number(trail, "ratio", 1),
                        number(trail, "minimum_vertex_distance", 0), bool(trail, "world_space", false),
                        bool(trail, "die_with_particles", true), bool(trail, "size_affects_width", true),
                        bool(trail, "size_affects_lifetime", false), bool(trail, "inherit_color", true),
                        scalar(trail, "lifetime", 1), scalar(trail, "width_over_trail", 1),
                        color(trail, "color_over_trail"), color(trail, "color_over_lifetime"),
                        UnityParticleTrail.Mode.valueOf(string(trail, "mode", "per_particle").toUpperCase(Locale.ROOT)),
                        number(trail, "maximum_particle_delta_time", UnityParticleTrail.DEFAULT_MAXIMUM_PARTICLE_DELTA_TIME)),
                renderer.has("ribbon_count") && !renderer.get("ribbon_count").isJsonNull() ? integer(renderer, "ribbon_count", 0) : null,
                texture == null ? null : new UnityTrailTexture(
                        UnityTrailTexture.Mode.valueOf(string(texture, "mode", "stretch").toUpperCase(Locale.ROOT)),
                        number(texture, "scale_u", 1), number(texture, "scale_v", 1),
                        number(texture, "offset_u", 0), number(texture, "offset_v", 0)),
                velocity == null ? null : new UnityInheritVelocity(
                        Mode.valueOf(string(velocity, "mode", "initial").toUpperCase(Locale.ROOT)),
                        UnityInheritVelocity.Source.valueOf(string(velocity, "source", "transform").toUpperCase(Locale.ROOT)),
                        scalar(velocity, "multiplier", 1)),
                limit == null ? null : new UnityVelocityLimit(
                        limit.has("axes") ? components(limit, "axes", 3) : List.of(scalar(limit, "speed", 0)),
                        bool(limit, "local_space", false), number(limit, "dampen", 0), scalar(limit, "drag", 0),
                        bool(limit, "multiply_by_size", false), bool(limit, "multiply_by_velocity", false),
                        unitySpaceTransform(jsonObject(limit, "space_transform"))),
                motion == null ? null : new UnitySpaceMotion(
                        motion.has("velocity") ? components(motion, "velocity", 3) : List.of(),
                        motion.has("force") ? components(motion, "force", 3) : List.of(),
                        motion.has("speed_modifier") ? scalar(motion, "speed_modifier", 1) : null,
                        orbit == null ? null : new UnitySpaceMotion.Orbit(components(orbit, "angular", 3),
                                components(orbit, "offset", 3), scalar(orbit, "radial", 0)),
                        unitySpaceTransform(jsonObject(motion, "velocity_space")),
                        unitySpaceTransform(jsonObject(motion, "force_space")),
                        bool(motion, "randomized_force", false)),
                billboard == null ? null : new UnityBillboard(
                        ParticleRendererSetting.Mode.valueOf(string(billboard, "mode", "Billboard")),
                        UnityBillboard.Alignment.valueOf(string(billboard, "alignment", "view").toUpperCase(Locale.ROOT)),
                        UnityBillboard.Order.valueOf(string(billboard, "order", "none").toUpperCase(Locale.ROOT)),
                        (float) number(billboard, "pivot_x", 0), (float) number(billboard, "pivot_y", 0),
                        (float) number(billboard, "length_scale", 1), (float) number(billboard, "velocity_scale", 0),
                        bool(billboard, "freeform_stretching", false), bool(billboard, "rotate_with_stretch", true),
                        number(billboard, "flip_x", 0), number(billboard, "flip_y", 0),
                        (float) number(billboard, "camera_velocity_scale", 0),
                        billboard.has("start_rotation_xy") ? components(billboard, "start_rotation_xy", 2) : List.of(),
                        billboard.has("angular_velocity_xy") ? components(billboard, "angular_velocity_xy", 2) : List.of(),
                        bool(billboard, "allow_roll", true), billboardSizeLimits(jsonObject(billboard, "viewport_size"))),
                mesh == null ? null : new UnityMeshMotion(scalar(mesh, "depth", 1),
                        mesh.has("start_rotation_xy") ? components(mesh, "start_rotation_xy", 2) : List.of(),
                        mesh.has("angular_velocity_xy") ? components(mesh, "angular_velocity_xy", 2) : List.of(),
                        UnityBillboard.Alignment.valueOf(string(mesh, "alignment", "world").toUpperCase(Locale.ROOT)),
                        UnityBillboard.Order.valueOf(string(mesh, "order", "none").toUpperCase(Locale.ROOT)),
                        bool(mesh, "forward_to_up", false),
                        (float) number(mesh, "scale_u", 1), (float) number(mesh, "scale_v", 1),
                        (float) number(mesh, "offset_u", 0), (float) number(mesh, "offset_v", 0), vector(mesh, "flip", Vec3.ZERO),
                        mesh.has("weights") ? mesh.getAsJsonArray("weights").asList().stream().map(element -> element.getAsFloat()).toList() : List.of()),
                unityCollision(jsonObject(renderer, "plane_collision"), jsonObject(renderer, "world_collision")),
                UnityRendererBinding.AtlasRowSelection.valueOf(string(renderer, "atlas_row_selection", "default").toUpperCase(Locale.ROOT)),
                renderer.has("atlas_fps") && !renderer.get("atlas_fps").isJsonNull() ? number(renderer, "atlas_fps", 0) : null,
                unityAtlasSpeed(jsonObject(renderer, "atlas_speed")), unitySpriteFrames(jsonArray(renderer, "sprite_frames")),
                bool(renderer, "prewarm", false), unitySpeedModules(jsonObject(renderer, "speed_modules")),
                unityLifetimeByEmitterSpeed(jsonObject(renderer, "lifetime_by_emitter_speed")),
                unitySizeOverLifetimeAxes(renderer, mesh), unitySubEmitters(jsonArray(renderer, "sub_emitters")),
                unityTrigger(jsonObject(renderer, "trigger"), meshes), unityLights(jsonObject(renderer, "lights")),
                unityCustomData(jsonObject(renderer, "custom_data")), unityNoise(jsonObject(renderer, "noise")),
                UnityForceFieldJson.read(jsonObject(renderer, "external_forces")),
                unityEmitterTransform(jsonObject(renderer, "transform")), number(renderer, "simulation_speed", 1),
                number(renderer, "flip_rotation", 0),
                renderer.has("start_delay_maximum") && !renderer.get("start_delay_maximum").isJsonNull() ? number(renderer, "start_delay_maximum", 0) : null,
                renderer.has("random_seed") && !renderer.get("random_seed").isJsonNull() ? renderer.get("random_seed").getAsBigDecimal().longValueExact() : null,
                bool(renderer, "pause_until_replaced", false), unityLifetimeLoop(jsonArray(renderer, "lifetime_loop")),
                unityEmitterTransform(jsonObject(renderer, "emission_transform")),
                renderer.has("uv_channel_mask") && !renderer.get("uv_channel_mask").isJsonNull()
                        ? renderer.get("uv_channel_mask").getAsInt() : 1,
                startDepth == null ? null : new UnityRendererBinding.StartDepth(scalar(startDepth, "size", 1),
                        bool(startDepth, "forward_to_up", false)),
                unitySorting(sorting),
                renderer.has("maximum_particle_delta_time") && !renderer.get("maximum_particle_delta_time").isJsonNull()
                        ? number(renderer, "maximum_particle_delta_time", 0) : null);
    }

    private static UnityRendererBinding.@Nullable Sorting unitySorting(@Nullable JsonObject sorting) {
        if (sorting == null) return null;
        var groupArray = jsonArray(sorting, "groups");
        List<UnityRendererBinding.SortingGroup> groups = List.of();
        if (groupArray != null && !groupArray.isEmpty()) {
            groups = new ArrayList<>(groupArray.size());
            for (var element : groupArray) {
                var group = element.getAsJsonObject();
                groups.add(new UnityRendererBinding.SortingGroup(
                        group.get("id").getAsString(),
                        integer(group, "layer", 0),
                        integer(group, "order", 0),
                        vector(group, "position", Vec3.ZERO),
                        integer(group, "hierarchy_order", 0)));
            }
        }
        return new UnityRendererBinding.Sorting(
                integer(sorting, "layer", 0),
                integer(sorting, "order", 0),
                integer(sorting, "queue", 3000),
                number(sorting, "distance_bias", 0),
                groups,
                integer(sorting, "hierarchy_order", 0));
    }

    private static @Nullable UnitySpaceTransform unitySpaceTransform(@Nullable JsonObject value) {
        if (value == null) return null;
        boolean fixed = bool(value, "fixed", false);
        var rotation = value.getAsJsonArray("rotation");
        if (rotation == null) return new UnitySpaceTransform(fixed, new Quaterniond());
        if (rotation.size() != 4) throw new JsonParseException("Unity space transform needs four quaternion components");
        double x = rotation.get(0).getAsDouble();
        double y = rotation.get(1).getAsDouble();
        double z = rotation.get(2).getAsDouble();
        double w = rotation.get(3).getAsDouble();
        double norm = x * x + y * y + z * z + w * w;
        if (!Double.isFinite(norm) || norm <= 0)
            throw new JsonParseException("Unity space transform quaternion must be finite and non-zero");
        return new UnitySpaceTransform(fixed, new Quaterniond(x, y, z, w).normalize());
    }

    private static UnityRendererBinding.@Nullable LifetimeLoop unityLifetimeLoop(@Nullable JsonArray range) {
        if (range == null) return null;
        if (range.size() != 2) throw new JsonParseException("Unity particle lifetime loop needs two normalized endpoints");
        return new UnityRendererBinding.LifetimeLoop(range.get(0).getAsDouble(), range.get(1).getAsDouble());
    }

    private static @Nullable UnityEmitterTransform unityEmitterTransform(@Nullable JsonObject value) {
        if (value == null) return null;
        var rotation = value.getAsJsonArray("rotation");
        if (rotation == null || rotation.size() != 4) throw new JsonParseException("Unity emitter transform needs four quaternion components");
        return new UnityEmitterTransform(vector(value, "position", Vec3.ZERO), rotation.get(0).getAsFloat(),
                rotation.get(1).getAsFloat(), rotation.get(2).getAsFloat(), rotation.get(3).getAsFloat(),
                vector(value, "scale", new Vec3(1, 1, 1)), bool(value, "shape_only_scale", false));
    }

    public static @Nullable UnityShapeSettings shape(@Nullable JsonObject value, Function<Identifier, StaticMeshAsset> meshes,
                                                           Function<Identifier, UnityShapeTexture.Pixels> shapeTextures, Vec3 scale) {
        if (value == null) return null;
        var rotation = value.getAsJsonArray("rotation");
        if (rotation == null || rotation.size() != 4) throw new JsonParseException("Unity shape needs four quaternion components");
        var arc = value.getAsJsonObject("arc");
        var edge = value.getAsJsonObject("edge");
        var donut = value.getAsJsonObject("donut");
        var cone = value.getAsJsonObject("cone");
        var mesh = value.getAsJsonObject("mesh");
        var texture = value.getAsJsonObject("texture");
        return new UnityShapeSettings(vector(value, "position", Vec3.ZERO), rotation.get(0).getAsFloat(),
                rotation.get(1).getAsFloat(), rotation.get(2).getAsFloat(), rotation.get(3).getAsFloat(),
                number(value, "radius_thickness", 1), number(value, "random_direction", 0),
                number(value, "spherical_direction", 0), number(value, "random_position", 0),
                arc == null ? null : new UnityShapeSettings.Arc(number(arc, "radians", 0), unityShapeTraversal(arc)),
                edge == null ? null : unityShapeTraversal(edge),
                donut == null ? null : new UnityShapeSettings.Donut(number(donut, "ring", 0), number(donut, "tube", 0)),
                cone == null ? null : new UnityShapeSettings.Cone(number(cone, "radius", 0), number(cone, "length", 0)),
                mesh == null ? null : unityShapeMesh(mesh, meshes, shapeTextures, scale), vector(value, "box_thickness", Vec3.ZERO),
                bool(value, "align_to_direction", false), texture == null ? null
                        : new UnityShapeTexture.Projection(unityShapeTexture(texture, shapeTextures), bool(texture, "forward_to_up", false)));
    }

    private static UnityMeshEmission unityShapeMesh(JsonObject mesh, Function<Identifier, StaticMeshAsset> meshes,
                                                   Function<Identifier, UnityShapeTexture.Pixels> shapeTextures, Vec3 scale) {
        var id = Identifier.parse(mesh.get("asset").getAsString());
        var geometry = meshes.apply(id).lods().getFirst().geometry();
        if (geometry == null) throw new JsonParseException("Unity emission mesh needs resident source geometry: " + id);
        var texture = mesh.getAsJsonObject("texture");
        var edge = mesh.getAsJsonObject("edge_traversal");
        List<Color> sectionTints = new ArrayList<>();
        var sections = mesh.getAsJsonArray("section_tints");
        if (sections != null) for (var section : sections) {
            var rgba = section.getAsJsonArray();
            if (rgba.size() != 4) throw new JsonParseException("Unity emission material tint requires RGBA");
            sectionTints.add(new Color(rgba.get(0).getAsFloat(), rgba.get(1).getAsFloat(), rgba.get(2).getAsFloat(), rgba.get(3).getAsFloat()));
        }
        return new UnityMeshEmission(geometry, Type.valueOf(string(mesh, "placement", "Vertex")),
                ShapeArcMode.valueOf(string(mesh, "vertex_mode", "Random")),
                integer(mesh, "section", -1), number(mesh, "normal_offset", 0), bool(mesh, "use_vertex_color", false),
                scale, texture == null ? null : unityShapeTexture(texture, shapeTextures),
                edge == null ? null : unityShapeTraversal(edge), sectionTints,
                bool(mesh, "live_geometry", false) ? id : null);
    }

    private static UnityShapeTexture unityShapeTexture(JsonObject texture, Function<Identifier, UnityShapeTexture.Pixels> images) {
        return new UnityShapeTexture(images.apply(Identifier.parse(texture.get("asset").getAsString())),
                integer(texture, "channel", 3), (float)number(texture, "threshold", 0),
                bool(texture, "color", false), bool(texture, "alpha", false), bool(texture, "bilinear", false),
                UnityShapeTexture.Wrap.valueOf(string(texture, "wrap_u", "Clamp")),
                UnityShapeTexture.Wrap.valueOf(string(texture, "wrap_v", "Clamp")));
    }

    private static UnityShapeSettings.Traversal unityShapeTraversal(JsonObject value) {
        return new UnityShapeSettings.Traversal(ShapeArcMode.valueOf(string(value, "mode", "Random")),
                (float)number(value, "spread", 0), scalar(value, "speed", 1));
    }

    private static @Nullable UnityNoise unityNoise(@Nullable JsonObject value) {
        if (value == null) return null;
        return new UnityNoise(components(value, "strength", 3),
                value.has("remap") ? components(value, "remap", 3) : List.of(),
                number(value, "frequency", .5), bool(value, "damping", true), integer(value, "octaves", 1),
                number(value, "octave_multiplier", .5), number(value, "octave_scale", 2), integer(value, "quality", 3),
                scalar(value, "scroll_speed", 0), scalar(value, "position", 1), scalar(value, "rotation", 0),
                scalar(value, "size", 0), bool(value, "forward_to_up", false));
    }

    private static @Nullable UnityCustomData unityCustomData(@Nullable JsonObject value) {
        return value == null ? null : new UnityCustomData(unityCustomStream(value.getAsJsonObject("custom1")),
                unityCustomStream(value.getAsJsonObject("custom2")));
    }

    private static UnityCustomData.@Nullable Stream unityCustomStream(@Nullable JsonObject value) {
        if (value == null) return null;
        if (value.has("color") == value.has("vector"))
            throw new JsonParseException("Unity custom data stream requires exactly one color or vector");
        int width = integer(value, "width", 4);
        if (value.has("color")) return new UnityCustomData.Stream(List.of(), color(value, "color"), width);
        return new UnityCustomData.Stream(components(value, "vector", 4), null, width);
    }

    private static @Nullable ViewportSize billboardSizeLimits(@Nullable JsonObject limits) {
        return limits == null ? null : new ViewportSize(limits.get("minimum").getAsFloat(), limits.get("maximum").getAsFloat());
    }

    private static @Nullable UnityParticleLights unityLights(@Nullable JsonObject lights) {
        if (lights == null) return null;
        var profile = jsonObject(lights, "profile");
        var lightProfile = profile == null ? null : new UnityParticleLights.Profile(
                UnityParticleLights.Shape.valueOf(string(profile, "type", "point").toUpperCase(Locale.ROOT)),
                bool(profile, "forward_to_up", false),
                vector(profile, "forward", new Vec3(0, 0, -1)), vector(profile, "up", new Vec3(0, 1, 0)),
                (float)number(profile, "inner_angle", 0), (float)number(profile, "outer_angle", 30),
                UnityParticleLights.Shadows.valueOf(string(profile, "shadows", "none").toUpperCase(Locale.ROOT)),
                (float)number(profile, "shadow_strength", 1), (float)number(profile, "shadow_bias", .05),
                (float)number(profile, "shadow_normal_bias", .4), (float)number(profile, "shadow_near", .2),
                integer(profile, "shadow_resolution", -1), string(profile, "cookie_material", null));
        return new UnityParticleLights(number(lights, "ratio", 1), integer(lights, "maximum", 0),
                bool(lights, "random_distribution", true), vector(lights, "color", new Vec3(1, 1, 1)),
                number(lights, "radius", 1), number(lights, "intensity", 1), scalar(lights, "range", 1),
                scalar(lights, "intensity_over_life", 1), bool(lights, "use_particle_color", false),
                bool(lights, "size_affects_range", false), bool(lights, "alpha_affects_intensity", false), lightProfile);
    }

    private static @Nullable UnityTrigger unityTrigger(@Nullable JsonObject value, Function<Identifier, StaticMeshAsset> meshes) {
        if (value == null) return null;
        var colliders = new ArrayList<UnityTrigger.Collider>();
        for (var entry : value.getAsJsonArray("colliders")) {
            var collider = entry.getAsJsonObject();
            String name = collider.get("name").getAsString();
            var shape = UnityTrigger.Shape.valueOf(collider.get("shape").getAsString().toUpperCase(Locale.ROOT));
            if (shape == UnityTrigger.Shape.HOST) {
                colliders.add(UnityTrigger.Collider.host(name, string(collider, "host_key", name)));
                continue;
            }
            com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry triggerMesh = null;
            if (shape == UnityTrigger.Shape.MESH) {
                var meshId = Identifier.parse(collider.get("mesh").getAsString());
                triggerMesh = meshes.apply(meshId).lods().getFirst().geometry();
                if (triggerMesh == null) {
                    throw new JsonParseException("Unity trigger mesh needs resident source geometry: " + meshId);
                }
            }
            colliders.add(new UnityTrigger.Collider(name, shape,
                    vector(collider, "center", Vec3.ZERO), vector(collider, "x_axis", new Vec3(1, 0, 0)),
                    vector(collider, "y_axis", new Vec3(0, 1, 0)), vector(collider, "z_axis", new Vec3(0, 0, 1)),
                    vector(collider, "size", Vec3.ZERO),
                    collider.has("direction") ? collider.get("direction").getAsInt() : -1,
                    triggerMesh, bool(collider, "convex", false), ""));
        }
        return new UnityTrigger(colliders, number(value, "radius_scale", 1),
                UnityTrigger.Query.valueOf(string(value, "query", "disabled").toUpperCase(Locale.ROOT)),
                unityTriggerAction(value, "inside"), unityTriggerAction(value, "outside"),
                unityTriggerAction(value, "enter"), unityTriggerAction(value, "exit"));
    }

    private static UnityTrigger.Action unityTriggerAction(JsonObject value, String key) {
        return UnityTrigger.Action.valueOf(string(value, key, "ignore").toUpperCase(Locale.ROOT));
    }

    private static List<UnitySubEmitter> unitySubEmitters(@Nullable JsonArray array) {
        if (array == null) return List.of();
        var links = new ArrayList<UnitySubEmitter>(array.size());
        for (var entry : array) {
            var link = entry.getAsJsonObject();
            links.add(new UnitySubEmitter(link.get("target").getAsString(),
                    UnitySubEmitter.Event.valueOf(link.get("event").getAsString().toUpperCase(Locale.ROOT)),
                    number(link, "probability", 1), integer(link, "inherit", 0)));
        }
        return links;
    }

    private static List<ScalarFunction> unitySizeOverLifetimeAxes(JsonObject renderer, @Nullable JsonObject mesh) {
        if (jsonArray(renderer, "size_over_life_axes") != null) return components(renderer, "size_over_life_axes", 3);
        return jsonArray(mesh, "size_over_life") != null ? components(mesh, "size_over_life", 3) : List.of();
    }

    private static List<UnityRendererBinding.SpriteFrame> unitySpriteFrames(@Nullable JsonArray frames) {
        if (frames == null) return List.of();
        var result = new ArrayList<UnityRendererBinding.SpriteFrame>(frames.size());
        for (var value : frames) {
            var uv = value.getAsJsonArray();
            if (uv.size() != 4) throw new IllegalArgumentException("Unity sprite frame must contain four UV coordinates");
            result.add(new UnityRendererBinding.SpriteFrame(uv.get(0).getAsFloat(), uv.get(1).getAsFloat(),
                    uv.get(2).getAsFloat(), uv.get(3).getAsFloat()));
        }
        return result;
    }

    private static UnityRendererBinding.@Nullable AtlasSpeed unityAtlasSpeed(@Nullable JsonObject settings) {
        if (settings == null) return null;
        return new UnityRendererBinding.AtlasSpeed(unitySpeedRange(settings), integer(settings, "cycles", 1));
    }

    private static @Nullable UnitySpeedModules unitySpeedModules(@Nullable JsonObject settings) {
        if (settings == null) return null;
        var size = jsonObject(settings, "size");
        var color = jsonObject(settings, "color");
        var rotation = jsonObject(settings, "rotation");
        return new UnitySpeedModules(size == null ? null : new UnitySpeedModules.Size(unitySpeedRange(size),
                        jsonArray(size, "axes") != null ? components(size, "axes", 3) : List.of(scalar(size, "value", 1))),
                color == null ? null : new UnitySpeedModules.Color(unitySpeedRange(color), color(color, "value")),
                rotation == null ? null : new UnitySpeedModules.Rotation(unitySpeedRange(rotation),
                        jsonArray(rotation, "axes") != null ? components(rotation, "axes", 3) : List.of(scalar(rotation, "value", 0))));
    }

    private static UnitySpeedRange unitySpeedRange(JsonObject settings) {
        return new UnitySpeedRange(number(settings, "min", 0), number(settings, "max", 0));
    }

    private static @Nullable UnityLifetimeByEmitterSpeed unityLifetimeByEmitterSpeed(@Nullable JsonObject settings) {
        if (settings == null) return null;
        return new UnityLifetimeByEmitterSpeed(
                UnityInheritVelocity.Source.valueOf(string(settings, "source", "transform").toUpperCase(Locale.ROOT)),
                unitySpeedRange(settings), scalar(settings, "value", 1));
    }

    private static @Nullable UnityCollision unityCollision(@Nullable JsonObject plane, @Nullable JsonObject world) {
        if (plane != null && world != null)
            throw new IllegalArgumentException("Unity particle renderer cannot combine plane and world collision modes");
        var collision = plane == null ? world : plane;
        if (collision == null) return null;
        var planes = new ArrayList<UnityCollision.Plane>();
        if (plane != null) for (var item : plane.getAsJsonArray("planes")) {
            var value = item.getAsJsonObject();
            planes.add(new UnityCollision.Plane(vector(value, "point", Vec3.ZERO), vector(value, "normal", Vec3.ZERO),
                    integer(value, "slot", -1)));
        }
        UnityCollision.World worldSettings = null;
        if (world != null) {
            boolean mode2d = bool(world, "mode_2d", false);
            UnityCollision.Plane2D plane2d = UnityCollision.Plane2D.NONE;
            if (mode2d || world.has("plane_2d")) {
                if (world.has("plane_2d") && !world.get("plane_2d").isJsonNull()) {
                    plane2d = UnityCollision.Plane2D.valueOf(world.get("plane_2d").getAsString().toUpperCase(Locale.ROOT));
                } else if (bool(world, "forward_to_up", false)) {
                    plane2d = UnityCollision.Plane2D.XZ;
                } else {
                    plane2d = UnityCollision.Plane2D.XY;
                }
            }
            worldSettings = new UnityCollision.World(
                    bool(world, "dynamic_colliders", false), integer(world, "max_dynamic_shapes", 256), integer(world, "layer_mask", -1),
                    UnityCollision.Quality.valueOf(string(world, "quality", "high").toUpperCase(Locale.ROOT)),
                    plane2d,
                    number(world, "voxel_size", UnityCollision.World.DEFAULT_VOXEL_SIZE),
                    number(world, "collider_force", 0.0),
                    bool(world, "multiply_force_by_collision_angle", false),
                    bool(world, "multiply_force_by_particle_speed", false),
                    bool(world, "multiply_force_by_particle_size", false));
        }
        return new UnityCollision(planes, worldSettings, scalar(collision, "dampen", 0), scalar(collision, "bounce", 1),
                scalar(collision, "lifetime_loss", 0), number(collision, "minimum_speed", 0),
                number(collision, "maximum_speed", Float.MAX_VALUE), number(collision, "radius_scale", 1),
                bool(collision, "send_collision_messages", false));
    }
}
