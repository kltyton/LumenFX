package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.StreamingMips;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.Volume;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePlacement;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePlacement.MaterialQuality;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import net.minecraft.world.phys.Vec3;

/** Reads the authored component transform in Unreal units and publishes the same signed volume in world units. */
final class RuntimeVirtualTexturePlacementJson {
    private RuntimeVirtualTexturePlacementJson() { }

    static RuntimeVirtualTexturePlacement read(JsonObject json) {
        var matrix = SceneComponentTransform.authored(json.getAsJsonObject("transform")).matrix();
        var origin = position(matrix.m30(), matrix.m31(), matrix.m32());
        var x = position(matrix.m00(), matrix.m01(), matrix.m02());
        var y = position(matrix.m10(), matrix.m11(), matrix.m12());
        var z = position(matrix.m20(), matrix.m21(), matrix.m22());
        double minHeight = origin.y + Math.min(0, x.y) + Math.min(0, y.y) + Math.min(0, z.y);
        double maxHeight = origin.y + Math.max(0, x.y) + Math.max(0, y.y) + Math.max(0, z.y);
        var volume = new Volume(origin, x, y, z, minHeight, maxHeight);
        var quality = json.get("minimum_material_quality");
        var scalability = json.get("scalability_group");
        var streaming = json.getAsJsonObject("streaming");
        return new RuntimeVirtualTexturePlacement(json.get("source").getAsString(), volume,
                json.get("hide_primitives").getAsBoolean(), windowsEnabled(json.get("platform_enabled")),
                json.get("nanite_only").getAsBoolean(), quality.isJsonNull() ? null : MaterialQuality.valueOf(quality.getAsString()),
                scalability.isJsonNull() ? -1 : scalability.getAsInt(),
                streaming == null ? null : new StreamingMips(streaming.get("source").getAsString(), streaming.get("only").getAsBoolean()));
    }

    private static boolean windowsEnabled(JsonElement source) {
        if (source.isJsonNull()) return true;
        if (source.isJsonPrimitive()) return source.getAsJsonPrimitive().isBoolean() ? source.getAsBoolean() : source.getAsInt() != 0;
        var perPlatform = source.getAsJsonObject();
        var overrides = perPlatform.getAsJsonObject("overrides");
        if (overrides != null) for (var entry : overrides.entrySet())
            if (entry.getKey().equalsIgnoreCase("Windows")) return entry.getValue().getAsBoolean();
        return perPlatform.get("default").getAsBoolean();
    }

    private static Vec3 position(double x, double y, double z) { return new Vec3(x * .01, z * .01, y * .01); }
}
