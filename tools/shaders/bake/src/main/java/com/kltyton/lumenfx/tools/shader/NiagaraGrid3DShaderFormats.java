package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class NiagaraGrid3DShaderFormats {
    static final String IMAGE_KIND = "niagara_grid3d_image";
    static final String ARRAY_IMAGE_KIND = "niagara_grid2d_image";
    static final String ATTRIBUTE_KIND = "niagara_grid3d_attributes";
    static final String SELECTOR = "grid3d_storage_formats";
    private static final List<String> SCALAR = List.of("PF_R8", "PF_R16F", "PF_R32_FLOAT", "PF_G32R32F", "PF_A32B32G32R32F");
    private static final List<String> PAIR = List.of("PF_R8G8", "PF_G16R16F", "PF_G32R32F", "PF_A32B32G32R32F");
    private static final List<String> COLOR = List.of("PF_R8G8B8A8", "PF_FloatRGBA", "PF_A32B32G32R32F");

    private NiagaraGrid3DShaderFormats() { }

    static void add(JsonObject stage, JsonObject program, String source) {
        var resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        var outputs = stage.getAsJsonObject("niagara_stage_metadata").getAsJsonArray("OutputDestinations");
        var variants = new LinkedHashMap<String, List<String>>();
        boolean present = false;
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            boolean array = binding.has("grid2d_collection");
            String definition = array ? "grid2d_collection" : "grid3d_collection";
            if (!binding.has(definition) || !binding.has("shader_symbol")) continue;
            present = true;
            String symbol = binding.get("shader_symbol").getAsString();
            String input = symbol + "_Grid", output = symbol + "_OutputGrid", attributes = symbol + "_PerAttributeData";
            String dimension = array ? "Texture2DArray" : "Texture3D";
            String kind = array ? ARRAY_IMAGE_KIND : IMAGE_KIND;
            var image = Pattern.compile("\\bRW" + dimension + "\\s*<\\s*(float[234]?)\\s*>\\s+"
                    + Pattern.quote(output) + "\\s*;").matcher(source);
            if (!image.find()) throw new BakeException("Missing Niagara Grid Collection shader declaration " + output);
            String type = image.group(1);
            if (image.find()) throw new BakeException("Ambiguous Niagara Grid Collection shader declaration " + output);
            addResource(resources, input, kind, dimension + "<" + type + ">", false, "PF_R32_FLOAT");
            addResource(resources, output, kind, "RW" + dimension + "<" + type + ">", true, "PF_R32_FLOAT");
            if (!array) {
                var metadata = addResource(resources, attributes, ATTRIBUTE_KIND, "StructuredBuffer<float4>", false, null);
                metadata.addProperty("original_hlsl_type", "Buffer<float4>");
                metadata.addProperty("stride_bytes", 16);
            }
            String name = binding.get("parameter_name").getAsString();
            boolean writer = !binding.getAsJsonObject(definition).get("reader").getAsBoolean();
            if (writer && outputs != null && outputs.asList().stream().anyMatch(itemName -> name.equalsIgnoreCase(itemName.getAsString())))
                variants.put(output, switch (type) { case "float" -> SCALAR; case "float2" -> PAIR; default -> COLOR; });
        }
        if (!present) return;
        stage.add("resources", resources);
        stage.addProperty("niagara_grid_collection", true);
        if (variants.isEmpty()) return;
        var base = stage.getAsJsonArray("variants");
        if (base == null || base.isEmpty()) {
            base = new JsonArray();
            var item = new JsonObject();
            item.addProperty("id", "default");
            base.add(item);
        }
        var expanded = new JsonArray();
        var names = new ArrayList<>(variants.keySet());
        for (var item : base) expand(expanded, item.getAsJsonObject(), variants, names, 0, new JsonObject(), "");
        stage.add("variants", expanded);
    }

    private static void expand(JsonArray output, JsonObject base, Map<String, List<String>> formats, List<String> names,
                               int index, JsonObject selected, String key) {
        if (index == names.size()) {
            var variant = base.deepCopy();
            String id = variant.has("id") ? variant.get("id").getAsString() : "default";
            variant.addProperty("id", id + "-grid3d" + key);
            var selector = variant.has("selector") ? variant.getAsJsonObject("selector") : new JsonObject();
            selector.add(SELECTOR, selected.deepCopy());
            variant.add("selector", selector);
            output.add(variant);
            return;
        }
        String name = names.get(index);
        var choices = formats.get(name);
        for (int choice = 0; choice < choices.size(); choice++) {
            selected.addProperty(name, choices.get(choice));
            expand(output, base, formats, names, index + 1, selected, key + "-" + choice);
        }
    }

    private static JsonObject addResource(JsonArray resources, String name, String kind, String hlsl, boolean writable, String pf) {
        var resource = new JsonObject();
        resource.addProperty("name", name);
        resource.addProperty("kind", kind);
        resource.addProperty("hlsl_type", hlsl);
        resource.addProperty("uav", writable);
        if (pf != null) resource.addProperty("format", pf);
        resources.add(resource);
        return resource;
    }
}
