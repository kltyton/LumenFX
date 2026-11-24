package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Prepared ranges use literal endpoints or one typed renderer-store value for both endpoints. */
public final class StatelessRange {
    private final float[] minimum, maximum;
    private final int parameterOffset;
    private StatelessRange(float[] minimum, float[] maximum, int parameterOffset) {
        if (minimum.length == 0 || minimum.length != maximum.length || parameterOffset < -1)
            throw new IllegalArgumentException("Invalid stateless range layout");
        this.minimum = minimum; this.maximum = maximum; this.parameterOffset = parameterOffset;
    }
    public static StatelessRange decode(JsonObject source) {
        return new StatelessRange(floats(source.getAsJsonArray("minimum")), floats(source.getAsJsonArray("maximum")), source.get("parameter_offset").getAsInt());
    }
    public int width() { return minimum.length; }
    public int parameterOffset() { return parameterOffset; }
    public float minimum(int component) { return minimum[component]; }
    public float maximum(int component) { return maximum[component]; }
    public float[] minimumValues() { return minimum.clone(); }
    public float minimum(int component, int[] parameters) {
        return parameterOffset < 0 ? minimum[component] : Float.intBitsToFloat(parameters[parameterOffset + component]);
    }
    public float maximum(int component, int[] parameters) {
        return parameterOffset < 0 ? maximum[component] : Float.intBitsToFloat(parameters[parameterOffset + component]);
    }
    public float[] bias(int[] parameters, float factor) {
        float[] result = new float[minimum.length];
        for (int component = 0; component < result.length; component++) result[component] = minimum(component, parameters) * factor;
        return result;
    }
    public float[] scale(int[] parameters, float factor) {
        float[] result = new float[minimum.length];
        for (int component = 0; component < result.length; component++) result[component] = (maximum(component, parameters) - minimum(component, parameters)) * factor;
        return result;
    }
    private static float[] floats(JsonArray values) {
        float[] result = new float[values.size()];
        for (int index = 0; index < result.length; index++) result[index] = Float.intBitsToFloat((int)values.get(index).getAsLong());
        return result;
    }
}
