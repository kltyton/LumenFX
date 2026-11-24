package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.kltyton.lumenfx.math.SourceFloatMath;
import com.kltyton.lumenfx.math.SourceRandomStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Loop-local rates and bursts retain residual time, contiguous IDs and the source enable/crop lifecycle. */
public final class StatelessSpawnController {
    public record Definition(StatelessSpawnSchedule.Type type, boolean enabled, float spawnTime,
                             StatelessScalarRange.Integer amount, StatelessScalarRange.Floating rate,
                             boolean probabilityEnabled, StatelessScalarRange.Floating probability,
                             boolean loopLimitEnabled, StatelessScalarRange.Integer loopLimit) {
        public Definition {
            Objects.requireNonNull(type); Objects.requireNonNull(amount); Objects.requireNonNull(rate);
            Objects.requireNonNull(probability); Objects.requireNonNull(loopLimit);
        }
        public boolean valid(float duration) {
            return enabled && (type == StatelessSpawnSchedule.Type.RATE ? rate.minimum() + rate.maximum() > 0f
                    : amount.minimum() + amount.maximum() > 0 && spawnTime >= 0f && spawnTime < duration);
        }
    }
    public record Lifetime(float minimum, float maximum, int parameterOffset) { }
    private static final class ActiveRate {
        final StatelessScalarRange.Floating rate;
        final StatelessScalarRange.@Nullable Floating probability;
        float residual;
        ActiveRate(Definition source) { rate = source.rate(); probability = source.probabilityEnabled() ? source.probability() : null; }
    }
    private final List<Definition> definitions;
    private final Lifetime lifetime;
    private final SourceRandomStream random;
    private final float spawnScale;
    private final List<StatelessSpawnSchedule.Info> infos = new ArrayList<>();
    private final List<ActiveRate> rates = new ArrayList<>();
    private boolean enabled = true, dirty;
    private int uniqueOffset;

    public StatelessSpawnController(List<Definition> definitions, Lifetime lifetime, SourceRandomStream random, float spawnScale) {
        this.definitions = List.copyOf(definitions); this.lifetime = Objects.requireNonNull(lifetime);
        this.random = Objects.requireNonNull(random); this.spawnScale = spawnScale;
    }

    public void initialize(float initializationAge, StatelessLoopState loop, int[] parameters, boolean enabledAndAwake) {
        if (!enabledAndAwake) return;
        for (var definition : definitions)
            if (definition.type() == StatelessSpawnSchedule.Type.RATE) rates.add(new ActiveRate(definition));
        beginLoop(initializationAge, loop, parameters, true);
    }

    public void beginLoop(float initializationAge, StatelessLoopState loop, int[] parameters, boolean enabledAndAwake) {
        if (!enabledAndAwake) return;
        for (var rate : rates) {
            float probability = 1f;
            if (rate.probability != null) {
                probability = SourceFloatMath.clamp(rate.probability.evaluate(random, parameters, 0f), 0f, 1f);
                if (Math.abs(probability) <= 1e-8f) continue;
            }
            float value = rate.rate.evaluate(random, parameters, 0f) * spawnScale;
            if (value <= 0f) continue;
            float minimum = minimumLifetime(parameters), maximum = maximumLifetime(parameters);
            if (!(minimum > 0f || maximum > 0f)) continue;
            float start = SourceFloatMath.min(initializationAge + loop.delay() - rate.residual, loop.ageEnd());
            int amount = SourceFloatMath.floorToInt((loop.ageEnd() - start) * value);
            float end = start + (float)amount / value;
            if (amount > 0) {
                boolean append = false;
                if (!infos.isEmpty()) {
                    var previous = infos.get(infos.size() - 1);
                    if (previous.type() == StatelessSpawnSchedule.Type.RATE && previous.rate() == value && previous.end() == start
                            && nearlyEqual(previous.probability(), probability) && nearlyEqual(previous.lifetimeMinimum(), minimum)
                            && nearlyEqual(previous.lifetimeMaximum(), maximum) && previous.uniqueOffset() + previous.amount() == uniqueOffset) {
                        infos.set(infos.size() - 1, new StatelessSpawnSchedule.Info(previous.type(), previous.uniqueOffset(), previous.start(), end,
                                previous.rate(), previous.probability(), previous.amount() + amount, previous.lifetimeMinimum(), previous.lifetimeMaximum()));
                        append = true;
                    }
                }
                if (!append) infos.add(new StatelessSpawnSchedule.Info(StatelessSpawnSchedule.Type.RATE, uniqueOffset, start, end,
                        value, probability, amount, minimum, maximum));
                uniqueOffset += amount; dirty = true;
            }
            rate.residual = loop.ageEnd() - end;
        }
        for (var definition : definitions) {
            if (definition.type() == StatelessSpawnSchedule.Type.RATE || !definition.valid(loop.duration())) continue;
            if (definition.loopLimitEnabled() && loop.loopCount() >= definition.loopLimit().evaluate(random, parameters, 1)) continue;
            if (definition.probabilityEnabled()) {
                float probability = SourceFloatMath.clamp(definition.probability().evaluate(random, parameters, 0f), 0f, 1f);
                if (probability < random.fraction()) continue;
            }
            int unscaled = definition.amount().evaluate(random, parameters, 0);
            int amount = unscaled > 0 ? Math.max(SourceFloatMath.floorToInt((float)unscaled * spawnScale), 1) : 0;
            if (amount <= 0) continue;
            float time = loop.ageStart() + loop.delay() + definition.spawnTime();
            if (time < initializationAge) continue;
            float minimum = minimumLifetime(parameters), maximum = maximumLifetime(parameters);
            if (!(minimum > 0f || maximum > 0f)) continue;
            infos.add(new StatelessSpawnSchedule.Info(StatelessSpawnSchedule.Type.BURST, uniqueOffset, time, time,
                    0f, 1f, amount, minimum, maximum));
            uniqueOffset += amount; dirty = true;
        }
    }

