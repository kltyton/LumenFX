package com.kltyton.lumenfx.client.resource.niagara;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererInformation;

public final class MeshRendererInfoJson {
    private MeshRendererInfoJson() { }

    public static MeshRendererInformation read(JsonObject source) {
        if (!source.has("mesh_count") || !source.has("sub_image_blend") || !source.has("sub_image_size")
                || !source.has("bounds") || source.has("mesh_sources"))
            throw new JsonParseException("Mesh renderer information requires completed mesh bounds and SubUV settings");
        int count = source.get("mesh_count").getAsInt();
        var values = source.getAsJsonArray("bounds");
        var size = source.getAsJsonArray("sub_image_size");
        if (count < 0 || (long)count * 6 != values.size() || size.size() != 2)
            throw new JsonParseException("Mesh renderer information has inconsistent mesh or SubUV dimensions");
        float[] bounds = new float[values.size()];
        for (int index = 0; index < bounds.length; index++) bounds[index] = values.get(index).getAsFloat();
        return new MeshRendererInformation(count, source.get("sub_image_blend").getAsBoolean(),
                size.get(0).getAsFloat(), size.get(1).getAsFloat(), bounds);
    }
}
