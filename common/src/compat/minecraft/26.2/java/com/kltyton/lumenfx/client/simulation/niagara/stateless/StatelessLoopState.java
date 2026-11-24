package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.kltyton.lumenfx.math.SourceFloatMath;
import com.kltyton.lumenfx.math.SourceRandomStream;
import java.util.Objects;
import java.util.function.Consumer;

/** Loop transitions call spawn generation immediately, preserving the shared random stream's visitation order. */
public final class StatelessLoopState {
    public enum Behavior { INFINITE, MULTIPLE, ONCE }
    public enum DurationMode { FIXED, INFINITE }
    public record Settings(Behavior behavior, DurationMode durationMode, int loopLimit,
                           StatelessScalarRange.Floating duration, StatelessScalarRange.Floating delay,
                           boolean delayEnabled, boolean recalculateDuration, boolean firstDelayOnly, boolean recalculateDelay) {
        public Settings {
            Objects.requireNonNull(behavior); Objects.requireNonNull(durationMode);
            Objects.requireNonNull(duration); Objects.requireNonNull(delay);
        }
    }
    private final Settings settings;
    private final SourceRandomStream random;
    private int loopCount;
    private float delay, duration, ageStart, ageEnd;

    public StatelessLoopState(Settings settings, SourceRandomStream random) {
        this.settings = Objects.requireNonNull(settings); this.random = Objects.requireNonNull(random);
    }

    public void initialize(int[] parameters) {
        loopCount = 0; ageStart = 0f; delay = 0f;
        if (settings.delayEnabled()) delay = SourceFloatMath.max(settings.delay().evaluate(random, parameters, 0f), 0f);
        if (settings.behavior() == Behavior.ONCE && settings.durationMode() == DurationMode.INFINITE) {
            duration = 60f * 60f * 24f;
            ageEnd = duration;
        } else {
            duration = SourceFloatMath.max(settings.duration().evaluate(random, parameters, .001f), .001f);
            ageEnd = ageStart + delay + duration;
        }
    }

    public boolean advance(float age, int[] parameters, Consumer<StatelessLoopState> beginLoop) {
        if (!(age >= ageEnd)) return true;
        if (settings.behavior() == Behavior.ONCE) return false;
        do {
            loopCount++;
            if (settings.behavior() == Behavior.MULTIPLE && loopCount >= settings.loopLimit()) return false;
            if (settings.recalculateDuration())
                duration = SourceFloatMath.max(settings.duration().evaluate(random, parameters, .001f), .001f);
            if (settings.delayEnabled()) {
                if (settings.firstDelayOnly()) delay = 0f;
                else if (settings.recalculateDelay()) delay = SourceFloatMath.max(settings.delay().evaluate(random, parameters, 0f), 0f);
            }
            ageStart = ageEnd;
            ageEnd = ageStart + delay + duration;
            beginLoop.accept(this);
        } while (age >= ageEnd);
        return true;
    }

    public int loopCount() { return loopCount; }
    public float delay() { return delay; }
    public float duration() { return duration; }
    public float ageStart() { return ageStart; }
    public float ageEnd() { return ageEnd; }
}
