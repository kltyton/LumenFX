package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.math.SourceFloatMath;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Analytic particle evaluation writes a destination dataset owned by one emitter worker. */
public final class StatelessParticleSimulation {
    public enum Required { UNIQUE_ID, LIFETIME, AGE, PREVIOUS_AGE, NORMALIZED_AGE, PREVIOUS_NORMALIZED_AGE }
    public record SpawnWindow(long active, int particleOffset, int uniqueOffset, float time, float interval,
                              float probability, float lifetimeScale, float lifetimeBias) {
        public SpawnWindow {
            if (active < 0 || active > 0xffff_ffffL) throw new IllegalArgumentException("Invalid stateless unsigned active count");
        }
    }
    @FunctionalInterface
    public interface Module { void simulate(StatelessParticleSimulation context); }
    public record Callback(int randomSeedOffset, Module module) {
        public Callback { Objects.requireNonNull(module); }
    }

    public void simulate(int emitterSeed, float emitterAge, float deltaTime, StatelessSpawnSchedule.Plan plan, int[] dynamicWords, int[] shaderWords) {
        simulate(emitterSeed, emitterAge, deltaTime, plan.windows(), Math.toIntExact(plan.active()), dynamicWords, shaderWords);
    }

    private static final int ALIVE = 0, LIFETIME = 1, AGE = 2, NORMALIZED_AGE = 3,
            PREVIOUS_AGE = 4, PREVIOUS_NORMALIZED_AGE = 5, UNIQUE_ID = 6, MATERIAL_RANDOM = 7;
    private final VectorDataSet destination;
    private final int uniqueComponent, materialRandomComponent;
    private final int[] staticWords;
    private final List<Callback> callbacks;
    private final int[][] required = new int[8][];
    private final int[] random = new int[4];
    private final float[][] scratchVectors = new float[5][4];
    final StatelessDistribution.Lookup distributionLookup = new StatelessDistribution.Lookup();
    private int[] dynamicWords = new int[0];
    private int[] shaderWords = new int[0];
    private int transientCapacity, instances, emitterSeed, moduleSeed;
    private float deltaTime, inverseDeltaTime;
    private boolean needsKillCompaction;

    public StatelessParticleSimulation(VectorDataSet destination, int uniqueComponent, int materialRandomComponent,
                                       int[] staticWords, List<Callback> callbacks) {
        this.destination = Objects.requireNonNull(destination);
        if (destination.componentCount(2) != 0 || uniqueComponent < -1 || uniqueComponent >= destination.componentCount(1)
                || materialRandomComponent < -1 || materialRandomComponent >= destination.componentCount(0))
            throw new IllegalArgumentException("Invalid stateless output component layout");
        this.uniqueComponent = uniqueComponent;
        this.materialRandomComponent = materialRandomComponent;
        this.staticWords = staticWords.clone();
        this.callbacks = List.copyOf(callbacks);
    }

    public void simulate(int emitterSeed, float emitterAge, float deltaTime, List<SpawnWindow> spawnWindows,
                         int activeParticles, int[] dynamicWords, int[] shaderWords) {
        if (activeParticles < 0 || spawnWindows.size() > 8)
            throw new IllegalArgumentException("Invalid stateless spawn window layout");
        this.emitterSeed = emitterSeed; moduleSeed = 0; instances = 0;
        this.deltaTime = deltaTime; inverseDeltaTime = deltaTime > 0 ? 1f / deltaTime : 0f;
        this.dynamicWords = Objects.requireNonNull(dynamicWords);
        this.shaderWords = Objects.requireNonNull(shaderWords);
        needsKillCompaction = false;
        if (activeParticles == 0) { destination.count(0); return; }
        prepare(Math.addExact(activeParticles, 4));
        for (int candidate = 0; candidate < activeParticles; candidate++) {
            int remaining = candidate;
            float age = -1f, lifetime = 0f;
            for (var window : spawnWindows) {
                if (remaining < window.active()) {
                    int particle = remaining + window.particleOffset();
                    required[UNIQUE_ID][instances] = remaining + window.uniqueOffset() + window.particleOffset();
                    age = emitterAge - (window.time() + (float)Integer.toUnsignedLong(particle) * window.interval());
                    lifetime = window.lifetimeBias() + randomFloat(instances, 0) * window.lifetimeScale();
                    if (!(randomFloat(instances, 2) <= window.probability())) lifetime = -1f;
                    break;
                }
                remaining -= (int)window.active();
            }
            if (age < 0 || lifetime <= 0 || age >= lifetime) continue;
            float previousAge = SourceFloatMath.max(age - deltaTime, 0f);
            required[ALIVE][instances] = 1;
            floating(LIFETIME, instances, lifetime);
            floating(AGE, instances, age);
            floating(NORMALIZED_AGE, instances, age / lifetime);
            floating(PREVIOUS_AGE, instances, previousAge);
            floating(PREVIOUS_NORMALIZED_AGE, instances, previousAge / lifetime);
            floating(MATERIAL_RANDOM, instances, randomFloat(instances, 1));
            instances++;
        }
        if (instances != 0) {
            for (var callback : callbacks) {
                moduleSeed = callback.randomSeedOffset();
                callback.module().simulate(this);
            }
            if (needsKillCompaction) compactKilled();
        }
        destination.count(instances);
    }

