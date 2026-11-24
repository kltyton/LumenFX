package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Event datasets owned by one Niagara system instance. */
final class NiagaraEventData {
    private record Key(UUID emitter, String event) { }
    static final class Stream {
        private final List<NiagaraDefinition.Variable> layout;
        private VectorDataSet current, destination;

        Stream(List<NiagaraDefinition.Variable> layout) {
            this.layout = List.copyOf(layout);
            current = dataset(layout);
            destination = dataset(layout);
        }
        VectorDataSet current() { return current; }
        VectorDataSet destination() { return destination; }
        void begin() { destination.count(0); }
        void publish() { VectorDataSet old = current; current = destination; destination = old; }
        void reset() { current.count(0); destination.count(0); }
    }

    private final Map<Key, Stream> streams = new LinkedHashMap<>();

    NiagaraEventData(List<NiagaraDefinition.Emitter> emitters) {
        for (var emitter : emitters) {
            if (emitter.spawnEvents().isEmpty() && emitter.updateEvents().isEmpty()) continue;
            UUID owner = NiagaraEmitterSchedule.guid(emitter.handleId());
            if (owner == null) throw new IllegalArgumentException("Niagara event emitter has no handle ID: " + emitter.name());
            for (var generator : emitter.spawnEvents()) register(owner, generator);
            for (var generator : emitter.updateEvents()) register(owner, generator);
        }
    }

    Stream generator(NiagaraDefinition.Emitter emitter, NiagaraDefinition.EventGenerator generator) {
        UUID owner = NiagaraEmitterSchedule.guid(emitter.handleId());
        Stream result = streams.get(new Key(owner, SourceNames.canonical(generator.id())));
        if (result == null) throw new IllegalArgumentException("Missing Niagara event generator " + generator.id());
        return result;
    }

    @Nullable VectorDataSet source(NiagaraDefinition.Emitter emitter, NiagaraDefinition.EventHandler handler) {
        UUID owner = NiagaraEmitterSchedule.guid(handler.sourceEmitterId());
        if (owner == null || owner.equals(new UUID(0, 0))) owner = NiagaraEmitterSchedule.guid(emitter.handleId());
        if (owner == null) return null;
        Stream stream = streams.get(new Key(owner, SourceNames.canonical(handler.sourceEventName())));
        return stream == null ? null : stream.current();
    }

    void reset() { streams.values().forEach(Stream::reset); }

    private void register(UUID emitter, NiagaraDefinition.EventGenerator generator) {
        Key key = new Key(emitter, SourceNames.canonical(generator.id()));
        Stream existing = streams.get(key);
        if (existing == null) streams.put(key, new Stream(generator.layout()));
        else if (!existing.layout.equals(generator.layout()))
            throw new IllegalArgumentException("Conflicting Niagara event layouts for " + generator.id());
    }

    private static VectorDataSet dataset(List<NiagaraDefinition.Variable> layout) {
        int floats = 0, integers = 0, halves = 0;
        for (var variable : layout) {
            floats = Math.max(floats, variable.floatStart() + variable.floatCount());
            integers = Math.max(integers, variable.intStart() + variable.intCount());
            halves = Math.max(halves, variable.halfStart() + variable.halfCount());
        }
        return new VectorDataSet(floats, integers, halves);
    }
}
