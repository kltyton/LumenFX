package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.effect.niagara.NiagaraSystemState;
import com.kltyton.lumenfx.effect.niagara.NiagaraSystemState.InactiveResponse;
import com.kltyton.lumenfx.effect.niagara.NiagaraSystemState.LoopBehavior;
import com.kltyton.lumenfx.effect.niagara.NiagaraSystemState.Range;
import com.kltyton.lumenfx.math.SourceRandomStream;

/** Owns the instance loop state used when the source has no System Update script to execute. */
final class NiagaraSystemStateClock {
    private final NiagaraSystemState definition;
    private final SourceRandomStream random;
    private int loops;
    private float duration, delay, end;

    NiagaraSystemStateClock(NiagaraSystemState definition, int seed) {
        this.definition = definition;
        random = new SourceRandomStream(seed);
        reset(seed);
    }

    void reset(int seed) {
        random.initialize(seed);
        loops = 0;
        duration = sample(definition.duration());
        delay = sample(definition.delay());
        end = delay + duration;
    }

    int tick(float age, int requested, int actual) {
        if (requested != 0) return requested;
        if (age < end) return actual;
        if (definition.ignoreState()) return requested;

        int inactive = definition.inactiveResponse() == InactiveResponse.KILL ? 3 : 1;
        if (definition.loopBehavior() == LoopBehavior.ONCE) return inactive;
        do {
            loops++;
            if (definition.loopBehavior() == LoopBehavior.MULTIPLE && loops >= definition.loopCount()) return inactive;
            if (definition.resampleDuration()) duration = sample(definition.duration());
            if (definition.firstDelayOnly()) delay = 0;
            else if (definition.resampleDelay()) delay = sample(definition.delay());
            float previous = end;
            end = previous + delay + duration;
            if (definition.loopBehavior() == LoopBehavior.INFINITE && end <= previous) {
                float longestDuration = definition.resampleDuration() ? definition.duration().max() : duration;
                float longestDelay = !definition.firstDelayOnly() && definition.resampleDelay() ? definition.delay().max() : delay;
                if (previous + longestDelay + longestDuration <= previous)
                    throw new IllegalStateException("Niagara system loop cannot advance at age " + age);
            }
        } while (age >= end);
        return requested;
    }

    private float sample(Range range) { return random.rangeFloat(range.min(), range.max()); }
}
