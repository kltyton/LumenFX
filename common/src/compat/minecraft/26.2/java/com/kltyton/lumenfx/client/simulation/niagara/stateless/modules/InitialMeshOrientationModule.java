package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessRange;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.math.NativeStatelessMath;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.shader.StatelessShaderFields;

/** Initial orientation preserves source axis alignment, degree conversion and per-particle random rotations. */
public final class InitialMeshOrientationModule implements PreparedShaderModule {
    private final StatelessRange rotation, from, to;
    private final int mode, current, previous;
    private final float[] input = new float[12], prepared = new float[6];
    private final int[][] required = new int[1][], output = new int[8][];
    public InitialMeshOrientationModule(JsonObject source) {
        mode = source.get("mode").getAsInt(); current = source.get("current").getAsInt(); previous = source.get("previous").getAsInt();
        rotation = range(source, "rotation"); from = range(source, "from"); to = range(source, "to");
    }
    @Override public void prepare(int[] parameters, float[] sourcePose) {
        for (int component = 0; component < 3; component++) {
            float minimum = rotation.minimum(component, parameters);
            input[component] = rotation.maximum(component, parameters) - minimum;
            input[3 + component] = minimum;
            input[6 + component] = from.minimum(component, parameters);
            input[9 + component] = to.minimum(component, parameters);
        }
        NativeStatelessMath.prepareInitialOrientation(mode, input, prepared);
    }
    @Override public void simulate(StatelessParticleSimulation context) {
        required[0] = context.requiredWords(StatelessParticleSimulation.Required.UNIQUE_ID);
        for (int component = 0; component < 4; component++) {
            output[component] = context.particleFloatWords(current < 0 ? -1 : current + component);
            output[4 + component] = context.particleFloatWords(previous < 0 ? -1 : previous + component);
        }
        NativeStatelessMath.initialOrientation(context.count(), context.emitterSeed(), context.moduleSeed(), required, output, prepared);
    }
    @Override public void writeShaderParameters(StatelessShaderFields fields) {
        fields.floats("InitialMeshOrientation_RotationScale", prepared, 0, 3);
        fields.floats("InitialMeshOrientation_RotationBias", prepared, 3, 3);
    }
    private static StatelessRange range(JsonObject source, String name) {
        StatelessRange result = StatelessRange.decode(source.getAsJsonObject(name));
        if (result.width() != 3) throw new IllegalArgumentException("Invalid initial mesh orientation range " + name);
        return result;
    }
}
