package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessRange;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.math.NativeStatelessMath;

/** Integrated rotation curves supply current and previous deltas before native quaternion composition. */
public final class MeshRotationRateModule implements StatelessParticleSimulation.Module {
    private final int current, previous;
    private final StatelessRange rotation;
    private final StatelessDistribution curve;
    private final int[][] angles = new int[6][], output = new int[8][];
    private int capacity;
    public MeshRotationRateModule(JsonObject source) {
        current = source.get("current").getAsInt(); previous = source.get("previous").getAsInt();
        rotation = StatelessRange.decode(source.getAsJsonObject("rotation"));
        if (rotation.width() != 3) throw new IllegalArgumentException("Invalid mesh rotation rate range");
        var words = source.getAsJsonArray("curve");
        if (words.size() != 3) throw new IllegalArgumentException("Invalid mesh rotation rate curve");
        curve = new StatelessDistribution((int)words.get(0).getAsLong(), (int)words.get(1).getAsLong(), (int)words.get(2).getAsLong());
    }
    @Override public void simulate(StatelessParticleSimulation context) {
        if (context.count() > capacity) {
            capacity = Math.max(context.count(), capacity * 2);
            for (int component = 0; component < angles.length; component++) angles[component] = new int[capacity];
        }
        float[] rate = context.scratchVector(0), multiply = context.scratchVector(1), previousMultiply = context.scratchVector(2);
        float[] bias = context.scratchVector(3), scale = context.scratchVector(4);
        for (int component = 0; component < 3; component++) {
            bias[component] = rotation.parameterOffset() < 0 ? rotation.minimum(component) : context.dynamicFloat(rotation.parameterOffset() + component);
            scale[component] = rotation.parameterOffset() < 0 ? rotation.maximum(component) - rotation.minimum(component) : 0f;
        }
        for (int row = 0; row < context.count(); row++) {
            context.randomFloats(row, 0, rate, 3);
            for (int component = 0; component < 3; component++) rate[component] = rate[component] * scale[component] + bias[component];
            curve.curve(context, context.normalizedAge(row), multiply, 3);
            curve.curve(context, context.previousNormalizedAge(row), previousMultiply, 3);
            for (int component = 0; component < 3; component++) {
                angles[component][row] = Float.floatToRawIntBits(context.lifetime(row) * rate[component] * multiply[component]);
                angles[3 + component][row] = Float.floatToRawIntBits(context.lifetime(row) * rate[component] * previousMultiply[component]);
            }
        }
        for (int component = 0; component < 4; component++) {
            output[component] = context.particleFloatWords(current < 0 ? -1 : current + component);
            output[4 + component] = context.particleFloatWords(previous < 0 ? -1 : previous + component);
        }
        NativeStatelessMath.applyMeshRotation(context.count(), angles, output);
    }
}
