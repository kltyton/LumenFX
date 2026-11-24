package com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.niagara.grid.VelocityGridDefinition;
import com.mojang.logging.LogUtils;
import java.util.function.IntBinaryOperator;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/** Game-thread parameters and size transitions of the native Velocity/Pressure Grid DI. */
public final class NiagaraVelocityGrid {
    private static final Logger LOGGER = LogUtils.getLogger();
    public enum Function { SetGridDimension, UpdateGridTransform }
    @FunctionalInterface public interface Output { void set(int word, int lane, int value); }

    public record Size(int x, int y, int z) {
        public long words(int attributes) {
            return Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(x + 1L, y + 1L), z + 1L), attributes);
        }
        public boolean valid() { return x > 0 && y > 0 && z > 0; }
    }
    public record Snapshot(Size size, boolean allocated, boolean pressure, int attributes,
                           long allocation, float[] transform, float[] inverse) {
        public Snapshot { transform = transform.clone(); inverse = inverse.clone(); }
        @Override public float[] transform() { return transform.clone(); }
        @Override public float[] inverse() { return inverse.clone(); }
    }

    private final VelocityGridDefinition definition;
    private final boolean ownsBuffers;
    private Size size, pendingSize;
    private long allocation;
    private final Matrix4d transform = new Matrix4d();
    private final Matrix4d inverse = new Matrix4d();

    public NiagaraVelocityGrid(VelocityGridDefinition definition) {
        this.definition = definition;
        var authored = new Size(Math.clamp(definition.x(), 0, 50), Math.clamp(definition.y(), 0, 50),
                Math.clamp(definition.z(), 0, 50));
        if (authored.x() != definition.x() || authored.y() != definition.y() || authored.z() != definition.z())
            LOGGER.warn("Niagara Velocity/Pressure Grid authored size ({}, {}, {}) is outside 0–50",
                    definition.x(), definition.y(), definition.z());
        ownsBuffers = authored.valid();
        size = ownsBuffers ? authored : new Size(1, 1, 1);
    }

    public synchronized void begin(Pose owner) {
        NiagaraComponentMatrices.source(owner, 0, 0, 0, true, transform);
        if (pendingSize != null) {
            size = pendingSize;
            pendingSize = null;
            allocation++;
        }
    }

    public synchronized void execute(Function function, int count, IntBinaryOperator input, Output output) {
        for (int lane = 0; lane < count; lane++) {
            switch (function) {
                case SetGridDimension -> pendingSize = new Size((int)number(input, 0, lane), (int)number(input, 1, lane),
                        (int)number(input, 2, lane));
                case UpdateGridTransform -> {
                    transform.set(number(input, 0, lane), number(input, 1, lane), number(input, 2, lane), number(input, 3, lane),
                            number(input, 4, lane), number(input, 5, lane), number(input, 6, lane), number(input, 7, lane),
                            number(input, 8, lane), number(input, 9, lane), number(input, 10, lane), number(input, 11, lane),
                            number(input, 12, lane), number(input, 13, lane), number(input, 14, lane), number(input, 15, lane));
                    NiagaraComponentMatrices.inverse(transform, inverse);
                }
            }
            output.set(0, lane, -1);
        }
    }

    public synchronized Snapshot capture() {
        return new Snapshot(size, ownsBuffers && size.valid(), definition.pressure(), definition.attributes(), allocation,
                new Matrix4f(transform).get(new float[16]), new Matrix4f(inverse).get(new float[16]));
    }

    private static float number(IntBinaryOperator input, int word, int lane) {
        return Float.intBitsToFloat(input.applyAsInt(word, lane));
    }
}
