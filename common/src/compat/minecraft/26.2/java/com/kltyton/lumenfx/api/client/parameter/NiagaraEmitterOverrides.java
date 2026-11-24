package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Component overrides keyed by the authored Niagara emitter handle name. */
public final class NiagaraEmitterOverrides {
    public record Entry(boolean spawningAllowed, @Nullable NiagaraBounds fixedBounds) { }

    private record BoundsUpdate(@Nullable NiagaraBounds value) { }
    private record State(boolean spawningAllowed, @Nullable BoundsUpdate boundsUpdate) { }

    private static final State DEFAULT = new State(true, null);
    public static final NiagaraEmitterOverrides EMPTY = new NiagaraEmitterOverrides(Map.of());
    private final Map<String, State> values;

    private NiagaraEmitterOverrides(Map<String, State> values) { this.values = values; }

    public Entry get(String emitter) {
        State state = state(SourceNames.canonical(emitter));
        return new Entry(state.spawningAllowed(), state.boundsUpdate() == null ? null : state.boundsUpdate().value());
    }

    public boolean fixedBoundsChangedSince(String emitter, NiagaraEmitterOverrides previous) {
        String key = SourceNames.canonical(emitter);
        return state(key).boundsUpdate() != previous.state(key).boundsUpdate();
    }

    public NiagaraEmitterOverrides withSpawning(String emitter, boolean allowed) {
        String key = key(emitter);
        State current = state(key);
        return current.spawningAllowed() == allowed ? this : with(key, new State(allowed, current.boundsUpdate()));
    }

    public NiagaraEmitterOverrides withFixedBounds(String emitter, @Nullable NiagaraBounds bounds) {
        String key = key(emitter);
        // Even an equal value or null can overwrite a subsequent Niagara VM write.
        return with(key, new State(state(key).spawningAllowed(), new BoundsUpdate(bounds)));
    }

    private static String key(String emitter) {
        String key = SourceNames.canonical(Objects.requireNonNull(emitter));
        if (key.isBlank() || key.equals("none")) throw new IllegalArgumentException("A Niagara emitter override requires a handle name");
        return key;
    }

    private State state(String key) { return values.getOrDefault(key, DEFAULT); }

    private NiagaraEmitterOverrides with(String key, State value) {
        var next = new HashMap<>(values);
        if (value.equals(DEFAULT)) next.remove(key);
        else next.put(key, value);
        return next.isEmpty() ? EMPTY : new NiagaraEmitterOverrides(Map.copyOf(next));
    }
}
