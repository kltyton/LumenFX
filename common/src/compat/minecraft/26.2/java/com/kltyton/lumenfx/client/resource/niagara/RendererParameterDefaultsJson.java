package com.kltyton.lumenfx.client.resource.niagara;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.kltyton.lumenfx.effect.niagara.parameter.RendererParameterDefaults;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout;
import java.util.ArrayList;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/** Decodes saved renderer defaults without constructing or mutating a live parameter store. */
final class RendererParameterDefaultsJson {
    private RendererParameterDefaultsJson() { }

    static RendererParameterDefaults read(JsonObject value) {
        if (value == null) return RendererParameterDefaults.EMPTY;
        var variables = new ArrayList<RendererParameterDefaults.Variable>();
        for (var item : value.getAsJsonArray("variables")) {
            var variable = item.getAsJsonObject();
            variables.add(new RendererParameterDefaults.Variable(variable.get("name").getAsString(),
                    variable.get("source_type").getAsString(), variable.get("offset").getAsInt(), variable.get("flags").getAsInt(),
                    variable.has("underlying") ? variable.get("underlying").getAsInt() : 2,
                    variable.has("bytes") ? variable.get("bytes").getAsInt() : -1,
                    variable.has("lwc") && !variable.get("lwc").isJsonNull() ? variable.get("lwc").getAsBoolean() : null));
        }
        var external = new ArrayList<RendererParameterDefaults.ExternalObject>();
        if (value.has("external_objects")) for (var item : value.getAsJsonArray("external_objects")) {
            var mapping = item.getAsJsonObject(); var target = mapping.getAsJsonObject("target");
            external.add(new RendererParameterDefaults.ExternalObject(new NiagaraParameterLayout.Key(target.get("name").getAsString(),
                    target.get("source_type").getAsString(), target.get("underlying").getAsInt(), target.get("flags").getAsInt(),
                    NiagaraParameterLayout.Storage.OBJECT), mapping.get("external").getAsString()));
        }
        return new RendererParameterDefaults(Base64.getDecoder().decode(value.get("data").getAsString()), variables,
                layout(value.getAsJsonArray("required")), layout(value.getAsJsonArray("compiled_layout")), external);
    }

    private static @Nullable NiagaraParameterLayout layout(@Nullable JsonArray values) {
        if (values == null) return null;
        var slots = new ArrayList<NiagaraParameterLayout.Variable>();
        for (var item : values) {
            var slot = item.getAsJsonObject();
            var storage = switch (slot.get("storage").getAsString()) {
                case "numeric" -> NiagaraParameterLayout.Storage.NUMERIC;
                case "interface" -> NiagaraParameterLayout.Storage.INTERFACE;
                case "object" -> NiagaraParameterLayout.Storage.OBJECT;
                default -> throw new IllegalArgumentException("Unknown Niagara parameter storage");
            };
            slots.add(new NiagaraParameterLayout.Variable(new NiagaraParameterLayout.Key(slot.get("name").getAsString(),
                    slot.get("source_type").getAsString(), slot.get("underlying").getAsInt(), slot.get("flags").getAsInt(), storage),
                    slot.get("bytes").getAsInt(), slot.has("lwc") && !slot.get("lwc").isJsonNull() ? slot.get("lwc").getAsBoolean() : null));
        }
        return new NiagaraParameterLayout(slots);
    }
}
