package com.kltyton.lumenfx.effect.asset.mesh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.effect.asset.mesh.nanite.NaniteMeshAssetLoader;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry.Section;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry.SectionInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class StaticMeshAssetLoader {
    private StaticMeshAssetLoader() { }

    public static StaticMeshAsset load(ResourceProvider resources, Identifier id, Function<Identifier, StaticMeshGeometry> geometry) {
        return load(resources, id, geometry, null);
    }

    record StreamedLod(Identifier path, Resource payload, int vertexCount, List<Section> sections) {
        StaticMeshGeometry read() {
            var data = StaticMeshGeometryLoader.load(payload, path)
                    .withSectionInfo(sections.stream().map(section -> Objects.requireNonNull(section.info())).toList());
            if (data.vertexCount() != vertexCount || !data.sections().equals(sections))
                throw new JsonParseException("Streamed static mesh LOD metadata disagrees with " + path);
            return data;
        }
    }

    public static StaticMeshAsset load(ResourceProvider resources, Identifier id, Function<Identifier, StaticMeshGeometry> geometry,
                                       @Nullable StaticMeshStreaming streaming) {
        Identifier path = Identifier.fromNamespaceAndPath(id.getNamespace(), "lumenfx/meshes/" + id.getPath() + ".json");
        try (var reader = resources.getResourceOrThrow(path).openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("format_version").getAsInt() != 1 || !"render".equals(root.get("geometry_stage").getAsString()))
                throw new JsonParseException("Static mesh requires built render geometry: " + id);
            var materials = new ArrayList<StaticMeshAsset.MaterialSlot>();
            for (JsonElement element : root.getAsJsonArray("materials")) {
                JsonObject value = element.getAsJsonObject();
                materials.add(new StaticMeshAsset.MaterialSlot(value.get("slot").getAsString(), value.get("imported_slot").getAsString()));
            }
            int firstInline = root.has("first_inline_lod") ? root.get("first_inline_lod").getAsInt() : 0;
            int firstAvailable = root.has("first_available_lod") ? root.get("first_available_lod").getAsInt() : 0;
            int firstNonOptional = root.has("first_non_optional_lod") ? root.get("first_non_optional_lod").getAsInt()
                    : Math.min(firstAvailable, firstInline);
            if (firstNonOptional < Math.min(firstAvailable, firstInline) || firstNonOptional > firstInline)
                throw new JsonParseException("Invalid optional static mesh LOD range " + id);
            var lods = new ArrayList<StaticMeshAsset.Lod>();
            var deferred = new LinkedHashMap<Integer, StreamedLod>();
            int firstPackaged = firstAvailable;
            for (JsonElement element : root.getAsJsonArray("lods")) {
                JsonObject value = element.getAsJsonObject(), bounds = value.getAsJsonObject("bounds");
                JsonObject settings = value.getAsJsonObject("settings");
                Identifier meshId = Identifier.parse(value.get("geometry").getAsString());
                var lodBounds = new StaticMeshAsset.Bounds(vector(bounds.get("origin")), vector(bounds.get("extent")), bounds.get("radius").getAsFloat());
                var screen = floatSetting(value.has("screen_size") ? value.get("screen_size") : settings.get("ScreenSize"), 0);
                var cpuBufferAccess = value.has("cpu_buffer_access") ? cpuBufferAccess(value.getAsJsonObject("cpu_buffer_access")) : null;
                var gpuBufferPolicy = value.has("gpu_buffer_policy") ? gpuBufferPolicy(value.getAsJsonObject("gpu_buffer_policy")) : null;
                int lodIndex = lods.size();
                if (value.has("streamable_vertex_count")) {
                    int vertexCount = value.get("streamable_vertex_count").getAsInt();
                    if (lodIndex < firstAvailable || lodIndex >= firstInline || vertexCount <= 0)
                        throw new JsonParseException("Invalid streamed static mesh LOD " + meshId);
                    var sections = new ArrayList<Section>();
                    for (var elementSection : value.getAsJsonArray("sections")) {
                        var section = elementSection.getAsJsonObject();
                        sections.add(new Section(section.get("polygon_group").getAsInt(), section.get("material_slot").getAsString(),
                                section.get("first_index").getAsInt(), section.get("index_count").getAsInt(), sectionInfo(section)));
                    }
                    var payload = resources.getResource(meshId);
                    if (payload.isEmpty()) {
                        if (lodIndex >= firstNonOptional) throw new JsonParseException("Missing required static mesh LOD " + meshId);
                        firstPackaged = lodIndex + 1;
                        lods.add(new StaticMeshAsset.Lod(null, lodBounds, screen, false, 0, List.of(), cpuBufferAccess, gpuBufferPolicy));
                        continue;
                    }
                    if (streaming != null) {
                        deferred.put(lodIndex, new StreamedLod(meshId, payload.get(), vertexCount, List.copyOf(sections)));
                        lods.add(new StaticMeshAsset.Lod(null, lodBounds, screen, bool(value, "has_vertex_colors", true),
                                vertexCount, sections, cpuBufferAccess, gpuBufferPolicy));
                        continue;
                    }
                }
                StaticMeshGeometry mesh = geometry.apply(meshId);
                if (value.has("sections")) {
                    var metadata = new ArrayList<SectionInfo>();
                    for (JsonElement section : value.getAsJsonArray("sections")) metadata.add(sectionInfo(section.getAsJsonObject()));
                    mesh = mesh.withSectionInfo(metadata);
                }
                lods.add(new StaticMeshAsset.Lod(mesh, lodBounds, screen,
                        bool(value, "has_vertex_colors", mesh.vertexCount() != 0), mesh.vertexCount(), mesh.sections(),
                        cpuBufferAccess, gpuBufferPolicy));
            }
            JsonObject settings = root.getAsJsonObject("settings");
            var shaderAccess = new LinkedHashMap<String, StaticMeshAsset.ShaderAccess>();
            if (root.has("shader_access")) for (var entry : root.getAsJsonObject("shader_access").entrySet()) {
                var access = entry.getValue().getAsJsonObject();
                shaderAccess.put(entry.getKey(), new StaticMeshAsset.ShaderAccess(access.get("default_views").getAsBoolean(),
                        access.get("fallback_available").getAsBoolean()));
            }
            var sockets = new ArrayList<StaticMeshAsset.Socket>();
            if (root.has("sockets")) for (var item : root.getAsJsonArray("sockets")) {
                var socket = item.getAsJsonObject();
                sockets.add(new StaticMeshAsset.Socket(socket.get("name").getAsString(), vector(socket.get("location")),
                        vector(socket.get("rotation")), vector(socket.get("scale"))));
            }
            return new StaticMeshAsset(materials, lods, intSetting(settings.get("MinLOD"), 0),
                    bool(settings, "bOverrideMinLOD", false), text(settings, "LODGroup", "None"),
                    bool(settings, "bAutoComputeLODScreenSize", true),
                    root.has("render_bounds") ? bounds(root.getAsJsonObject("render_bounds")) : lods.getFirst().bounds(),
                    root.has("extended_bounds") ? bounds(root.getAsJsonObject("extended_bounds")) : lods.getFirst().bounds(), null,
                    qualitySetting(settings.get("MinQualityLevelLOD")),
                    new StaticMeshAsset.Sampling(sockets, bool(settings, "bAllowCPUAccess", false),
                            bool(settings, "bSupportUniformlyDistributedSampling", false),
                            bool(settings, "bSupportGpuUniformlyDistributedSampling", false),
                            root.has("distance_field") ? StaticMeshDistanceFieldLoader.load(resources,
                                    Identifier.parse(root.get("distance_field").getAsString())) : null,
                            bool(root, "nanite_fallback_stripped", false), bool(root, "omits_default_mesh_buffer_views", false),
                            firstInline, firstPackaged, bool(root, "cooked_fallback_unavailable", false), shaderAccess),
                    deferred.isEmpty() || streaming == null ? null : streaming.register(id, lods.size(), firstInline, firstPackaged, firstNonOptional, deferred),
                    root.has("nanite") ? NaniteMeshAssetLoader.load(resources, Identifier.parse(root.get("nanite").getAsString())) : null);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot load static mesh " + id, error);
        }
    }

    private static SectionInfo sectionInfo(JsonObject info) {
        return new SectionInfo(info.get("material_index").getAsInt(), info.get("collision").getAsBoolean(),
                info.get("cast_shadow").getAsBoolean(), info.get("visible_in_ray_tracing").getAsBoolean(),
                info.get("affect_distance_field_lighting").getAsBoolean(), info.get("force_opaque").getAsBoolean());
    }

    private static StaticMeshAsset.CpuBufferAccess cpuBufferAccess(JsonObject value) {
        return new StaticMeshAsset.CpuBufferAccess(policyFlag(value, "indices"), policyFlag(value, "positions"),
                policyFlag(value, "tangents"), policyFlag(value, "texture_coordinates"), policyFlag(value, "colors"));
    }

    private static Map<String, StaticMeshAsset.ShaderBuffers> gpuBufferPolicy(JsonObject value) {
        var result = new LinkedHashMap<String, StaticMeshAsset.ShaderBuffers>();
        for (var entry : value.entrySet()) {
            var buffers = entry.getValue().getAsJsonObject();
            result.put(entry.getKey(), new StaticMeshAsset.ShaderBuffers(policyFlag(buffers, "indices"),
                    policyFlag(buffers, "positions"), policyFlag(buffers, "tangents"),
                    policyFlag(buffers, "texture_coordinates"), policyFlag(buffers, "colors")));
        }
        return result;
    }

    private static boolean policyFlag(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull())
            throw new JsonParseException("Missing static mesh buffer policy flag " + key);
        return object.get(key).getAsBoolean();
    }

    private static StaticMeshAsset.IntSetting intSetting(JsonElement value, int fallback) {
        if (value == null || value.isJsonNull()) return new StaticMeshAsset.IntSetting(fallback, Map.of());
        if (value.isJsonPrimitive()) return new StaticMeshAsset.IntSetting(value.getAsInt(), Map.of());
        JsonObject object = value.getAsJsonObject();
        Map<String, Integer> overrides = new LinkedHashMap<>();
        for (var entry : object.getAsJsonObject("overrides").entrySet()) overrides.put(entry.getKey(), entry.getValue().getAsInt());
        return new StaticMeshAsset.IntSetting(object.get("default").getAsInt(), overrides);
    }

    private static StaticMeshAsset.QualitySetting qualitySetting(JsonElement value) {
        if (value == null || value.isJsonNull()) return new StaticMeshAsset.QualitySetting(0, Map.of());
        if (value.isJsonPrimitive()) return new StaticMeshAsset.QualitySetting(value.getAsInt(), Map.of());
        JsonObject object = value.getAsJsonObject();
        Map<Integer, Integer> overrides = new LinkedHashMap<>();
        for (var entry : object.getAsJsonObject("overrides").entrySet()) overrides.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsInt());
        return new StaticMeshAsset.QualitySetting(object.get("default").getAsInt(), overrides);
    }

    private static StaticMeshAsset.FloatSetting floatSetting(JsonElement value, float fallback) {
        if (value == null || value.isJsonNull()) return new StaticMeshAsset.FloatSetting(fallback, Map.of());
        if (value.isJsonPrimitive()) return new StaticMeshAsset.FloatSetting(value.getAsFloat(), Map.of());
        JsonObject object = value.getAsJsonObject();
        Map<String, Float> overrides = new LinkedHashMap<>();
        for (var entry : object.getAsJsonObject("overrides").entrySet()) overrides.put(entry.getKey(), entry.getValue().getAsFloat());
        return new StaticMeshAsset.FloatSetting(object.get("default").getAsFloat(), overrides);
    }

    private static Vec3 vector(JsonElement element) {
        var values = element.getAsJsonArray();
        if (values.size() != 3) throw new JsonParseException("Static mesh vector must have three components");
        return new Vec3(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
    }
    private static StaticMeshAsset.Bounds bounds(JsonObject value) {
        return new StaticMeshAsset.Bounds(vector(value.get("origin")), vector(value.get("extent")), value.get("radius").getAsFloat());
    }
    private static boolean bool(JsonObject object, String key, boolean fallback) { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
    private static String text(JsonObject object, String key, String fallback) { return object.has(key) ? object.get(key).getAsString() : fallback; }
}
