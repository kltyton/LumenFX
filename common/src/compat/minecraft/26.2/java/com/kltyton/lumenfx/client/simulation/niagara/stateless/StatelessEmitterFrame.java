package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Immutable analytic state can cross from simulation ownership to render demand generation. */
public final class StatelessEmitterFrame {
    public static final class Bindings {
        private final int[] words;
        Bindings(int[] words) { this.words = words.clone(); }
        public int[] copyWords() { return words.clone(); }
        public int byteSize() { return Math.multiplyExact(words.length, Integer.BYTES); }
    }
    private static final int[] POSE_FIELDS = { 480, 484, 488, 492, 496, 500, 504, 576, 580, 584 };
    private final int seed, executionState, features;
    private final float age, delta;
    private final boolean eligible;
    private final Bindings bindings;
    private final List<StatelessSpawnSchedule.Info> spawns;
    private final float[] pose;

    StatelessEmitterFrame(int seed, float age, float delta, int executionState, int features, boolean eligible,
                          Bindings bindings, List<StatelessSpawnSchedule.Info> spawns, byte[] runtime) {
        this(seed, age, delta, executionState, features, eligible, bindings, spawns, pose(runtime));
    }
    private StatelessEmitterFrame(int seed, float age, float delta, int executionState, int features, boolean eligible,
                                 Bindings bindings, List<StatelessSpawnSchedule.Info> spawns, float[] ownedPose) {
        this.seed = seed; this.age = age; this.delta = delta; this.executionState = executionState;
        this.features = features; this.eligible = eligible; this.bindings = bindings;
        this.spawns = spawns; pose = ownedPose;
    }
    public int seed() { return seed; }
    public float age() { return age; }
    public float delta() { return delta; }
    public int executionState() { return executionState; }
    public int features() { return features; }
    public boolean eligible() { return eligible; }
    public Bindings bindings() { return bindings; }
    public float[] sourcePose() { return pose.clone(); }
    public StatelessSpawnSchedule.Plan plan() { return StatelessSpawnSchedule.plan(spawns, age); }
    public StatelessEmitterFrame withDelta(float delta) {
        return new StatelessEmitterFrame(seed, age, delta, executionState, features, eligible, bindings, spawns, pose);
    }
    public StatelessEmitterFrame restartClock(int seed) {
        return new StatelessEmitterFrame(seed, 0f, 0f, 0, features, eligible, bindings, spawns, pose);
    }
    private static float[] pose(byte[] runtime) {
        ByteBuffer source = ByteBuffer.wrap(runtime).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[POSE_FIELDS.length];
        for (int index = 0; index < result.length; index++) result[index] = source.getFloat(POSE_FIELDS[index]);
        return result;
    }
}
