package com.kltyton.lumenfx.client.resource.niagara;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.effect.render.sprite.SpriteRendererInformation;

public final class SpriteRendererInfoJson {
    private SpriteRendererInfoJson() { }
    public static SpriteRendererInformation read(JsonObject source) {
        if (!source.has("is_valid") || !source.has("source_mode") || !source.has("alignment") || !source.has("facing_mode")
                || !source.has("sub_image_blend") || !source.has("sub_image_size") || source.getAsJsonArray("sub_image_size").size() != 2)
            throw new JsonParseException("Sprite renderer information requires validity, source modes and SubUV settings");
        var size = source.getAsJsonArray("sub_image_size");
        return new SpriteRendererInformation(source.get("is_valid").getAsBoolean(), source.get("source_mode").getAsInt(),
                source.get("alignment").getAsInt(), source.get("facing_mode").getAsInt(), source.get("sub_image_blend").getAsBoolean(),
                size.get(0).getAsFloat(), size.get(1).getAsFloat());
    }
}
