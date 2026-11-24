package com.kltyton.lumenfx.client.resource.parameter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import com.kltyton.lumenfx.client.resource.mesh.MeshRendererJson;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

public final class RendererObjectsJson {
    private RendererObjectsJson() { }

    public static Map<String, RendererParameter> read(JsonObject root, Map<String, MaterialDefinition> materials,
                                                     Function<Identifier, StaticMeshAsset> meshes) {
        Map<String, RendererParameter> result = new LinkedHashMap<>();
        for (var entry : root.entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            RendererParameter parameter = switch (value.get("type").getAsString()) {
                case "mesh" -> new RendererParameter.Mesh(MeshRendererJson.asset(value, meshes, materials));
                case "material" -> {
                    String name = value.get("material").isJsonNull() ? null : value.get("material").getAsString();
                    MaterialDefinition material = name == null ? null : materials.get(name);
                    if (name != null && material == null) throw new JsonParseException("Unknown renderer object material " + name);
                    yield new RendererParameter.Material(material);
                }
                case "material_variants" -> {
                    var variants = new LinkedHashMap<String, RendererParameter.Material>();
                    for (var variant : value.getAsJsonObject("materials").entrySet()) {
                        String name = variant.getValue().isJsonNull() ? null : variant.getValue().getAsString();
                        MaterialDefinition material = name == null ? null : materials.get(name);
                        if (name != null && material == null) throw new JsonParseException("Unknown renderer object material " + name);
                        variants.put(variant.getKey(), new RendererParameter.Material(material));
                    }
                    yield new RendererParameter.MaterialVariants(variants);
                }
                default -> throw new JsonParseException("Unknown renderer object type " + value.get("type"));
            };
            result.put(entry.getKey(), parameter);
        }
        return Map.copyOf(result);
    }
}
