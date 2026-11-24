package com.kltyton.lumenfx.api.client.component;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraSystemLifeCycleTrack;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Requests lifecycle changes on the world owner and publishes completed transitions. */
public final class NiagaraComponentControl {
    public sealed interface Request permits Action, MovieScene, MovieSceneEnd { }
    public enum Action implements Request { ACTIVATE, ACTIVATE_RESET, REINITIALIZE, DEACTIVATE, DEACTIVATE_IMMEDIATE, RELEASE_TO_POOL }
    public enum Pooling { None, AutoRelease, ManualRelease }
    public record MovieScene(@Nullable NiagaraSystemLifeCycleTrack track, @Nullable NiagaraMovieSceneCacheTrack cacheTrack,
                             NiagaraMovieSceneEvaluation context) implements Request { }
    public enum MovieSceneEnd implements Request { INSTANCE }
    public enum State { INACTIVE, ACTIVE, DRAINING }
    public record Completion(EffectHandle handle, boolean external) { }

    private final List<Request> requests = new ArrayList<>();
    private final Pooling pooling;
    private volatile State state = State.INACTIVE;
    private volatile @Nullable Consumer<Completion> completion;

    public NiagaraComponentControl(boolean autoActivate) {
        this(autoActivate, Pooling.None);
    }

    public NiagaraComponentControl(boolean autoActivate, Pooling pooling) {
        this.pooling = Objects.requireNonNull(pooling);
        if (autoActivate) activate(false);
    }

    public Pooling pooling() { return pooling; }

    /** Releases a manually leased component after its live particles finish. */
    public synchronized void releaseToPool() {
        if (pooling != Pooling.ManualRelease) throw new IllegalStateException("Only manual Niagara leases can be released");
        requests.add(Action.RELEASE_TO_POOL);
    }

    public synchronized void activate(boolean reset) { requests.add(reset ? Action.ACTIVATE_RESET : Action.ACTIVATE); }
    public synchronized void reinitialize() { requests.add(Action.REINITIALIZE); }
    public synchronized void deactivate() { requests.add(Action.DEACTIVATE); }
    public synchronized void deactivateImmediately() { requests.add(Action.DEACTIVATE_IMMEDIATE); }

    /** Evaluates the saved life cycle track in the sequence's own frame space. */
    public void evaluateMovieScene(NiagaraMovieSceneEvaluation context) { evaluateMovieScene(null, context); }

    /** Evaluates a life cycle track supplied by the host's sequence binding. */
    public void evaluateMovieScene(@Nullable NiagaraSystemLifeCycleTrack track, NiagaraMovieSceneEvaluation context) {
        evaluateMovieScene(track, null, context);
    }

    public synchronized void evaluateMovieScene(@Nullable NiagaraSystemLifeCycleTrack track, @Nullable NiagaraMovieSceneCacheTrack cacheTrack,
                                               NiagaraMovieSceneEvaluation context) {
        requests.add(new MovieScene(track, cacheTrack, Objects.requireNonNull(context, "context")));
    }

    public synchronized void endMovieScene() { requests.add(MovieSceneEnd.INSTANCE); }

    public State state() { return state; }
    public boolean isActive() { return state != State.INACTIVE; }
    public void setCompletionListener(@Nullable Consumer<Completion> listener) { completion = listener; }

    public synchronized List<Request> takeRequests() {
        if (requests.isEmpty()) return List.of();
        var result = List.copyOf(requests);
        requests.clear();
        return result;
    }
    public void publish(State state) { this.state = state; }
    public void publishCompletion(EffectHandle handle, boolean external) {
        var listener = completion;
        if (listener != null) listener.accept(new Completion(handle, external));
    }
}
