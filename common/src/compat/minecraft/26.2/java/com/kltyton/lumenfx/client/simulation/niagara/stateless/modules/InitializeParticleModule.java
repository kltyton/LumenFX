package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import java.util.Objects;
import java.util.function.Function;

/** Initial values and their previous-frame partners consume one prepared shader-parameter snapshot. */
public final class InitializeParticleModule implements StatelessParticleSimulation.Module {
    public record Channels(int position, int color, int ribbonWidth, int spriteSize, int spriteRotation, int scale,
                           int previousPosition, int previousRibbonWidth, int previousSpriteSize,
                           int previousSpriteRotation, int previousScale) { }
    public record Parameters(float ribbonWidthScale, float ribbonWidthBias, float[] spriteSizeScale, float[] spriteSizeBias,
                             float spriteRotationScale, float spriteRotationBias, float[] meshScaleScale, float[] meshScaleBias) {
        public Parameters {
            if (spriteSizeScale.length != 2 || spriteSizeBias.length != 2 || meshScaleScale.length != 3 || meshScaleBias.length != 3)
                throw new IllegalArgumentException("Invalid initialize-particle parameter width");
            spriteSizeScale = spriteSizeScale.clone(); spriteSizeBias = spriteSizeBias.clone();
            meshScaleScale = meshScaleScale.clone(); meshScaleBias = meshScaleBias.clone();
        }
        @Override public float[] spriteSizeScale() { return spriteSizeScale.clone(); }
        @Override public float[] spriteSizeBias() { return spriteSizeBias.clone(); }
        @Override public float[] meshScaleScale() { return meshScaleScale.clone(); }
        @Override public float[] meshScaleBias() { return meshScaleBias.clone(); }
    }
    private final Channels channels;
    private final StatelessDistribution initialPosition, initialColor;
    private final boolean uniformSpriteSize, uniformMeshScale;
    private final Function<StatelessParticleSimulation, Parameters> parameters;

    public InitializeParticleModule(Channels channels, StatelessDistribution initialPosition, StatelessDistribution initialColor,
                                    boolean uniformSpriteSize, boolean uniformMeshScale, Function<StatelessParticleSimulation, Parameters> parameters) {
        this.channels = Objects.requireNonNull(channels);
        this.initialPosition = Objects.requireNonNull(initialPosition); this.initialColor = Objects.requireNonNull(initialColor);
        this.uniformSpriteSize = uniformSpriteSize; this.uniformMeshScale = uniformMeshScale;
        this.parameters = Objects.requireNonNull(parameters);
    }

    @Override public void simulate(StatelessParticleSimulation context) {
        Parameters frame = Objects.requireNonNull(parameters.apply(context), "Initialize-particle parameters were not prepared");
        float[] position = context.scratchVector(0), color = context.scratchVector(1), spriteSize = context.scratchVector(2), scale = context.scratchVector(3);
        for (int row = 0; row < context.count(); row++) {
            // Native initialization passes the signed int32 UniqueID as lookup time, while the RNG uses its uint32 bits.
            float time = context.uniqueId(row);
            initialPosition.sample(context, row, 0, time, position, 3);
            initialColor.sample(context, row, 1, time, color, 4);
            float ribbonWidth = frame.ribbonWidthBias + context.randomFloat(row, 2) * frame.ribbonWidthScale;
            randomScaleBias(context, row, 3, frame.spriteSizeScale, frame.spriteSizeBias, spriteSize, uniformSpriteSize);
            float rotation = frame.spriteRotationBias + context.randomFloat(row, 4) * frame.spriteRotationScale;
            randomScaleBias(context, row, 5, frame.meshScaleScale, frame.meshScaleBias, scale, uniformMeshScale);
            write(context, channels.position, row, position, 3); write(context, channels.previousPosition, row, position, 3);
            write(context, channels.color, row, color, 4);
            context.particleFloat(channels.ribbonWidth, row, ribbonWidth); context.particleFloat(channels.previousRibbonWidth, row, ribbonWidth);
            write(context, channels.spriteSize, row, spriteSize, 2); write(context, channels.previousSpriteSize, row, spriteSize, 2);
            context.particleFloat(channels.spriteRotation, row, rotation); context.particleFloat(channels.previousSpriteRotation, row, rotation);
            write(context, channels.scale, row, scale, 3); write(context, channels.previousScale, row, scale, 3);
        }
    }

    private static void randomScaleBias(StatelessParticleSimulation context, int row, int seed, float[] scale, float[] bias,
                                       float[] output, boolean uniform) {
        context.randomFloats(row, seed, output, scale.length);
        for (int component = 0; component < scale.length; component++) output[component] = bias[component] + output[component] * scale[component];
        if (uniform) for (int component = 1; component < scale.length; component++) output[component] = output[0];
    }
    private static void write(StatelessParticleSimulation context, int firstComponent, int row, float[] values, int width) {
        if (firstComponent < 0) return;
        for (int component = 0; component < width; component++) context.particleFloat(firstComponent + component, row, values[component]);
    }
}
