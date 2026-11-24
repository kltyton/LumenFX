package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessRange;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.math.NativeStatelessMath;

/** Source orbit displacement uses normalized age with independently sampled center, phase, radius and rotation. */
public final class OrbitModule implements PreparedModule {
    private final StatelessRange rate, radius, phase, center, rotation;
    private final int current, previous, centerSpace, rotationSpace;
    private final float[] input = new float[18], prepared = new float[18];
    private final int[][] required = new int[3][], output = new int[6][];
    public OrbitModule(JsonObject source) {
        current = source.get("current").getAsInt(); previous = source.get("previous").getAsInt();
        centerSpace = source.get("center_space").getAsInt(); rotationSpace = source.get("rotation_space").getAsInt();
        rate = range(source, "rate", 1); radius = range(source, "radius", 1); phase = range(source, "phase", 1);
        center = range(source, "center", 3); rotation = range(source, "rotation", 3);
    }
    @Override public void prepare(int[] parameters, float[] sourcePose) {
        pair(rate, parameters, 0, 1); pair(radius, parameters, 2, 3); pair(phase, parameters, 4, 5);
        pair(center, parameters, 6, 9); pair(rotation, parameters, 12, 15);
        NativeStatelessMath.prepareOrbit(centerSpace, rotationSpace, input, sourcePose, prepared);
    }
    private void pair(StatelessRange range, int[] parameters, int bias, int scale) {
        for (int component = 0; component < range.width(); component++) {
            float minimum = range.minimum(component, parameters);
            input[bias + component] = minimum; input[scale + component] = range.maximum(component, parameters) - minimum;
        }
    }
    @Override public void simulate(StatelessParticleSimulation context) {
        required[0] = context.requiredWords(StatelessParticleSimulation.Required.UNIQUE_ID);
        required[1] = context.requiredWords(StatelessParticleSimulation.Required.NORMALIZED_AGE);
        required[2] = context.requiredWords(StatelessParticleSimulation.Required.PREVIOUS_NORMALIZED_AGE);
        for (int component = 0; component < 3; component++) {
            output[component] = context.particleFloatWords(current < 0 ? -1 : current + component);
            output[3 + component] = context.particleFloatWords(previous < 0 ? -1 : previous + component);
        }
        NativeStatelessMath.orbit(context.count(), context.emitterSeed(), context.moduleSeed(), required, output, prepared);
    }
    private static StatelessRange range(JsonObject source, String name, int width) {
        StatelessRange value = StatelessRange.decode(source.getAsJsonObject(name));
        if (value.width() != width) throw new IllegalArgumentException("Invalid stateless orbit range " + name);
        return value;
    }
}
