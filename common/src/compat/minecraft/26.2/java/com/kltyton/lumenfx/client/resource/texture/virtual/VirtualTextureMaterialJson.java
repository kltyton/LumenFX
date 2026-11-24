package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.ParameterInfo;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import com.kltyton.lumenfx.client.resource.parameter.MaterialInstanceJson;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead.Collection;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead.ObjectInput;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead.StreamedBinding;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class VirtualTextureMaterialJson {
    private VirtualTextureMaterialJson() { }

    public static List<VirtualTextureRead> read(@Nullable JsonArray values) {
        if (values == null) return List.of();
        var result = new ArrayList<VirtualTextureRead>(values.size());
        for (var item : values) {
            var data = item.getAsJsonObject();
            var parameter = data.get("parameter");
            var material = data.get("material");
            var streamed = data.get("streamed");
            var binding = data.get("renderer_binding");
            result.add(new VirtualTextureRead(data.get("name").getAsString(), data.get("source").getAsString(),
                    material == null || material.isJsonNull() ? null : Material.valueOf(material.getAsString()),
                    streamed == null || streamed.isJsonNull() ? null : Identifier.parse(streamed.getAsString()),
                    data.get("layers").getAsInt(),
                    data.get("packed").getAsBoolean(), data.get("adaptive").getAsBoolean(),
                    parameter == null || parameter.isJsonNull() ? null : new ParameterInfo(parameter.getAsString(),
                            MaterialInstanceJson.association(data.get("association").getAsString()), data.get("index").getAsInt()),
                    binding == null || binding.isJsonNull() ? null : streamedBinding(binding.getAsJsonObject())));
        }
        return List.copyOf(result);
    }

    private static StreamedBinding streamedBinding(JsonObject data) {
        var inputs = new ArrayList<ObjectInput>();
        for (var value : data.getAsJsonArray("object_inputs")) {
            var input = value.getAsJsonObject();
            inputs.add(new ObjectInput(input.get("variable").getAsString(), input.get("default_present").getAsBoolean(),
                    optional(input, "default_texture")));
        }
        var source = data.get("collection");
        Collection collection = null;
        if (source != null && !source.isJsonNull()) {
            var value = source.getAsJsonObject();
            collection = new Collection(value.get("path").getAsString(), value.get("name").getAsString(),
                    value.get("source_type").getAsString(), value.get("system_override").getAsBoolean(), optional(value, "base_default"));
        }
        String base = optional(data, "base");
        return new StreamedBinding(data.get("variable").getAsString(), data.get("source_type").getAsString(),
                data.get("type_flags").getAsInt(), optional(data, "data_interface"), optional(data, "child"),
                optional(data, "object_default"), optional(data, "interface_id"), data.get("static_exposure").getAsBoolean(),
                base == null ? null : Identifier.parse(base), collection, inputs);
    }

    private static @Nullable String optional(JsonObject data, String field) {
        var value = data.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
