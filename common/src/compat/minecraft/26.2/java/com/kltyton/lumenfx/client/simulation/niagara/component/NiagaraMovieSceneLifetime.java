package com.kltyton.lumenfx.client.simulation.niagara.component;

import com.kltyton.lumenfx.api.client.NiagaraAgeUpdate;
import com.kltyton.lumenfx.api.client.component.NiagaraMovieSceneEvaluation;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraSystemLifeCycleTrack;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraSystemLifeCycleTrack.EvaluateBehavior;
import org.jspecify.annotations.Nullable;

/** Evaluates the Niagara life cycle section across the current MovieScene traversal range. */
public final class NiagaraMovieSceneLifetime {
    public enum Action { NONE, RESTART, DEACTIVATE_IMMEDIATELY, REQUEST_ACTIVE, REQUEST_INACTIVE }

    public record Evaluation(Action action, boolean rendering, boolean allowScalability, NiagaraAgeUpdate.Mode mode,
                             float seekDelta, boolean frameLocked, @Nullable Float desiredAge) {
        public NiagaraAgeUpdate ageUpdate(NiagaraAgeUpdate base, float age) {
            return new NiagaraAgeUpdate(mode, age, seekDelta, frameLocked, base.maximumSimulationTime(), base.renderWhileSeeking());
        }
    }

    private static final float AGE_RESOLUTION = 10000.0f;

    private NiagaraMovieSceneLifetime() { }

    public static Evaluation evaluate(NiagaraSystemLifeCycleTrack track, NiagaraMovieSceneEvaluation context) {
        Action action;
        if (context.time() < track.startFrame()) action = Action.DEACTIVATE_IMMEDIATELY;
        else if (context.visits(track.startFrame())) action = Action.RESTART;
        else if (context.time() < track.endFrame())
            action = track.evaluateBehavior() == EvaluateBehavior.ActivateIfInactive ? Action.REQUEST_ACTIVE : Action.NONE;
        else action = switch (track.endBehavior()) {
            case SetSystemInactive -> Action.REQUEST_INACTIVE;
            case Deactivate -> Action.DEACTIVATE_IMMEDIATELY;
            case None -> Action.NONE;
        };
        float seekDelta = (float)track.displayRate().denominator() / track.displayRate().numerator();
        float age = (float)context.frameRate().seconds(context.time() - track.startFrame());
        age = (float)Math.floor(age * AGE_RESOLUTION + 0.5f) / AGE_RESOLUTION;
        Float desiredAge = age >= 0f ? age + (track.frameLocked() ? seekDelta * 0.25f : 0f) : null;
        return new Evaluation(action, !context.preRoll(), track.allowScalability(), track.ageUpdateMode(),
                seekDelta, track.frameLocked(), desiredAge);
    }
}
