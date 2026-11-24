package com.kltyton.lumenfx.effect.asset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.effect.curve.ColorFunction;
import com.kltyton.lumenfx.effect.curve.FloatCurve;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Shared scalar, vector, and resource field semantics for effect JSON readers. */
public final class EffectJsonFields {
    private EffectJsonFields() { }

    public static @Nullable JsonObject jsonObject(@Nullable JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    public static @Nullable JsonArray jsonArray(@Nullable JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    public static @Nullable String jsonString(@Nullable JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    public static List<ScalarFunction> components(JsonObject object, String name, int count) {
        List<ScalarFunction> values = new ArrayList<>(count);
        if (!object.has(name)) {
            for (int i = 0; i < count; i++) values.add(ScalarFunction.constant(0));
            return values;
        }
        JsonArray array = object.getAsJsonArray(name);
        if (array.size() != count) throw new JsonParseException("Invalid component count for " + name);
        for (JsonElement element : array) values.add(scalar(element));
        return values;
    }

    public static ColorFunction color(JsonObject object, String name) {
        if (!object.has(name)) return ColorFunction.white();
        JsonElement value = object.get(name);
        boolean randomTime = false, minimumGammaOutput = false, maximumGammaOutput = false;
        if (value.isJsonObject()) {
            JsonObject color = value.getAsJsonObject();
            if (color.has("components")) {
                randomTime = color.has("random_time") && color.get("random_time").getAsBoolean();
                minimumGammaOutput = color.has("minimum_gamma_output") && color.get("minimum_gamma_output").getAsBoolean();
                maximumGammaOutput = color.has("maximum_gamma_output") && color.get("maximum_gamma_output").getAsBoolean();
                value = color.get("components");
            } else if (color.has("random_time")) {
                randomTime = true;
                value = color.get("random_time");
            }
        }
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 4)
            throw new JsonParseException("Expected four color components for " + name);
        JsonArray fields = value.getAsJsonArray();
        return new ColorFunction(scalar(fields.get(0)), scalar(fields.get(1)), scalar(fields.get(2)),
                scalar(fields.get(3)), randomTime, minimumGammaOutput, maximumGammaOutput);
    }

    public static ScalarFunction scalar(JsonObject object, String name, double defaultValue) {
        return object.has(name) ? scalar(object.get(name)) : ScalarFunction.constant(defaultValue);
    }

    public static ScalarFunction scalar(JsonElement element) {
        if (element.isJsonPrimitive()) return ScalarFunction.constant(element.getAsDouble());
        JsonObject object = element.getAsJsonObject();
        if (object.has("multiply") || object.has("add")) {
            List<ScalarFunction> factors = new ArrayList<>();
            boolean add = object.has("add");
            for (JsonElement factor : object.getAsJsonArray(add ? "add" : "multiply")) factors.add(scalar(factor));
            return add ? ScalarFunction.add(factors) : ScalarFunction.multiply(factors);
        }
        if (object.has("min")) return new ScalarFunction(curve(object.get("min")), curve(object.get("max")));
        FloatCurve curve = curve(element);
        return new ScalarFunction(curve, curve);
    }

    public static FloatCurve curve(JsonElement value) {
        if (value.isJsonPrimitive()) return FloatCurve.constant(value.getAsDouble());
        if (value.getAsJsonObject().has("lookup")) {
            JsonObject table = value.getAsJsonObject().getAsJsonObject("lookup");
            List<Float> samples = new ArrayList<>();
            for (JsonElement sample : table.getAsJsonArray("values")) samples.add(sample.getAsFloat());
            return FloatCurve.fromLookup(table.get("minimum_time").getAsFloat(),
                    table.get("inverse_time_range").getAsFloat(), samples);
        }
        List<FloatCurve.Key> keys = new ArrayList<>();
        var settings = value.getAsJsonObject();
        for (JsonElement element : settings.getAsJsonArray("keys")) {
            JsonObject key = element.getAsJsonObject();
            keys.add(new FloatCurve.Key(key.get("time").getAsDouble(), key.get("value").getAsDouble(),
                    number(key, "arrive", 0), number(key, "leave", 0),
                    FloatCurve.Interpolation.valueOf(string(key, "interpolation", "linear").toUpperCase(Locale.ROOT)),
                    key.has("arrive_weight") ? key.get("arrive_weight").getAsDouble() : null,
                    key.has("leave_weight") ? key.get("leave_weight").getAsDouble() : null));
        }
        return new FloatCurve(keys,
                FloatCurve.Wrap.valueOf(string(settings, "pre_wrap", "clamp").toUpperCase(Locale.ROOT)),
                FloatCurve.Wrap.valueOf(string(settings, "post_wrap", "clamp").toUpperCase(Locale.ROOT)));
    }

    public static Vec3 vector(JsonObject object, String name, Vec3 defaultValue) {
        if (!object.has(name)) return defaultValue;
        JsonArray array = object.getAsJsonArray(name);
        if (array.size() != 3) throw new JsonParseException("Expected three components for " + name);
        return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }

    public static String string(JsonObject object, String name, String defaultValue) {
        JsonElement value = object.get(name);
        return value != null && !value.isJsonNull() ? value.getAsString() : defaultValue;
    }
    public static double number(JsonObject object, String name, double defaultValue) {
        JsonElement value = object.get(name);
        return value != null && !value.isJsonNull() ? value.getAsDouble() : defaultValue;
    }
    public static int integer(JsonObject object, String name, int defaultValue) {
        JsonElement value = object.get(name);
        return value != null && !value.isJsonNull() ? value.getAsInt() : defaultValue;
    }
    public static boolean bool(JsonObject object, String name, boolean defaultValue) {
        JsonElement value = object.get(name);
        return value != null && !value.isJsonNull() ? value.getAsBoolean() : defaultValue;
    }
}
