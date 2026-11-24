package com.kltyton.lumenfx.client.resource.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reconstructs the saved MPC hierarchy without flattening CPU and GPU defaults together. */
public final class MaterialCollectionJson {
    private MaterialCollectionJson() { }

    public static List<MaterialCollectionBinding> bindings(@Nullable JsonArray values) {
        if (values == null) return List.of();
        List<MaterialCollectionBinding> result = new ArrayList<>();
        for (JsonElement element : values) {
            JsonObject value = element.getAsJsonObject();
            result.add(new MaterialCollectionBinding(value.get("uniform").getAsString(), value.get("stages").getAsInt(), read(value.getAsJsonObject("source"))));
        }
        return List.copyOf(result);
    }

    public static MaterialCollectionDefinition read(JsonObject source) {
        List<MaterialCollectionDefinition.Scalar> scalars = new ArrayList<>();
        List<MaterialCollectionDefinition.VectorParameter> vectors = new ArrayList<>();
        for (JsonElement element : source.getAsJsonArray("scalars")) {
            JsonObject parameter = element.getAsJsonObject();
            if (parameter.getAsJsonArray("default").size() != 1) throw new IllegalArgumentException("Invalid MPC scalar default");
            scalars.add(new MaterialCollectionDefinition.Scalar(parameter.get("name").getAsString(), parameter.get("id").getAsString(),
                    (int)parameter.getAsJsonArray("default").get(0).getAsLong()));
        }
        for (JsonElement element : source.getAsJsonArray("vectors")) {
            JsonObject parameter = element.getAsJsonObject();
            vectors.add(new MaterialCollectionDefinition.VectorParameter(parameter.get("name").getAsString(), parameter.get("id").getAsString(), vector(parameter.get("default"))));
        }
        Map<String, Integer> scalarOverrides = new LinkedHashMap<>();
        for (JsonElement element : source.getAsJsonArray("scalar_overrides")) {
            var pair = element.getAsJsonArray();
            if (pair.size() != 2 || pair.get(1).getAsJsonArray().size() != 1) throw new IllegalArgumentException("Invalid MPC scalar override");
            scalarOverrides.put(pair.get(0).getAsString(), (int)pair.get(1).getAsJsonArray().get(0).getAsLong());
        }
        Map<String, MaterialCollectionDefinition.Vector> vectorOverrides = new LinkedHashMap<>();
        for (JsonElement element : source.getAsJsonArray("vector_overrides")) {
            var pair = element.getAsJsonArray();
            if (pair.size() != 2) throw new IllegalArgumentException("Invalid MPC vector override");
            vectorOverrides.put(pair.get(0).getAsString(), vector(pair.get(1)));
        }
        JsonElement parent = source.get("base");
        return new MaterialCollectionDefinition(source.get("path").getAsString(), source.get("state_id").getAsString(),
                parent == null || parent.isJsonNull() ? null : read(parent.getAsJsonObject()), scalars, vectors, scalarOverrides, vectorOverrides);
    }
    private static MaterialCollectionDefinition.Vector vector(JsonElement source) {
        var values = source.getAsJsonArray();
        if (values.size() != 4) throw new IllegalArgumentException("Invalid MPC vector default");
        return new MaterialCollectionDefinition.Vector((int)values.get(0).getAsLong(), (int)values.get(1).getAsLong(),
                (int)values.get(2).getAsLong(), (int)values.get(3).getAsLong());
    }
}
