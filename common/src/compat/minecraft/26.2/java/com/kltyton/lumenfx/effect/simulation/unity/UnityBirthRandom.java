package com.kltyton.lumenfx.effect.simulation.unity;

import java.util.List;

/** Reproduces Shuriken's four birth lanes and particle-seeded renderer choices. */
public final class UnityBirthRandom {
    private static final int LANES = 4;
    private static final int SEED_STRIDE = 367;
    private static final int INITIALIZE_MULTIPLIER = 1812433253;
    private static final int SPEED_SEED_OFFSET = 0x96aa4de3;
    private static final int MESH_SEED_OFFSET = 0xbc524e5f;
    private static final int ATLAS_ROW_SEED_OFFSET = 0xaf502044;

    public static final class Lane {
        private int x;
        private int y;
        private int z;
        private int w;
        private int particleSeed;
        private int lifetime;
        private int size;
        private int rotation;
        private int color;

        private Lane() { }

        private void reset(int seed) {
            x = seed;
            y = initializeWord(x);
            z = initializeWord(y);
            w = initializeWord(z);
        }

        private int next() {
            int bits = xorshift(x, w);
            x = y;
            y = z;
            z = w;
            w = bits;
            return w;
        }

        private void sample() {
            particleSeed = next();
            lifetime = next();
            size = next();
            rotation = next();
            color = next();
        }

        public double lifetime() { return fraction(lifetime); }
        public double size() { return fraction(size); }
        public double rotation() { return fraction(rotation); }
        public double color() { return fraction(color); }
        public double speed() {
            // Shuriken samples start speed from a separate stream keyed by the particle seed.
            return fraction(firstWord(particleSeed + SPEED_SEED_OFFSET));
        }
        public int randomAtlasRow(int rows) {
            float sample = (float) fraction(firstWord(particleSeed + ATLAS_ROW_SEED_OFFSET));
            return Math.min(rows - 1, (int) (sample * rows));
        }
        public int meshAtlasRow(int slots, int rows) {
            // Shuriken's sheet selects a uniform mesh slot even when geometry is weighted.
            int slot = Integer.remainderUnsigned(firstWord(particleSeed + MESH_SEED_OFFSET), slots);
            return (int) ((slot * (1f / slots)) * rows);
        }
        public int meshIndex(int slots, List<Float> weights) {
            int word = firstWord(particleSeed + MESH_SEED_OFFSET);
            float total = 0;
            for (float weight : weights) total += weight;
            if (total == 0) return Integer.remainderUnsigned(word, slots);
            float target = (float)fraction(word) * total;
            float cumulative = 0;
            int lastWeighted = 0;
            for (int i = 0; i < weights.size(); i++) {
                float weight = weights.get(i);
                if (weight > 0) lastWeighted = i;
                cumulative += weight;
                if (cumulative > target) return i;
            }
            return lastWeighted;
        }

        private static double fraction(int bits) {
            return (bits & 0x007fffff) / 8388607.0;
        }
    }

    private static int initializeWord(int seed) {
        return seed * INITIALIZE_MULTIPLIER + 1;
    }

    private static int firstWord(int seed) {
        return xorshift(seed, initializeWord(initializeWord(initializeWord(seed))));
    }

    private static int xorshift(int x, int w) {
        int shifted = x ^ (x << 11);
        return w ^ (w >>> 19) ^ shifted ^ (shifted >>> 8);
    }

    private final int initialSeed;
    private final Lane[] lanes = new Lane[LANES];
    private double birthTime;
    private int nextLane;

    public UnityBirthRandom(long seed) {
        initialSeed = (int) seed;
        for (int i = 0; i < LANES; i++) lanes[i] = new Lane();
        reset();
    }

    public void reset() {
        for (int i = 0; i < LANES; i++) lanes[i].reset(initialSeed + i * SEED_STRIDE);
        birthTime = Double.NaN;
        nextLane = 0;
    }

    /** Samples are consumed on the owning simulation thread before the next block. */
    public Lane next(double time) {
        // Continuous and burst births at the same instant share the four-wide initialization blocks.
        if (Double.isNaN(birthTime) || Math.abs(time - birthTime) > 8 * Math.ulp(Math.max(1, Math.abs(time)))) {
            birthTime = time;
            nextLane = 0;
        }
        int laneIndex = nextLane;
        nextLane = (nextLane + 1) % LANES;
        if (laneIndex == 0) {
            // A partial block still advances each lane.
            for (Lane lane : lanes) lane.sample();
        }
        return lanes[laneIndex];
    }
}