    public void tickEnabled(float age, boolean enabledAndAwake, StatelessLoopState loop, int[] parameters) {
        if (enabled != enabledAndAwake) {
            enabled = enabledAndAwake;
            if (enabled) initialize(age, loop, parameters, true);
            else crop(age);
        }
        infos.removeIf(info -> age >= info.end() + info.lifetimeMaximum());
    }
    public void crop(float age) {
        if (infos.isEmpty() && rates.isEmpty()) return;
        rates.clear();
        for (int index = 0; index < infos.size();) {
            var info = infos.get(index);
            if (info.type() == StatelessSpawnSchedule.Type.RATE) {
                float end = SourceFloatMath.min(info.end(), age);
                info = new StatelessSpawnSchedule.Info(info.type(), info.uniqueOffset(), info.start(), end, info.rate(), info.probability(),
                        SourceFloatMath.floorToInt((end - info.start()) * info.rate()), info.lifetimeMinimum(), info.lifetimeMaximum());
                infos.set(index, info);
            }
            if (age < info.start() || age >= info.end() + info.lifetimeMaximum()) infos.remove(index);
            else index++;
        }
        dirty = true;
    }
    public void kill() {
        if (infos.isEmpty() && rates.isEmpty()) return;
        infos.clear(); rates.clear(); dirty = true;
    }
    public void reset(boolean killExisting, float oldAge, boolean emitterEnabled) {
        if (killExisting) { infos.clear(); uniqueOffset = 0; }
        else for (int index = 0; index < infos.size(); index++) {
            var info = infos.get(index);
            infos.set(index, new StatelessSpawnSchedule.Info(info.type(), info.uniqueOffset(), info.start() - oldAge, info.end() - oldAge,
                    info.rate(), info.probability(), info.amount(), info.lifetimeMinimum(), info.lifetimeMaximum()));
        }
        rates.clear(); enabled = emitterEnabled; dirty = true;
    }

    public List<StatelessSpawnSchedule.Info> snapshot() { return List.copyOf(infos); }
    public StatelessSpawnSchedule.Plan plan(float age) { return StatelessSpawnSchedule.plan(infos, age); }
    public long count(float age) { return StatelessSpawnSchedule.count(infos, true, age); }
    public boolean consumeDirty() { boolean result = dirty; dirty = false; return result; }
    private float minimumLifetime(int[] parameters) { return lifetime.parameterOffset() != -1 ? Float.intBitsToFloat(parameters[lifetime.parameterOffset()]) : lifetime.minimum(); }
    private float maximumLifetime(int[] parameters) { return lifetime.parameterOffset() != -1 ? Float.intBitsToFloat(parameters[lifetime.parameterOffset()]) : lifetime.maximum(); }
    private static boolean nearlyEqual(float a, float b) { return Math.abs(a - b) <= 1e-8f; }
}
