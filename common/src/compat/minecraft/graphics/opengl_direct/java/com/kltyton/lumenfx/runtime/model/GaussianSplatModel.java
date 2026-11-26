package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 3D Gaussian Splatting asset model (position, anisotropic 3D scale, orientation quaternion,
 * RGB color / SH0, and opacity) shared across static splat assets and Niagara Gaussian Splat writers.
 */
public final class GaussianSplatModel {
    public record Splat(float x, float y, float z,
                        float sx, float sy, float sz,
                        float qx, float qy, float qz, float qw,
                        float r, float g, float b, float opacity) { }

    private final String id;
    private final List<Splat> splats;

    public GaussianSplatModel(String id, List<Splat> splats) {
        this.id = id;
        this.splats = List.copyOf(splats);
    }

    public String id() { return id; }
    public List<Splat> splats() { return splats; }
    public int count() { return splats.size(); }

    public static GaussianSplatModel fromJson(String id, JsonObject obj) {
        List<Splat> list = new ArrayList<>();
        JsonArray arr = obj.has("splats") && obj.get("splats").isJsonArray()
                ? obj.getAsJsonArray("splats") : null;
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();
                float[] p = readVec(s, "position", 0f, 0f, 0f);
                float[] sc = readVec(s, "scale", 0.15f, 0.15f, 0.15f);
                float[] q = readVec4(s, "rotation", 0f, 0f, 0f, 1f);
                float[] c = readVec4(s, "color", 1f, 1f, 1f, 1f);
                float opacity = s.has("opacity") ? s.get("opacity").getAsFloat() : c[3];
                list.add(new Splat(p[0], p[1], p[2], sc[0], sc[1], sc[2], q[0], q[1], q[2], q[3], c[0], c[1], c[2], opacity));
            }
        }
        return new GaussianSplatModel(id, list);
    }

    private static float[] readVec(JsonObject o, String key, float dx, float dy, float dz) {
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            if (a.size() >= 3) return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        }
        return new float[]{dx, dy, dz};
    }

    private static float[] readVec4(JsonObject o, String key, float dx, float dy, float dz, float dw) {
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            if (a.size() >= 4) {
                return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
            }
            if (a.size() == 3) {
                return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), dw};
            }
        }
        return new float[]{dx, dy, dz, dw};
    }
}
