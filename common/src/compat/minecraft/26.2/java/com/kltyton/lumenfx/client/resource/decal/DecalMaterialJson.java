package com.kltyton.lumenfx.client.resource.decal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.effect.render.decal.DecalMaterialSettings;
import java.util.Locale;

public final class DecalMaterialJson {
    private DecalMaterialJson() { }

    public static DecalMaterialSettings read(JsonObject value) {
        if (!value.has("outputs")) throw new JsonParseException("Decal material requires its output channel mask");
        String blend = value.has("blend") ? value.get("blend").getAsString() : "translucent";
        return new DecalMaterialSettings(value.get("outputs").getAsInt(),
                DecalMaterialSettings.Blend.valueOf(blend.toUpperCase(Locale.ROOT)));
    }
}
