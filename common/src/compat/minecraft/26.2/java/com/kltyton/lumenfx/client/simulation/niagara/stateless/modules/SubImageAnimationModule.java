package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.math.NativeStatelessMath;

/** Native unsigned seed conversion preserves direct, looped, lifetime-linear and random-frame sampling. */
public final class SubImageAnimationModule implements StatelessParticleSimulation.Module {
    private final int component, mode, frames, minimum, maximum, parameterOffset;
    private final float[] parameters = new float[7];
    private final int[][] required = new int[3][], output = new int[1][];
    public SubImageAnimationModule(JsonObject source) {
        component = source.get("component").getAsInt(); mode = source.get("mode").getAsInt(); frames = source.get("frames").getAsInt();
        JsonObject initial = source.getAsJsonObject("initial");
        minimum = initial.get("minimum").getAsInt(); maximum = initial.get("maximum").getAsInt(); parameterOffset = initial.get("parameter_offset").getAsInt();
        if (frames > 1) {
            parameters[0] = frames; parameters[3] = source.get("seed_rate").getAsFloat();
            parameters[4] = source.get("frame_start").getAsInt() / (float)frames;
            parameters[5] = source.get("frame_range").getAsInt() / (float)frames;
            parameters[6] = source.get("rate_scale").getAsFloat();
        }
    }
    @Override public void simulate(StatelessParticleSimulation context) {
        if (frames > 1) {
            int bias = parameterOffset < 0 ? minimum : context.dynamicWord(parameterOffset);
            int scale = parameterOffset < 0 ? maximum - minimum : 0;
            parameters[1] = scale / (float)frames; parameters[2] = bias / (float)frames;
        }
        required[0] = context.requiredWords(StatelessParticleSimulation.Required.UNIQUE_ID);
        required[1] = context.requiredWords(StatelessParticleSimulation.Required.AGE);
        required[2] = context.requiredWords(StatelessParticleSimulation.Required.NORMALIZED_AGE);
        output[0] = context.particleFloatWords(component);
        NativeStatelessMath.subImage(context.count(), context.emitterSeed(), context.moduleSeed(), mode, required, output, parameters);
    }
}
