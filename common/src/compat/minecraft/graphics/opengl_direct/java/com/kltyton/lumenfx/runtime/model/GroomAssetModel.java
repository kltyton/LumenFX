package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Groom hair/fur strand asset model with per-strand control points, root-to-tip width taper,
 * melanin/albedo color, roughness, and spring-verlet simulation parameters.
 */
public final class GroomAssetModel {
    public record Strand(float[] restPoints, float rootWidth, float tipWidth,
                         float r, float g, float b, float roughness, float stiffness) {
        public int pointCount() { return restPoints.length / 3; }
    }

    private final String id;
    private final List<Strand> strands;
    private final float gravity;
    private final float windStrength;

    public GroomAssetModel(String id, List<Strand> strands, float gravity, float windStrength) {
        this.id = id;
        this.strands = List.copyOf(strands);
        this.gravity = gravity;
        this.windStrength = windStrength;
    }

    public String id() { return id; }
    public List<Strand> strands() { return strands; }
    public float gravity() { return gravity; }
    public float windStrength() { return windStrength; }

    public static GroomAssetModel fromJson(String id, JsonObject obj) {
        float gravity = obj.has("gravity") ? obj.get("gravity").getAsFloat() : 9.81f;
        float wind = obj.has("wind_strength") ? obj.get("wind_strength").getAsFloat() : 0.35f;
        List<Strand> strands = new ArrayList<>();
        JsonArray arr = obj.has("strands") && obj.get("strands").isJsonArray()
                ? obj.getAsJsonArray("strands") : null;
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();
                JsonArray pts = s.has("points") && s.get("points").isJsonArray() ? s.getAsJsonArray("points") : null;
                if (pts == null || pts.size() < 2) continue;
                float[] coords = new float[pts.size() * 3];
                for (int i = 0; i < pts.size(); i++) {
                    JsonArray p = pts.get(i).getAsJsonArray();
                    coords[i * 3] = p.get(0).getAsFloat();
                    coords[i * 3 + 1] = p.get(1).getAsFloat();
                    coords[i * 3 + 2] = p.get(2).getAsFloat();
                }
                float rootW = s.has("root_width") ? s.get("root_width").getAsFloat() : 0.025f;
                float tipW = s.has("tip_width") ? s.get("tip_width").getAsFloat() : 0.005f;
                float rough = s.has("roughness") ? s.get("roughness").getAsFloat() : 0.35f;
                float stiff = s.has("stiffness") ? s.get("stiffness").getAsFloat() : 0.65f;
                float r = 0.25f, g = 0.18f, b = 0.12f;
                if (s.has("color") && s.get("color").isJsonArray()) {
                    JsonArray c = s.getAsJsonArray("color");
                    if (c.size() >= 3) { r = c.get(0).getAsFloat(); g = c.get(1).getAsFloat(); b = c.get(2).getAsFloat(); }
                }
                strands.add(new Strand(coords, rootW, tipW, r, g, b, rough, stiff));
            }
        }
        return new GroomAssetModel(id, strands, gravity, wind);
    }
}
