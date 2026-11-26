package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import javax.annotation.Nullable;

/** Evaluated at each particle's normalized age for the authored material's custom input. */
public record MaterialDataCurves(ScalarCurve x, ScalarCurve y, ScalarCurve z, ScalarCurve w) {
    private static final ScalarCurve ZERO = ScalarCurve.constant(0f);
    public static final MaterialDataCurves EMPTY = new MaterialDataCurves(ZERO, ZERO, ZERO, ZERO);

    public static MaterialDataCurves fromJson(@Nullable JsonElement value) {
        if (value == null || !value.isJsonArray()) return EMPTY;
        JsonArray array = value.getAsJsonArray();
        return new MaterialDataCurves(channel(array, 0), channel(array, 1), channel(array, 2), channel(array, 3));
    }

    private static ScalarCurve channel(JsonArray values, int index) {
        return index < values.size() ? ScalarCurve.fromJson(values.get(index), 0f) : ZERO;
    }
}

