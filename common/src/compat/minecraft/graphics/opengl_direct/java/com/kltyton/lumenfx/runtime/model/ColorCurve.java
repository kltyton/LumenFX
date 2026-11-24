package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.math.SourceFloatMath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

/** Evaluable RGBA color and alpha gradient matching LumenFX 26.2 {@code EffectJsonFields.color}. */
public final class ColorCurve {
    public record ColorKey(float time, float r, float g, float b) { }
    public record AlphaKey(float time, float a) { }

    private final List<ColorKey> colorMin;
    private final List<AlphaKey> alphaMin;
    private final List<ColorKey> colorMax;
    private final List<AlphaKey> alphaMax;
    private final boolean dual;
    private final boolean fixedMode;

    private ColorCurve(List<ColorKey> colorMin, List<AlphaKey> alphaMin,
                       List<ColorKey> colorMax, List<AlphaKey> alphaMax, boolean dual, boolean fixedMode) {
        this.colorMin = colorMin;
        this.alphaMin = alphaMin;
        this.colorMax = colorMax;
        this.alphaMax = alphaMax;
        this.dual = dual;
        this.fixedMode = fixedMode;
    }

    public static ColorCurve solid(float r, float g, float b, float a) {
        List<ColorKey> c = List.of(new ColorKey(0f, r, g, b));
        List<AlphaKey> al = List.of(new AlphaKey(0f, a));
        return new ColorCurve(c, al, c, al, false, false);
    }

    public static ColorCurve white() { return solid(1f, 1f, 1f, 1f); }

    public void evaluate(float normalizedTime, float randomLerp, float[] outRgba) {
        float t = SourceFloatMath.clamp(normalizedTime, 0f, 1f);
        evalSingle(colorMin, alphaMin, t, fixedMode, outRgba);
        if (dual) {
            float[] hi = new float[4];
            evalSingle(colorMax, alphaMax, t, fixedMode, hi);
            float u = SourceFloatMath.clamp(randomLerp, 0f, 1f);
            for (int i = 0; i < 4; i++) outRgba[i] = outRgba[i] + (hi[i] - outRgba[i]) * u;
        }
    }

    private static void evalSingle(List<ColorKey> colors, List<AlphaKey> alphas, float t, boolean fixed, float[] out) {
        if (colors.isEmpty()) {
            out[0] = 1f; out[1] = 1f; out[2] = 1f;
        } else if (colors.size() == 1 || t <= colors.get(0).time()) {
            ColorKey k = colors.get(0);
            out[0] = k.r(); out[1] = k.g(); out[2] = k.b();
        } else {
            ColorKey last = colors.get(colors.size() - 1);
            if (t >= last.time()) {
                out[0] = last.r(); out[1] = last.g(); out[2] = last.b();
            } else {
                for (int i = 0; i < colors.size() - 1; i++) {
                    ColorKey a = colors.get(i);
                    ColorKey b = colors.get(i + 1);
                    if (t >= a.time() && t <= b.time()) {
                        if (fixed) {
                            out[0] = b.r(); out[1] = b.g(); out[2] = b.b();
                        } else {
                            float dt = Math.max(1e-6f, b.time() - a.time());
                            float u = (t - a.time()) / dt;
                            out[0] = a.r() + (b.r() - a.r()) * u;
                            out[1] = a.g() + (b.g() - a.g()) * u;
                            out[2] = a.b() + (b.b() - a.b()) * u;
                        }
                        break;
                    }
                }
            }
        }
        if (alphas.isEmpty()) {
            out[3] = 1f;
        } else if (alphas.size() == 1 || t <= alphas.get(0).time()) {
            out[3] = alphas.get(0).a();
        } else {
            AlphaKey last = alphas.get(alphas.size() - 1);
            if (t >= last.time()) {
                out[3] = last.a();
            } else {
                for (int i = 0; i < alphas.size() - 1; i++) {
                    AlphaKey a = alphas.get(i);
                    AlphaKey b = alphas.get(i + 1);
                    if (t >= a.time() && t <= b.time()) {
                        if (fixed) {
                            out[3] = b.a();
                        } else {
                            float dt = Math.max(1e-6f, b.time() - a.time());
                            float u = (t - a.time()) / dt;
                            out[3] = a.a() + (b.a() - a.a()) * u;
                        }
                        break;
                    }
                }
            }
        }
    }