    private void prepare(int capacity) {
        destination.reserve(capacity);
        if (capacity > transientCapacity) {
            transientCapacity = capacity;
            for (int index = 0; index < required.length; index++) {
                boolean aliasesOutput = index == UNIQUE_ID && uniqueComponent >= 0 || index == MATERIAL_RANDOM && materialRandomComponent >= 0;
                if (!aliasesOutput) required[index] = new int[capacity];
            }
        }
        if (uniqueComponent >= 0) required[UNIQUE_ID] = destination.component(1, uniqueComponent);
        if (materialRandomComponent >= 0) required[MATERIAL_RANDOM] = destination.component(0, materialRandomComponent);
    }

    private void compactKilled() {
        for (int row = instances - 1; row >= 0; row--) {
            if (required[ALIVE][row] != 0) continue;
            instances--;
            if (row >= instances) continue;
            for (var component : required) component[row] = component[instances];
            for (int type = 0; type < 2; type++)
                for (int index = 0; index < destination.componentCount(type); index++) {
                    int[] component = destination.component(type, index);
                    component[row] = component[instances];
                }
        }
    }

    public int count() { return instances; }
    public float deltaTime() { return deltaTime; }
    public float inverseDeltaTime() { return inverseDeltaTime; }
    public int emitterSeed() { return emitterSeed; }
    public int moduleSeed() { return moduleSeed; }
    public int[] requiredWords(Required component) { return required[switch (component) {
        case UNIQUE_ID -> UNIQUE_ID; case LIFETIME -> LIFETIME; case AGE -> AGE;
        case PREVIOUS_AGE -> PREVIOUS_AGE; case NORMALIZED_AGE -> NORMALIZED_AGE; case PREVIOUS_NORMALIZED_AGE -> PREVIOUS_NORMALIZED_AGE;
    }]; }
    public int @Nullable [] particleFloatWords(int component) { return component < 0 ? null : destination.component(0, component); }
    public int uniqueId(int row) { return required[UNIQUE_ID][row]; }
    public int alive(int row) { return required[ALIVE][row]; }
    public void alive(int row, int value) { required[ALIVE][row] = value; }
    public void needsKillCompaction(boolean value) { needsKillCompaction |= value; }
    public float lifetime(int row) { return floating(LIFETIME, row); }
    public float age(int row) { return floating(AGE, row); }
    public float normalizedAge(int row) { return floating(NORMALIZED_AGE, row); }
    public float previousAge(int row) { return floating(PREVIOUS_AGE, row); }
    public float previousNormalizedAge(int row) { return floating(PREVIOUS_NORMALIZED_AGE, row); }
    public float materialRandom(int row) { return floating(MATERIAL_RANDOM, row); }
    public float particleFloat(int component, int row) { return destination.floating(component, row); }
    public float particleFloatOrDefault(int component, int row, float defaultValue) { return component < 0 ? defaultValue : destination.floating(component, row); }
    public void particleFloat(int component, int row, float value) { if (component >= 0) destination.floating(component, row, value); }
    public int particleInt(int component, int row) { return destination.integer(component, row); }
    public int particleIntOrDefault(int component, int row, int defaultValue) { return component < 0 ? defaultValue : destination.integer(component, row); }
    public void particleInt(int component, int row, int value) { if (component >= 0) destination.integer(component, row, value); }
    public int staticWord(int offset) { return staticWords[offset]; }
    public float staticFloat(int offset) { return Float.intBitsToFloat(staticWords[offset]); }
    public int dynamicWord(int offset) { return dynamicWords[offset]; }
    public float dynamicFloat(int offset) { return Float.intBitsToFloat(dynamicWords[offset]); }
    public int shaderWord(int offset) { return shaderWords[offset]; }
    public float shaderFloat(int offset) { return Float.intBitsToFloat(shaderWords[offset]); }
    /** Scratch storage belongs to this emitter and is borrowed until its current module callback returns. */
    public float[] scratchVector(int index) { return scratchVectors[index]; }

    private float floating(int component, int row) { return Float.intBitsToFloat(required[component][row]); }
    private void floating(int component, int row, float value) { required[component][row] = Float.floatToRawIntBits(value); }

    public int randomUInt(int row, int callOffset) {
        mixRandom(row, callOffset);
        return random[0];
    }
    public float randomFloat(int row, int callOffset) { return (randomUInt(row, callOffset) >>> 8) / 16777216f; }
    public void randomFloats(int row, int callOffset, float[] output, int components) {
        if (components < 1 || components > 4 || output.length < components)
            throw new IllegalArgumentException("Invalid stateless random vector width");
        mixRandom(row, callOffset);
        for (int index = 0; index < components; index++) output[index] = (random[index] >>> 8) / 16777216f;
    }

    private void mixRandom(int row, int callOffset) {
        int x = (7123 + callOffset) * 1664525 + 1013904223;
        int y = emitterSeed * 1664525 + 1013904223;
        int z = (3581 + required[UNIQUE_ID][row]) * 1664525 + 1013904223;
        int w = (6405 + moduleSeed) * 1664525 + 1013904223;
        x += y * w; y += z * x; z += x * y; w += y * z;
        x ^= x >>> 16; y ^= y >>> 16; z ^= z >>> 16; w ^= w >>> 16;
        x += y * w; y += z * x; z += x * y; w += y * z;
        random[0] = x; random[1] = y; random[2] = z; random[3] = w;
    }
}
