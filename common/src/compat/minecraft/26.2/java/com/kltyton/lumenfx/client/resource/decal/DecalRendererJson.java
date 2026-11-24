package com.kltyton.lumenfx.client.resource.decal;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.render.decal.DecalRendererSettings;

public final class DecalRendererJson {
    private DecalRendererJson() { }

    public static DecalRendererSettings read(JsonObject value) {
        return new DecalRendererSettings(value.has("screen_size_fade") ? value.get("screen_size_fade").getAsFloat() : 0,
                string(value, "material_binding"), string(value, "invalid_domain_material"),
                value.has("material_variant") ? value.get("material_variant").getAsString() : "decal");
    }
    private static @org.jspecify.annotations.Nullable String string(JsonObject value, String field) {
        return !value.has(field) || value.get(field).isJsonNull() ? null : value.get(field).getAsString();
    }
}
