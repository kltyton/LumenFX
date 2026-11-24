package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.math.SourceFloatMath;
import com.kltyton.lumenfx.math.SourceRandomStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Evaluable scalar function matching LumenFX 26.2 {@code EffectJsonFields.scalar} and Unity/Niagara
 * curves: constant, two-constants ({@code [min, max]} or object), single curve (Hermite/linear),
 * and two-curves (min/max envelope with deterministic random interpolation).
 */
public final class ScalarCurve {
    public enum Mode { CONSTANT, TWO_CONSTANTS, CURVE, TWO_CURVES }

    public record Keyframe(float time, float value, float inTangent, float outTangent) { }

    private final Mode mode;
    private final float constantMin;
    private final float constantMax;
    private final List<Keyframe> curveMin;
    private final List<Keyframe> curveMax;
    private final float multiplier;

    private ScalarCurve(Mode mode, float constantMin, float constantMax,
                        List<Keyframe> curveMin, List<Keyframe> curveMax, float multiplier) {
        this.mode = mode;
        this.constantMin = constantMin;
        this.constantMax = constantMax;
        this.curveMin = curveMin;
        this.curveMax = curveMax;
        this.multiplier = multiplier;
    }

    public static ScalarCurve constant(float value) {
        return new ScalarCurve(Mode.CONSTANT, value, value, List.of(), List.of(), 1.0f);
    }

    public static ScalarCurve twoConstants(float min, float max) {
        return new ScalarCurve(Mode.TWO_CONSTANTS, min, max, List.of(), List.of(), 1.0f);
    }

    public static ScalarCurve curve(List<Keyframe> keys, float multiplier) {
        return new ScalarCurve(Mode.CURVE, 0f, 0f, sortKeys(keys), List.of(), multiplier);
    }

    public static ScalarCurve twoCurves(List<Keyframe> minKeys, List<Keyframe> maxKeys, float multiplier) {
        return new ScalarCurve(Mode.TWO_CURVES, 0f, 0f, sortKeys(minKeys), sortKeys(maxKeys), multiplier);
    }

    public Mode mode() { return mode; }

    public float evaluate(float normalizedTime, float randomLerp) {
        float t = SourceFloatMath.clamp(normalizedTime, 0f, 1f);
        float u = SourceFloatMath.clamp(randomLerp, 0f, 1f);
        return switch (mode) {
            case CONSTANT -> constantMax;
            case TWO_CONSTANTS -> constantMin + (constantMax - constantMin) * u;
            case CURVE -> evaluateTrack(curveMin, t) * multiplier;
            case TWO_CURVES -> {
                float lo = evaluateTrack(curveMin, t) * multiplier;
                float hi = evaluateTrack(curveMax, t) * multiplier;
                yield lo + (hi - lo) * u;
            }
        };
    }

    public float sample(float normalizedTime, SourceRandomStream rng) {
        return evaluate(normalizedTime, rng.fraction());
    }

    public float evaluateMin(float normalizedTime) {
        return Math.min(evaluate(normalizedTime, 0f), evaluate(normalizedTime, 1f));
    }

    public float evaluateMax(float normalizedTime) {
        return Math.max(evaluate(normalizedTime, 0f), evaluate(normalizedTime, 1f));
    }

    private static float evaluateTrack(List<Keyframe> keys, float t) {
        if (keys.isEmpty()) return 0f;
        if (keys.size() == 1 || t <= keys.get(0).time()) return keys.get(0).value();
        Keyframe last = keys.get(keys.size() - 1);
        if (t >= last.time()) return last.value();
        for (int i = 0; i < keys.size() - 1; i++) {
            Keyframe a = keys.get(i);
            Keyframe b = keys.get(i + 1);
            if (t >= a.time() && t <= b.time()) {
                float dt = b.time() - a.time();
                if (dt <= 1e-6f) return b.value();
                float u = (t - a.time()) / dt;
                float u2 = u * u;
                float u3 = u2 * u;
                float h00 = 2f * u3 - 3f * u2 + 1f;
                float h10 = u3 - 2f * u2 + u;
                float h01 = -2f * u3 + 3f * u2;
                float h11 = u3 - u2;
                return h00 * a.value() + h10 * dt * a.outTangent()
                        + h01 * b.value() + h11 * dt * b.inTangent();
            }
        }
        return last.value();
    }

    private static List<Keyframe> sortKeys(List<Keyframe> keys) {
        if (keys.isEmpty()) return List.of();
        List<Keyframe> copy = new ArrayList<>(keys);
        copy.sort(Comparator.comparingDouble(Keyframe::time));
        return List.copyOf(copy);
    }

    /**
     * Parses both 26.2 {@code EffectJsonFields.scalar} formats (number, {@code [min, max]},
     * {@code {min: ..., max: ...}}, {@code {points/keys: [...]}}, {@code {min: {keys}, max: {keys}}})
     * and explicit Unity mode objects ({@code mode: two_curves / curve / two_constants}).
     */
    public static ScalarCurve fromJson(@Nullable JsonElement element, float fallback) {
        if (element == null || element.isJsonNull()) return constant(fallback);
        if (element.isJsonPrimitive()) {
            try {
                float v = element.getAsFloat();
                return constant(Float.isFinite(v) ? v : fallback);
            } catch (RuntimeException ignored) {
                return constant(fallback);
            }
        }
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            if (arr.size() == 2 && arr.get(0).isJsonPrimitive() && arr.get(1).isJsonPrimitive()) {
                return twoConstants(arr.get(0).getAsFloat(), arr.get(1).getAsFloat());
            }
            List<Keyframe> keys = readKeys(arr);
            if (!keys.isEmpty()) return curve(keys, 1.0f);
            return constant(fallback);
        }
        if (!element.isJsonObject()) return constant(fallback);
        JsonObject obj = element.getAsJsonObject();
        String modeStr = obj.has("mode") && obj.get("mode").isJsonPrimitive()
                ? obj.get("mode").getAsString().toLowerCase() : "";
        String kindStr = obj.has("kind") && obj.get("kind").isJsonPrimitive()
                ? obj.get("kind").getAsString().toLowerCase() : "";
        float mult = readFloat(obj, "curve_multiplier", readFloat(obj, "multiplier", readFloat(obj, "scale", 1.0f)));

