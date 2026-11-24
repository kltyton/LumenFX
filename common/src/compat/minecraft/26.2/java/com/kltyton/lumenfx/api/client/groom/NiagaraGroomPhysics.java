package com.kltyton.lumenfx.api.client.groom;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Solver, constraint and per-node scale values sampled from one groom group's physics settings. */
public record NiagaraGroomPhysics(int strandSize, int subSteps, int iterationCount, float gravityPreloading,
                                 Forces forces, Spring bend, Spring stretch, Collision collision,
                                 Strand strand, Scales scales) {
    public record Forces(Vec3 gravity, float airDrag, Vec3 airVelocity) {
        public Forces {
            Objects.requireNonNull(gravity);
            Objects.requireNonNull(airVelocity);
        }
    }

    public record Spring(boolean solve, boolean project, float damping, float stiffness) { }

    public record Collision(boolean solve, boolean project, float staticFriction, float kineticFriction,
                            float viscosity, int gridX, int gridY, int gridZ, float radius) { }

    public record Strand(float density, float smoothing, float thickness) { }

    public static final class Scales {
        public static final int NODE_CAPACITY = 32;
        private static final int FLOATS = 4 * NODE_CAPACITY;
        private final float[] values;

        /** Arrays use native Stretch, Bend, Radius and Thickness order, with 32 entries per scale. */
        public Scales(float[] values) {
            if (values.length != FLOATS) throw new IllegalArgumentException("Groom scale table requires four 32-node rows");
            this.values = values.clone();
        }

        public static Scales unit() {
            var values = new float[FLOATS];
            Arrays.fill(values, 1);
            return new Scales(values);
        }

        public int bytes() { return Float.BYTES * FLOATS; }

        public void write(ByteBuffer target) {
            for (float value : values) target.putFloat(value);
        }
    }

    public NiagaraGroomPhysics {
        if (strandSize < 0 || strandSize > Scales.NODE_CAPACITY)
            throw new IllegalArgumentException("Invalid Niagara groom strand size: " + strandSize);
        Objects.requireNonNull(forces);
        Objects.requireNonNull(bend);
        Objects.requireNonNull(stretch);
        Objects.requireNonNull(collision);
        Objects.requireNonNull(strand);
        Objects.requireNonNull(scales);
    }
}
