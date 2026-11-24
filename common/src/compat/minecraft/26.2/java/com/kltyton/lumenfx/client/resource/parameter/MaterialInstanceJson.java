package com.kltyton.lumenfx.client.resource.parameter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.ParameterInfo;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import org.jspecify.annotations.Nullable;
import net.minecraft.resources.Identifier;

public final class MaterialInstanceJson {
    private MaterialInstanceJson() { }
    public static MaterialInstanceParameters read(@Nullable JsonArray array) {
        return read(array, null);
    }
    public static MaterialInstanceParameters read(@Nullable JsonArray array, @Nullable JsonArray textures) {
        return read(array, textures, null);
    }
    public static MaterialInstanceParameters read(@Nullable JsonArray array, @Nullable JsonArray textures, @Nullable JsonArray fontSamplers) {
        return read(array, textures, fontSamplers, null);
    }
    public static MaterialInstanceParameters read(@Nullable JsonArray array, @Nullable JsonArray textures,
                                                  @Nullable JsonArray fontSamplers, @Nullable JsonArray textureSamplers) {
        var slots = new ArrayList<MaterialInstanceParameters.Slot>();
        for (var element : array == null ? new JsonArray() : array) {
            JsonObject value = element.getAsJsonObject();
            var kind = MaterialInstanceParameters.Kind.valueOf(value.get("kind").getAsString().toUpperCase(Locale.ROOT));
            JsonArray initial = value.getAsJsonArray("default");
            int count = kind == MaterialInstanceParameters.Kind.DOUBLE_VECTOR ? 8 : 4;
            if (initial == null || initial.size() != count) throw new JsonParseException("Material parameter defaults do not match their kind");
            float[] defaults = new float[count];
            for (int i = 0; i < count; i++) defaults[i] = initial.get(i).getAsFloat();
            slots.add(new MaterialInstanceParameters.Slot(value.get("name").getAsString(),
                    kind,
                    string(value, "variable"),
                    MaterialInstanceParameters.Source.valueOf(value.get("type").getAsString().toUpperCase(Locale.ROOT)),
                    string(value, "data_interface"), string(value, "child"), defaults,
                    association(string(value, "association")), value.has("parameter_index") ? value.get("parameter_index").getAsInt() : -1,
                    string(value, "default_profile"),
                    value.has("renderer_constant") && value.get("renderer_constant").getAsBoolean()));
        }
        var images = new ArrayList<MaterialInstanceParameters.TextureSlot>();
        for (var element : textures == null ? new JsonArray() : textures) {
            JsonObject value = element.getAsJsonObject();
            String object = string(value, "object_default");
            images.add(new MaterialInstanceParameters.TextureSlot(value.get("name").getAsString(), value.get("sampler").getAsString(),
                    value.get("variable").getAsString(), string(value, "data_interface"), string(value, "child"),
                    Identifier.parse(value.get("base_default").getAsString()), object == null ? null : Identifier.parse(object),
                    value.has("static_exposure") && value.get("static_exposure").getAsBoolean(), string(value, "interface_id"),
                    value.has("interface_definition") && !value.get("interface_definition").isJsonNull()
                            ? value.getAsJsonObject("interface_definition") : null,
                    value.has("collection") && !value.get("collection").isJsonNull()
                            ? textureCollection(value.getAsJsonObject("collection")) : null,
                    objectInputs(value.getAsJsonArray("object_inputs")), string(value, "source_type"),
                    value.has("type_flags") ? value.get("type_flags").getAsInt() : 0));
        }
        var fonts = new LinkedHashMap<String, ParameterInfo>();
        var parentFonts = new LinkedHashMap<String, Identifier>();
        if (fontSamplers != null) for (var element : fontSamplers) {
            JsonObject value = element.getAsJsonObject();
            String sampler = value.get("sampler").getAsString();
            var parameter = new ParameterInfo(value.get("name").getAsString(), association(string(value, "association")),
                    value.get("parameter_index").getAsInt());
            if (fonts.putIfAbsent(sampler, parameter) != null)
                throw new JsonParseException("Duplicate material font sampler " + sampler);
            String parent = string(value, "parent_texture");
            if (parent != null) parentFonts.put(sampler, Identifier.parse(parent));
        }
        var dynamicTextures = new LinkedHashMap<String, ParameterInfo>();
        if (textureSamplers != null) for (var element : textureSamplers) {
            JsonObject value = element.getAsJsonObject();
            String sampler = value.get("sampler").getAsString();
            var parameter = new ParameterInfo(value.get("name").getAsString(), association(string(value, "association")),
                    value.get("parameter_index").getAsInt());
            if (dynamicTextures.putIfAbsent(sampler, parameter) != null)
                throw new JsonParseException("Duplicate material texture parameter sampler " + sampler);
        }
        return MaterialInstanceParameters.definition(slots, images, fonts, parentFonts, dynamicTextures);
    }
    private static List<MaterialInstanceParameters.TextureObjectInput> objectInputs(@Nullable JsonArray values) {
        var result = new ArrayList<MaterialInstanceParameters.TextureObjectInput>();
        for (var element : values == null ? new JsonArray() : values) {
            JsonObject value = element.getAsJsonObject();
            String texture = string(value, "default_texture");
            result.add(new MaterialInstanceParameters.TextureObjectInput(value.get("variable").getAsString(),
                    value.get("default_present").getAsBoolean(), texture == null ? null : Identifier.parse(texture)));
        }
        return List.copyOf(result);
    }
    private static MaterialInstanceParameters.TextureCollection textureCollection(JsonObject value) {
        String base = string(value, "base_default");
        return new MaterialInstanceParameters.TextureCollection(value.get("path").getAsString(),
                value.get("name").getAsString(), value.get("source_type").getAsString(),
                value.get("system_override").getAsBoolean(), value.get("base_present").getAsBoolean(),
                base == null ? null : Identifier.parse(base));
    }
    private static @Nullable String string(JsonObject value, String field) {
        return !value.has(field) || value.get(field).isJsonNull() ? null : value.get(field).getAsString();
    }
    public static MaterialInstanceParameters.Association association(@Nullable String value) {
        return switch (value == null ? "GlobalParameter" : value) {
            case "GlobalParameter" -> MaterialInstanceParameters.Association.GLOBAL;
            case "LayerParameter" -> MaterialInstanceParameters.Association.LAYER;
            case "BlendParameter" -> MaterialInstanceParameters.Association.BLEND;
            default -> throw new JsonParseException("Invalid material parameter association " + value);
        };
    }
}
