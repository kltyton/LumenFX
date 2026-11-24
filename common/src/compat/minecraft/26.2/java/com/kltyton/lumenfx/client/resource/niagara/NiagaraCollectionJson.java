package com.kltyton.lumenfx.client.resource.niagara;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;
import com.kltyton.lumenfx.client.resource.material.MaterialCollectionJson;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/** Collection descriptors are decoded independently of script execution and module parameter preparation. */
public final class NiagaraCollectionJson {
    private NiagaraCollectionJson() { }

    public static @Nullable NiagaraCollectionBinding read(JsonObject value) {
        if (!value.has("collection")) return null;
        byte[] defaults = bytes(value.get("collection_default"));
        if (defaults == null) throw new IllegalArgumentException("Missing collection defaults");
        return new NiagaraCollectionBinding(value.get("collection").getAsString(), value.get("name").getAsString(),
                value.get("source_type").getAsString(), defaults, position(value.get("collection_position")),
                value.has("collection_system_override") && value.get("collection_system_override").getAsBoolean(),
                bytes(value.get("collection_base_default")), position(value.get("collection_base_position")),
                value.has("collection_material_source") ? MaterialCollectionJson.read(value.getAsJsonObject("collection_material_source")) : null,
                value.has("collection_material_parameter") ? value.get("collection_material_parameter").getAsString() : null);
    }
    private static byte @Nullable [] bytes(@Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonArray()) return Base64.getDecoder().decode(value.getAsString());
        ByteBuffer result = ByteBuffer.allocate(value.getAsJsonArray().size() * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (JsonElement word : value.getAsJsonArray()) result.putInt((int)word.getAsLong());
        return result.array();
    }
    private static double @Nullable [] position(@Nullable JsonElement value) {
        return value == null || value.isJsonNull() ? null : value.getAsJsonArray().asList().stream().mapToDouble(JsonElement::getAsDouble).toArray();
    }
}
