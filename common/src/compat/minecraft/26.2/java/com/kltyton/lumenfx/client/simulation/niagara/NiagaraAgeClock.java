package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.api.client.NiagaraAgeUpdate;
import com.kltyton.lumenfx.effect.niagara.NiagaraTiming;

/** Resolves UNiagaraComponent's solo age modes before dispatching its system steps. */
public final class NiagaraAgeClock {
    public record Step(boolean reset, boolean multiTick, int count, float delta) { }

    private static final float SOURCE_KINDA_SMALL_NUMBER = 1.0e-4f;
    private float lastHandledDesiredAge;

    public void reset() { lastHandledDesiredAge = 0; }

    public Step plan(NiagaraAgeUpdate settings, float age, NiagaraTiming timing) {
        float seekDelta = timing.fixedTick() ? timing.fixedDelta() : settings.seekDelta();
        if (seekDelta <= 0f) return new Step(false, false, 0, seekDelta);
        if (settings.mode() == NiagaraAgeUpdate.Mode.DESIRED_AGE_NO_SEEK) {
            float difference = settings.desiredAge() - lastHandledDesiredAge;
            lastHandledDesiredAge = settings.desiredAge();
            if (difference < 0f && Math.abs(difference) < seekDelta) return new Step(false, false, 0, seekDelta);
            if (difference < 0f || difference >= 5f * seekDelta) return new Step(true, false, 1, seekDelta);
            return new Step(false, false, 1, Math.min(difference, 2f * seekDelta));
        }
        float difference = Math.max(settings.desiredAge(), 0f) - age;
        if (Math.abs(difference) < SOURCE_KINDA_SMALL_NUMBER) return new Step(false, false, 0, seekDelta);
        boolean reset = difference < 0f;
        if (reset) difference = settings.desiredAge();
        if (settings.lockToSeekDelta() || difference > seekDelta) {
            int ticks = Math.max(0, (int)Math.floor(difference / seekDelta));
            return new Step(reset, true, ticks, seekDelta);
        }
        return new Step(reset, false, !timing.fixedTick() || difference >= seekDelta ? 1 : 0, difference);
    }
}
