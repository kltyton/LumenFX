package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import com.kltyton.lumenfx.math.SourceFloatMath;

/** The native CPU path rounds its table column and only applies the stored alias on a failed probability comparison. */
public record MeshIndexModule(int component, int index, int tableOffset, int tableLast) implements StatelessParticleSimulation.Module {
    public MeshIndexModule(JsonObject source) {
        this(source.get("component").getAsInt(), (int)source.get("index").getAsLong(), source.get("table_offset").getAsInt(), source.get("table_last").getAsInt());
    }
    @Override public void simulate(StatelessParticleSimulation context) {
        boolean parameter = (index & Integer.MIN_VALUE) != 0;
        int value = index & Integer.MAX_VALUE;
        if (parameter) value = context.dynamicWord(value);
        float[] random = context.scratchVector(0);
        for (int row = 0; row < context.count(); row++) {
            int selected = value;
            if (tableLast > 0) {
                context.randomFloats(row, 0, random, 2);
                selected = SourceFloatMath.roundToInt(random[0] * (float)tableLast);
                float probability = context.staticFloat(tableOffset + selected * 2);
                if (random[1] > probability) selected = SourceFloatMath.truncToInt(context.staticFloat(tableOffset + selected * 2 + 1));
            }
            context.particleInt(component, row, selected);
        }
    }
}
