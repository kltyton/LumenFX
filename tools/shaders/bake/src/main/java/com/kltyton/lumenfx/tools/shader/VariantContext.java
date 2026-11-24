package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

record VariantContext(
        boolean canResurrectHistory,
        boolean orthographic,
        boolean generateVelocityFlatten,
        List<String> selectionKeys
) {
    VariantContext {
        selectionKeys = List.copyOf(selectionKeys);
    }

    String id() {
        final StringBuilder id = new StringBuilder("res")
                .append(this.canResurrectHistory ? "1" : "0")
                .append("-ortho")
                .append(this.orthographic ? "1" : "0");
        if (this.selectionKeys.contains("generateVelocityFlatten")) {
            id.append("-gvf").append(this.generateVelocityFlatten ? "1" : "0");
        }
        return id.toString();
    }

    JsonObject toJson() {
        final JsonObject json = new JsonObject();
        json.addProperty("id", id());
        json.addProperty("canResurrectHistory", this.canResurrectHistory);
        json.addProperty("orthographic", this.orthographic);
        json.addProperty("generateVelocityFlatten", this.generateVelocityFlatten);
        final JsonArray keys = new JsonArray();
        for (String key : this.selectionKeys) {
            keys.add(key);
        }
        json.add("selectionKeys", keys);
        return json;
    }
}
