package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessRange;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.math.NativeStatelessMath;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.shader.StatelessShaderFields;
import java.util.LinkedHashMap;
import java.util.Map;

/** Five native shape families share temporal offset sampling without changing their source random distributions. */
public final class ShapeLocationModule implements PreparedShaderModule {
    private final int current, previous, primitive;
    private final StatelessRange rotation, scale, offsetScale;
    private final Map<String, StatelessRange> shape = new LinkedHashMap<>();
    private final StatelessDistribution offset;
    private final int[] controls = new int[6], mode = new int[4];
    private final float[] input = new float[14], prepared = new float[15];
    private final int[][] required = new int[1][], offsets = new int[6][], output = new int[6][];
    private int capacity;
    public ShapeLocationModule(JsonObject source) {
        current = source.get("current").getAsInt(); previous = source.get("previous").getAsInt(); primitive = source.get("primitive").getAsInt();
        rotation = range(source, "rotation", 3); scale = range(source, "scale", 3); offsetScale = range(source, "offset_scale", 3);
        var words = source.getAsJsonArray("offset");
        if (words.size() != 3) throw new IllegalArgumentException("Invalid shape offset distribution");
        offset = new StatelessDistribution((int)words.get(0).getAsLong(), (int)words.get(1).getAsLong(), (int)words.get(2).getAsLong());
        controls[0] = primitive; controls[1] = source.get("space").getAsInt(); controls[2] = source.get("surface").getAsBoolean() ? 1 : 0;
        controls[3] = source.get("end_caps").getAsBoolean() ? 1 : 0; controls[4] = source.get("expansion").getAsInt();
        controls[5] = rotation.parameterOffset() >= 0 ? 1 : 0;
        switch (primitive) {
            case 0 -> { shape.put("size", range(source, "size", 3)); shape.put("thickness", range(source, "thickness", 1)); }
            case 1 -> {
                for (String name : new String[] { "height", "radius", "midpoint", "thickness" }) shape.put(name, range(source, name, 1));
            }
            case 2 -> { shape.put("size", range(source, "size", 2)); shape.put("thickness", range(source, "thickness", 1)); }
            case 3 -> { for (String name : new String[] { "radius", "coverage", "distribution" }) shape.put(name, range(source, name, 1)); }
            case 4 -> shape.put("radius", range(source, "radius", 1));
            default -> throw new IllegalArgumentException("Invalid active shape primitive " + primitive);
        }
    }
    @Override public void prepare(int[] parameters, float[] sourcePose) {
        for (int component = 0; component < 3; component++) {
            input[component] = rotation.minimum(component, parameters);
            input[4 + component] = scale.minimum(component, parameters); input[7 + component] = offsetScale.minimum(component, parameters);
        }
        if (rotation.parameterOffset() >= 0) input[3] = Float.intBitsToFloat(parameters[rotation.parameterOffset() + 3]);
        switch (primitive) {
            case 0, 2 -> {
                StatelessRange size = shape.get("size");
                for (int component = 0; component < size.width(); component++) input[10 + component] = size.minimum(component, parameters);
                input[13] = shape.get("thickness").minimum(0, parameters);
            }
            case 1 -> {
                input[10] = shape.get("height").minimum(0, parameters); input[11] = shape.get("radius").minimum(0, parameters);
                input[12] = shape.get("midpoint").minimum(0, parameters); input[13] = shape.get("thickness").minimum(0, parameters);
            }
            case 3 -> {
                input[10] = shape.get("radius").minimum(0, parameters); input[11] = shape.get("coverage").minimum(0, parameters);
                input[12] = shape.get("distribution").minimum(0, parameters);
            }
            case 4 -> {
                StatelessRange radius = shape.get("radius"); input[10] = radius.minimum(0, parameters);
                input[11] = radius.maximum(0, parameters) - input[10];
            }
        }
        NativeStatelessMath.prepareShape(controls, input, sourcePose, mode, prepared);
    }
    @Override public void simulate(StatelessParticleSimulation context) {
        if (context.count() > capacity) {
            capacity = Math.max(context.count(), capacity * 2);
            for (int component = 0; component < offsets.length; component++) offsets[component] = new int[capacity];
        }
        float[] currentOffset = context.scratchVector(0), previousOffset = context.scratchVector(1);
        for (int row = 0; row < context.count(); row++) {
            offset.samplePair(context, row, 1, context.normalizedAge(row), context.previousNormalizedAge(row), currentOffset, previousOffset, 3);
            for (int component = 0; component < 3; component++) {
                offsets[component][row] = Float.floatToRawIntBits(currentOffset[component]);
                offsets[3 + component][row] = Float.floatToRawIntBits(previousOffset[component]);
            }
        }
        required[0] = context.requiredWords(StatelessParticleSimulation.Required.UNIQUE_ID);
        for (int component = 0; component < 3; component++) {
            output[component] = context.particleFloatWords(current < 0 ? -1 : current + component);
            output[3 + component] = context.particleFloatWords(previous < 0 ? -1 : previous + component);
        }
        NativeStatelessMath.shape(context.count(), context.emitterSeed(), context.moduleSeed(), mode, prepared, required, offsets, output);
    }
    private static StatelessRange range(JsonObject source, String name, int width) {
        StatelessRange result = StatelessRange.decode(source.getAsJsonObject(name));
        if (result.width() != width) throw new IllegalArgumentException("Invalid stateless shape range " + name);
        return result;
    }
    @Override public void writeShaderParameters(StatelessShaderFields fields) {
        fields.words("ShapeLocation_Mode", mode, 0, 4);
        fields.floats("ShapeLocation_Parameters0", prepared, 0, 4);
        fields.floats("ShapeLocation_Parameters1", prepared, 4, 4);
        fields.floats("ShapeLocation_Parameters2", prepared, 8, 4);
        fields.floats("ShapeLocation_TransformOffsetScale", prepared, 12, 3);
    }
}
