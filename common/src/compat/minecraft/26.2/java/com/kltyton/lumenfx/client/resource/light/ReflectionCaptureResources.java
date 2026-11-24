package com.kltyton.lumenfx.client.resource.light;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.effect.render.light.scene.LocalReflectionCaptures;
import com.kltyton.lumenfx.effect.render.light.scene.LocalReflectionCaptures.Box;
import com.kltyton.lumenfx.effect.render.light.scene.LocalReflectionCaptures.Capture;
import com.kltyton.lumenfx.effect.render.light.scene.LocalReflectionCaptures.Plane;
import com.kltyton.lumenfx.effect.render.light.scene.LocalReflectionCaptures.Sphere;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;

/** Prepares the built cubemap face layers in the source proxy sort order. */
final class ReflectionCaptureResources {
    private ReflectionCaptureResources() { }
    private record Prepared(PreparedTexture faces, PreparedTexture preintegrated) { }

    static void prepare(ResourceManager resources, JsonObject lighting, Map<Identifier, PreparedTexture> arrays) throws IOException {
        var source = lighting.get("reflection_captures");
        if (source == null || source.isJsonNull()) return;
        if (source.isJsonPrimitive()) {
            Identifier manifest = Identifier.parse(source.getAsString());
            try (var reader = resources.getResourceOrThrow(manifest).openAsReader()) {
                var captures = JsonParser.parseReader(reader).getAsJsonObject();
                var arrayId = Identifier.fromNamespaceAndPath(manifest.getNamespace(), manifest.getPath() + ".array");
                captures.addProperty("texture_array", arrayId.toString());
                lighting.add("reflection_captures", captures);
                source = captures;
            }
        }
        if (!source.isJsonObject()) return;
        var definition = source.getAsJsonObject();
        var settings = read(definition);
        if (!arrays.containsKey(settings.textureArray())) {
            var textures = loadFaces(resources, definition, settings);
            arrays.put(textures.faces().id(), textures.faces());
            arrays.put(textures.preintegrated().id(), textures.preintegrated());
        }
    }

    private static Prepared loadFaces(ResourceManager resources, JsonObject definition, LocalReflectionCaptures settings) {
        var cubes = new ArrayList<PreparedTexture>(settings.captures().size());
        var identifiers = new HashSet<Identifier>();
        PreparedTexture gf = null;
        for (var value : definition.getAsJsonArray("captures")) {
            Identifier id = Identifier.parse(value.getAsJsonObject().get("texture").getAsString());
            if (!identifiers.add(id)) throw new JsonParseException("Repeated reflection cubemap " + id);
            var loaded = SkyLightReflectionResources.read(resources, id);
            var cube = loaded.cube();
            if (gf == null) gf = loaded.preintegrated();
            if (cube.mips().size() != settings.mipCount())
                throw new JsonParseException("Reflection capture mip count disagrees with " + id);
            cubes.add(cube);
        }
        if (gf == null) throw new JsonParseException("Reflection captures require a native preintegrated GF " + settings.textureArray());
        var first = cubes.getFirst();
        var mips = new ArrayList<ByteBuffer>(settings.mipCount());
        for (int mip = 0; mip < settings.mipCount(); mip++) {
            int cubeBytes = first.mips().get(mip).remaining();
            var array = ByteBuffer.allocateDirect(Math.multiplyExact(cubeBytes, cubes.size()));
            for (var cube : cubes) {
                var level = cube.mips().get(mip);
                if (level.remaining() != cubeBytes)
                    throw new JsonParseException("Reflection capture array extents disagree for " + cube.id());
                array.put(level.duplicate());
            }
            mips.add(array.flip().asReadOnlyBuffer());
        }
        return new Prepared(new PreparedTexture(settings.textureArray(), first.metadata(), mips, Math.multiplyExact(6, cubes.size())),
                new PreparedTexture(settings.preintegratedTexture(), gf.metadata(), gf.mips()));
    }

    static LocalReflectionCaptures read(JsonObject definition) {
        var captures = new ArrayList<Capture>();
        for (var value : definition.getAsJsonArray("captures")) {
            var capture = value.getAsJsonObject();
            var shape = switch (capture.get("shape").getAsString()) {
                case "sphere" -> new Sphere();
                case "box" -> {
                    var matrix = array(capture, "box_transform", 9);
                    yield new Box(vector(matrix, 0), vector(matrix, 3), vector(matrix, 6),
                            vector(array(capture, "box_scales", 3), 0), capture.get("box_transition").getAsFloat());
                }
                case "plane" -> {
                    var plane = array(capture, "plane", 4);
                    var axis = array(capture, "plane_axis", 4);
                    yield new Plane(vector(plane, 0), plane.get(3).getAsFloat(), vector(axis, 0), axis.get(3).getAsFloat());
                }
                default -> throw new JsonParseException("Unknown source reflection capture shape " + capture.get("shape"));
            };
            captures.add(new Capture(vector(array(capture, "position", 3), 0), capture.get("radius").getAsFloat(),
                    vector(array(capture, "capture_offset", 3), 0), capture.get("brightness").getAsFloat(),
                    capture.get("average_brightness").getAsFloat(), shape,
                    capture.has("registration_index") ? capture.get("registration_index").getAsInt() : -1));
        }
        return new LocalReflectionCaptures(Identifier.parse(definition.get("texture_array").getAsString()),
                definition.get("mip_count").getAsInt(), vector(array(definition, "luminance_weights", 3), 0),
                definition.has("specular_color_scale") ? vector(array(definition, "specular_color_scale", 3), 0) : new Vec3(1, 1, 1),
                captures);
    }

    private static JsonArray array(JsonObject value, String key, int size) {
        var array = value.getAsJsonArray(key);
        if (array == null || array.size() != size) throw new JsonParseException("Invalid reflection capture " + key);
        return array;
    }

    private static Vec3 vector(JsonArray array, int first) {
        return new Vec3(array.get(first).getAsDouble(), array.get(first + 1).getAsDouble(), array.get(first + 2).getAsDouble());
    }
}
