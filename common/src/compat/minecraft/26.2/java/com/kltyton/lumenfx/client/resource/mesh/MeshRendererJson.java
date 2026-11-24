package com.kltyton.lumenfx.client.resource.mesh;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.client.resource.niagara.ScriptParameterStoreJson;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteRendererSettings;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class MeshRendererJson {
    private MeshRendererJson() { }

    public static MeshRendererSettings read(JsonObject root, Function<Identifier, StaticMeshAsset> assets) {
        return read(root, assets, Map.of());
    }

    public static MeshRendererSettings read(JsonObject root, Function<Identifier, StaticMeshAsset> assets,
                                            Map<String, MaterialDefinition> definitions) {
        var entries = entries(root.getAsJsonArray("entries"), assets, definitions);
        MeshRendererSettings.ArrayBinding array = null;
        if (root.get("mesh_array") instanceof JsonObject source) {
            var values = new LinkedHashMap<String, List<MeshRendererSettings.Entry>>();
            for (var entry : source.getAsJsonObject("values").entrySet())
                values.put(SourceNames.canonical(entry.getKey()), entries(entry.getValue().getAsJsonArray(), assets, definitions));
            array = new MeshRendererSettings.ArrayBinding(ScriptParameterStoreJson.key(source.getAsJsonObject("slot")), values);
        }
        var overrides = new ArrayList<MeshRendererSettings.MaterialBinding>();
        if (root.has("override_materials")) for (JsonElement value : root.getAsJsonArray("override_materials")) overrides.add(binding(value.getAsJsonObject()));
        Map<String, String> remap = new LinkedHashMap<>();
        if (root.has("material_remap")) for (var value : root.getAsJsonObject("material_remap").entrySet()) remap.put(value.getKey(), value.getValue().getAsString());
        Map<String, String> dynamicMaterials = new LinkedHashMap<>();
        if (root.has("dynamic_materials")) for (var value : root.getAsJsonObject("dynamic_materials").entrySet())
            dynamicMaterials.put(SourceNames.canonical(value.getKey()), value.getValue().getAsString());
        return new MeshRendererSettings(entries, MeshRendererSettings.Facing.valueOf(enumName(root, "facing_mode")),
                root.get("locked_axis_enabled").getAsBoolean(), vector(root.get("locked_axis")),
                MeshRendererSettings.AxisSpace.valueOf(enumName(root, "locked_axis_space")), vector(root.get("bounds_scale")),
                root.get("frustum_culling").getAsBoolean(), root.get("cast_shadows").getAsBoolean(), nullable(root, "meshes_binding"), overrides, remap,
                root.has("scene_transforms") ? MeshRendererSettings.SceneTransforms.valueOf(enumName(root, "scene_transforms"))
                        : MeshRendererSettings.SceneTransforms.LEGACY, dynamicMaterials,
                root.has("material_variant") ? root.get("material_variant").getAsString() : "mesh", array,
                root.get("native_nanite") instanceof JsonObject nanite ? nanite(nanite) : null);
    }

    private static NaniteRendererSettings nanite(JsonObject source) {
        var bindings = new ArrayList<NaniteRendererSettings.InstanceFloat>();
        for (var value : source.getAsJsonArray("instance_floats")) {
            var binding = value.getAsJsonObject();
            var parameter = binding.get("parameter") instanceof JsonObject key ? ScriptParameterStoreJson.key(key) : null;
            bindings.add(new NaniteRendererSettings.InstanceFloat(parameter, binding.get("component").getAsInt(),
                    binding.get("default").getAsFloat()));
        }
        var meshParameters = new LinkedHashMap<Integer, Key>();
        if (source.get("mesh_parameters") instanceof JsonObject parameters)
            for (var entry : parameters.entrySet())
                meshParameters.put(Integer.parseInt(entry.getKey()), ScriptParameterStoreJson.key(entry.getValue().getAsJsonObject()));
        return new NaniteRendererSettings(bindings, meshParameters);
    }

    private static List<MeshRendererSettings.Entry> entries(JsonArray source, Function<Identifier, StaticMeshAsset> assets,
                                                             Map<String, MaterialDefinition> definitions) {
        var entries = new ArrayList<MeshRendererSettings.Entry>(source.size());
        for (JsonElement element : source) {
            JsonObject value = element.getAsJsonObject();
            var materials = materialBindings(value);
            var rotation = value.getAsJsonArray("rotation");
            if (rotation.size() != 4) throw new JsonParseException("Mesh rotation must have four components");
            StaticMeshAsset resolved = asset(value, assets, definitions, materials);
            entries.add(new MeshRendererSettings.Entry(resolved,
                    nullable(value, "asset_binding"), materials, vector(value.get("scale")),
                    new MeshRendererSettings.Rotation(rotation.get(0).getAsFloat(), rotation.get(1).getAsFloat(),
                            rotation.get(2).getAsFloat(), rotation.get(3).getAsFloat()), vector(value.get("pivot")),
                    MeshRendererSettings.PivotSpace.valueOf(enumName(value, "pivot_space")),
                    MeshRendererSettings.LodMode.valueOf(enumName(value, "lod_mode")),
                    integerBinding(value.getAsJsonObject("lod_level")), integerBinding(value.getAsJsonObject("lod_bias")),
                    value.get("lod_distance_factor").getAsFloat(), value.get("use_lod_range").getAsBoolean(),
                    value.get("lod_range_min").getAsInt(), value.get("lod_range_max").getAsInt()));
        }
        return List.copyOf(entries);
    }

    private static MeshRendererSettings.MaterialBinding binding(JsonObject value) {
        return new MeshRendererSettings.MaterialBinding(nullable(value, "material"), nullable(value, "user_parameter"), nullable(value, "default_material"),
                value.has("material_variant") ? value.get("material_variant").getAsString() : "mesh");
    }

    public static @Nullable StaticMeshAsset asset(JsonObject value, Function<Identifier, StaticMeshAsset> assets,
                                                   Map<String, MaterialDefinition> definitions) {
        return asset(value, assets, definitions, materialBindings(value));
    }

    private static ArrayList<MeshRendererSettings.MaterialBinding> materialBindings(JsonObject value) {
        var result = new ArrayList<MeshRendererSettings.MaterialBinding>();
        for (JsonElement material : value.getAsJsonArray("materials")) result.add(binding(material.getAsJsonObject()));
        return result;
    }

    private static @Nullable StaticMeshAsset asset(JsonObject value, Function<Identifier, StaticMeshAsset> assets,
            Map<String, MaterialDefinition> definitions, java.util.List<MeshRendererSettings.MaterialBinding> materials) {
        String name = nullable(value, "asset");
        StaticMeshAsset resolved = name == null ? null : assets.apply(Identifier.parse(name));
        if (resolved == null || definitions.isEmpty()) return resolved;
        var slots = new ArrayList<StaticMeshAsset.MaterialSlot>();
        for (int index = 0; index < resolved.materials().size(); index++) {
            var slot = resolved.materials().get(index);
            String materialName = index < materials.size() ? materials.get(index).defaultMaterial() : null;
            MaterialDefinition material = materialName == null ? slot.material() : definitions.get(materialName);
            if (materialName != null && material == null) throw new JsonParseException("Unknown mesh default material " + materialName);
            slots.add(new StaticMeshAsset.MaterialSlot(slot.name(), slot.importedName(), material));
        }
        String fallbackName = nullable(value, "fallback_material");
        MaterialDefinition fallback = fallbackName == null ? resolved.fallbackMaterial() : definitions.get(fallbackName);
        if (fallbackName != null && fallback == null) throw new JsonParseException("Unknown mesh fallback material " + fallbackName);
        return new StaticMeshAsset(slots, resolved.lods(), resolved.minimumLod(), resolved.overrideMinimumLod(),
                resolved.lodGroup(), resolved.automaticLodScreenSizes(), resolved.renderBounds(), resolved.extendedBounds(),
                fallback, resolved.qualityMinimumLod(), resolved.sampling(), resolved.streaming(), resolved.nanite());
    }

    private static MeshRendererSettings.IntegerBinding integerBinding(JsonObject value) {
        return new MeshRendererSettings.IntegerBinding(nullable(value, "name"), value.get("value").getAsInt());
    }
    private static @Nullable String nullable(JsonObject value, String key) {
        return !value.has(key) || value.get(key).isJsonNull() ? null : value.get(key).getAsString();
    }
    private static String enumName(JsonObject value, String key) { return value.get(key).getAsString().toUpperCase(Locale.ROOT); }
    private static Vec3 vector(JsonElement element) {
        var value = element.getAsJsonArray();
        if (value.size() != 3) throw new JsonParseException("Mesh transform vector must have three components");
        return new Vec3(value.get(0).getAsDouble(), value.get(1).getAsDouble(), value.get(2).getAsDouble());
    }
}
