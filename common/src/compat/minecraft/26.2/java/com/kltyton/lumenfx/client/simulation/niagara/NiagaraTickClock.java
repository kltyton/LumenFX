package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.effect.niagara.NiagaraTiming;

/** Fixed-step budget owned by a solo system simulation. */
public final class NiagaraTickClock {
    public record Plan(int steps, float engineDelta, float systemDelta, boolean fixed) { }
    private static final int DEFAULT_MAX_SUBSTEPS = 100;
    private final NiagaraTiming timing;
    private float age;
    private double previousTime = Double.NaN;

    public NiagaraTickClock(NiagaraTiming timing) { this.timing = timing; }

    public Plan plan(double time, float engineDelta) {
        return plan(time, engineDelta, DEFAULT_MAX_SUBSTEPS);
    }

    Plan plan(double time, float engineDelta, int maximumSteps) {
        if (time == previousTime) return new Plan(0, engineDelta, timing.fixedDelta(), true);
        if (time < previousTime) age = 0;
        previousTime = time;
        return planDelta(engineDelta, maximumSteps);
    }

    Plan planDelta(float engineDelta, int maximumSteps) {
        float delta = timing.fixedDelta();
        int steps = steps(age, engineDelta, maximumSteps);
        age += engineDelta;
        return new Plan(steps, engineDelta, delta, true);
    }

    boolean hasStep(double time, float engineDelta, int maximumSteps) {
        return time != previousTime && steps(time < previousTime ? 0 : age, engineDelta, maximumSteps) > 0;
    }

    boolean hasManualStep(float engineDelta, int maximumSteps) { return steps(age, engineDelta, maximumSteps) > 0; }

    void advanceMultiTick(float delta) { age += delta; }

    private int steps(float previousAge, float engineDelta, int maximumSteps) {
        float delta = timing.fixedDelta();
        if (delta <= 0) return 0;
        float budget = previousAge % delta + engineDelta;
        return Math.max(0, Math.min((int)Math.floor(budget / delta), maximumSteps));
    }
}
