package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.math.NativeStatelessMath;
import org.jspecify.annotations.Nullable;

/** Decal orientation, size and fade share source random call zero while preserving optional output attributes. */
public final class DecalAttributesModule implements PreparedModule {
    private final int orientationComponent, sizeComponent, fadeComponent, space;
    private final @Nullable StatelessDistribution orientation, size, fade;
    private final boolean scaleSize;
    private final float[] sizeAmount = new float[3], sizeFactor = new float[3], rotation = new float[4];
    private final int[][] angles = new int[3][], output = new int[4][];
    private int capacity;
    public DecalAttributesModule(JsonObject source, JsonObject ownerScale) {
        orientationComponent = source.get("orientation_component").getAsInt(); sizeComponent = source.get("size_component").getAsInt();
        fadeComponent = source.get("fade_component").getAsInt(); space = source.get("orientation_space").getAsInt();
        orientation = distribution(source, "orientation"); size = distribution(source, "size"); fade = distribution(source, "fade");
        scaleSize = ownerScale.has("bScaleInitialDecalSize") && ownerScale.get("bScaleInitialDecalSize").getAsBoolean();
        JsonObject amount = ownerScale.has("ScaleInitialDecalSizeScaleAmount") ? ownerScale.getAsJsonObject("ScaleInitialDecalSizeScaleAmount") : new JsonObject();
        String[] axes = { "X", "Y", "Z" };
        for (int axis = 0; axis < 3; axis++) sizeAmount[axis] = amount.has(axes[axis]) ? amount.get(axes[axis]).getAsFloat() : 1f;
    }
    @Override public void prepare(int[] parameters, float[] sourcePose) {
        NativeStatelessMath.toSimulationRotation(space, sourcePose, rotation);
        for (int component = 0; component < 3; component++) sizeFactor[component] = scaleSize ? 1f + (sourcePose[7 + component] - 1f) * sizeAmount[component] : 1f;
    }
    @Override public void simulate(StatelessParticleSimulation context) {
        if (orientationComponent >= 0 && context.count() > capacity) {
            capacity = Math.max(context.count(), capacity * 2);
            for (int component = 0; component < 3; component++) angles[component] = new int[capacity];
        }
        float[] value = context.scratchVector(0);
        for (int row = 0; row < context.count(); row++) {
            float age = context.normalizedAge(row);
            if (orientationComponent >= 0) {
                orientation.sample(context, row, 0, age, value, 3);
                for (int component = 0; component < 3; component++) angles[component][row] = Float.floatToRawIntBits(value[component]);
            }
            if (sizeComponent >= 0) {
                size.sample(context, row, 0, age, value, 3);
                for (int component = 0; component < 3; component++) context.particleFloat(sizeComponent + component, row, value[component] * sizeFactor[component]);
            }
            if (fadeComponent >= 0) {
                fade.sample(context, row, 0, age, value, 1); context.particleFloat(fadeComponent, row, value[0]);
            }
        }
        if (orientationComponent >= 0) {
            for (int component = 0; component < 4; component++) output[component] = context.particleFloatWords(orientationComponent + component);
            NativeStatelessMath.decalOrientation(context.count(), angles, output, rotation);
        }
    }
    private static @Nullable StatelessDistribution distribution(JsonObject source, String name) {
        if (!source.has(name)) return null;
        var values = source.getAsJsonArray(name);
        if (values.size() != 3) throw new IllegalArgumentException("Invalid stateless decal distribution " + name);
        return new StatelessDistribution((int)values.get(0).getAsLong(), (int)values.get(1).getAsLong(), (int)values.get(2).getAsLong());
    }
}