        boolean hasLowHighCurve = obj.has("low") && obj.has("high")
                && (obj.get("low").isJsonObject() || obj.get("low").isJsonArray()
                        || obj.get("high").isJsonObject() || obj.get("high").isJsonArray());
        if (modeStr.equals("two_curves") || obj.has("curve_min") || obj.has("min_curve")
                || hasLowHighCurve
                || (obj.has("min") && obj.has("max") && (obj.get("min").isJsonObject() || obj.get("min").isJsonArray()
                        || obj.get("max").isJsonObject() || obj.get("max").isJsonArray()))) {
            JsonElement minEl = obj.has("curve_min") ? obj.get("curve_min")
                    : obj.has("min_curve") ? obj.get("min_curve")
                    : obj.has("low") ? obj.get("low") : obj.get("min");
            JsonElement maxEl = obj.has("curve_max") ? obj.get("curve_max")
                    : obj.has("max_curve") ? obj.get("max_curve")
                    : obj.has("high") ? obj.get("high") : obj.get("max");
            List<Keyframe> minK = extractCurveKeys(minEl, fallback);
            List<Keyframe> maxK = extractCurveKeys(maxEl, fallback);
            if (!minK.isEmpty() || !maxK.isEmpty()) {
                return twoCurves(minK.isEmpty() ? maxK : minK, maxK.isEmpty() ? minK : maxK, mult);
            }
        }
        if (modeStr.equals("curve") || kindStr.equals("curve")
                || obj.has("curve") || obj.has("keys") || obj.has("keyframes") || obj.has("points")) {
            JsonElement src = obj.has("curve") ? obj.get("curve")
                    : obj.has("keys") ? obj.get("keys")
                    : obj.has("keyframes") ? obj.get("keyframes") : obj.get("points");
            List<Keyframe> keys = readKeys(src);
            if (!keys.isEmpty()) return curve(keys, mult);
        }
        if (modeStr.equals("two_constants") || kindStr.equals("blend")
                || (obj.has("constant_min") && obj.has("constant_max"))
                || (obj.has("low") && obj.has("high"))
                || (obj.has("min") && obj.has("max"))) {
            float lo = readFloat(obj, "constant_min", readFloat(obj, "low", readFloat(obj, "min", fallback)));
            float hi = readFloat(obj, "constant_max", readFloat(obj, "high", readFloat(obj, "max", lo)));
            return twoConstants(lo, hi);
        }
        if (obj.has("constant")) return constant(readFloat(obj, "constant", fallback));
        if (obj.has("value")) return constant(readFloat(obj, "value", fallback));
        return constant(fallback);
    }

    private static List<Keyframe> extractCurveKeys(@Nullable JsonElement el, float fallback) {
        if (el == null || el.isJsonNull()) return List.of();
        if (el.isJsonPrimitive()) {
            float v = el.getAsFloat();
            return List.of(new Keyframe(0f, v, 0f, 0f), new Keyframe(1f, v, 0f, 0f));
        }
        return readKeys(el);
    }

    private static List<Keyframe> readKeys(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) return List.of();
        JsonArray arr = null;
        if (el.isJsonArray()) {
            arr = el.getAsJsonArray();
        } else if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("keys") && obj.get("keys").isJsonArray()) arr = obj.getAsJsonArray("keys");
            else if (obj.has("points") && obj.get("points").isJsonArray()) arr = obj.getAsJsonArray("points");
            else if (obj.has("keyframes") && obj.get("keyframes").isJsonArray()) arr = obj.getAsJsonArray("keyframes");
            else if (obj.has("curve") && obj.get("curve").isJsonArray()) arr = obj.getAsJsonArray("curve");
        }
        if (arr == null) return List.of();
        List<Keyframe> out = new ArrayList<>(arr.size());
        for (JsonElement item : arr) {
            if (item.isJsonArray()) {
                JsonArray pair = item.getAsJsonArray();
                if (pair.size() >= 2) {
                    float t = pair.get(0).getAsFloat();
                    float v = pair.get(1).getAsFloat();
                    float inT = pair.size() > 2 ? pair.get(2).getAsFloat() : 0f;
                    float outT = pair.size() > 3 ? pair.get(3).getAsFloat() : inT;
                    out.add(new Keyframe(t, v, inT, outT));
                }
            } else if (item.isJsonObject()) {
                JsonObject k = item.getAsJsonObject();
                float time = readFloat(k, "time", readFloat(k, "t", 0f));
                float value = readFloat(k, "value", readFloat(k, "v", 0f));
                float inT = readFloat(k, "in_tangent", readFloat(k, "inTangent", readFloat(k, "in", 0f)));
                float outT = readFloat(k, "out_tangent", readFloat(k, "outTangent", readFloat(k, "out", inT)));
                out.add(new Keyframe(time, value, inT, outT));
            }
        }
        return out;
    }

    private static float readFloat(JsonObject obj, String key, float fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) return fallback;
        try {
            float v = obj.get(key).getAsFloat();
            return Float.isFinite(v) ? v : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