    public static ColorCurve fromJson(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) return white();
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                return fromStops(arr, false);
            }
            float[] c = readRgbaArray(arr);
            return solid(c[0], c[1], c[2], c[3]);
        }
        if (!element.isJsonObject()) return white();
        JsonObject obj = element.getAsJsonObject();
        boolean fixed = obj.has("mode") && obj.get("mode").isJsonPrimitive()
                && obj.get("mode").getAsString().equalsIgnoreCase("fixed");
        if (obj.has("min") && obj.has("max")) {
            ColorCurve lo = fromJson(obj.get("min"));
            ColorCurve hi = fromJson(obj.get("max"));
            return new ColorCurve(lo.colorMin, lo.alphaMin, hi.colorMin, hi.alphaMin, true, fixed || lo.fixedMode);
        }
        if (obj.has("color_min") && obj.has("color_max")) {
            float[] lo = readRgba(obj.get("color_min"));
            float[] hi = readRgba(obj.get("color_max"));
            return new ColorCurve(
                    List.of(new ColorKey(0f, lo[0], lo[1], lo[2])), List.of(new AlphaKey(0f, lo[3])),
                    List.of(new ColorKey(0f, hi[0], hi[1], hi[2])), List.of(new AlphaKey(0f, hi[3])), true, fixed);
        }
        if (obj.has("gradient_min") && obj.has("gradient_max")) {
            ColorCurve lo = fromJson(obj.get("gradient_min"));
            ColorCurve hi = fromJson(obj.get("gradient_max"));
            return new ColorCurve(lo.colorMin, lo.alphaMin, hi.colorMin, hi.alphaMin, true, fixed);
        }
        if (obj.has("gradient")) return fromJson(obj.get("gradient"));
        if (obj.has("stops") && obj.get("stops").isJsonArray()) return fromStops(obj.getAsJsonArray("stops"), fixed);
        if (obj.has("keys") && obj.get("keys").isJsonArray()) return fromStops(obj.getAsJsonArray("keys"), fixed);
        if (obj.has("color_keys") || obj.has("alpha_keys")) {
            List<ColorKey> ck = new ArrayList<>();
            List<AlphaKey> ak = new ArrayList<>();
            if (obj.has("color_keys") && obj.get("color_keys").isJsonArray()) {
                for (JsonElement e : obj.getAsJsonArray("color_keys")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject k = e.getAsJsonObject();
                    float t = k.has("time") ? k.get("time").getAsFloat() : 0f;
                    float[] c = k.has("color") ? readRgba(k.get("color")) : readRgba(k);
                    ck.add(new ColorKey(t, c[0], c[1], c[2]));
                }
            }
            if (obj.has("alpha_keys") && obj.get("alpha_keys").isJsonArray()) {
                for (JsonElement e : obj.getAsJsonArray("alpha_keys")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject k = e.getAsJsonObject();
                    float t = k.has("time") ? k.get("time").getAsFloat() : 0f;
                    float a = k.has("alpha") ? k.get("alpha").getAsFloat() : 1f;
                    ak.add(new AlphaKey(t, a));
                }
            }
            ck.sort(Comparator.comparingDouble(ColorKey::time));
            ak.sort(Comparator.comparingDouble(AlphaKey::time));
            if (ck.isEmpty()) ck.add(new ColorKey(0f, 1f, 1f, 1f));
            if (ak.isEmpty()) ak.add(new AlphaKey(0f, 1f));
            return new ColorCurve(List.copyOf(ck), List.copyOf(ak), List.copyOf(ck), List.copyOf(ak), false, fixed);
        }
        if (obj.has("color")) {
            float[] c = readRgba(obj.get("color"));
            return solid(c[0], c[1], c[2], c[3]);
        }
        if (obj.has("r") || obj.has("g") || obj.has("b")) {
            float[] c = readRgba(obj);
            return solid(c[0], c[1], c[2], c[3]);
        }
        return white();
    }

    private static ColorCurve fromStops(JsonArray stops, boolean fixed) {
        List<ColorKey> ck = new ArrayList<>();
        List<AlphaKey> ak = new ArrayList<>();
        for (JsonElement e : stops) {
            if (!e.isJsonObject()) continue;
            JsonObject k = e.getAsJsonObject();
            float t = k.has("time") ? k.get("time").getAsFloat() : 0f;
            float[] c = k.has("color") ? readRgba(k.get("color")) : readRgba(k);
            ck.add(new ColorKey(t, c[0], c[1], c[2]));
            ak.add(new AlphaKey(t, c[3]));
        }
        ck.sort(Comparator.comparingDouble(ColorKey::time));
        ak.sort(Comparator.comparingDouble(AlphaKey::time));
        if (ck.isEmpty()) ck.add(new ColorKey(0f, 1f, 1f, 1f));
        if (ak.isEmpty()) ak.add(new AlphaKey(0f, 1f));
        return new ColorCurve(List.copyOf(ck), List.copyOf(ak), List.copyOf(ck), List.copyOf(ak), false, fixed);
    }

    public static float[] readRgba(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) return new float[] {1f, 1f, 1f, 1f};
        if (el.isJsonArray()) return readRgbaArray(el.getAsJsonArray());
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            float r = o.has("r") ? o.get("r").getAsFloat() : 1f;
            float g = o.has("g") ? o.get("g").getAsFloat() : 1f;
            float b = o.has("b") ? o.get("b").getAsFloat() : 1f;
            float a = o.has("a") ? o.get("a").getAsFloat() : 1f;
            return new float[] {r, g, b, a};
        }
        return new float[] {1f, 1f, 1f, 1f};
    }

    private static float[] readRgbaArray(JsonArray arr) {
        float r = arr.size() > 0 ? arr.get(0).getAsFloat() : 1f;
        float g = arr.size() > 1 ? arr.get(1).getAsFloat() : 1f;
        float b = arr.size() > 2 ? arr.get(2).getAsFloat() : 1f;
        float a = arr.size() > 3 ? arr.get(3).getAsFloat() : 1f;
        return new float[] {r, g, b, a};
    }
}
