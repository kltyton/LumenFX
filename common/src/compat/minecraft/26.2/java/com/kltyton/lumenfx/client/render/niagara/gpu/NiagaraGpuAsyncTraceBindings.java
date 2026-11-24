package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.AsyncTraceSnapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Resolves compiled shader symbols to the active trace interface batches. */
final class NiagaraGpuAsyncTraceBindings {
    record Source(String key, String original, NiagaraAsyncGpuTraceDefinition definition) {
        static Source read(JsonObject data) {
            return new Source(SourceNames.canonical(data.get("compile_name").getAsString()),
                    data.get("interface_id").getAsString(),
                    NiagaraAsyncGpuTraceDefinition.read(data.getAsJsonObject("async_gpu_trace")));
        }
    }

    private record Binding(Source source, Object fallbackProxy, boolean preStage) { }
    private static final byte[] ZERO = NiagaraGpuAsyncTraces.word(0);
    private final Map<String, Binding> symbols;
    private final Map<String, NiagaraGpuAsyncTraces.Batch> active = new HashMap<>();
    private @Nullable NiagaraGpuAsyncTraces registry;
    private Function<String, @Nullable NiagaraAsyncGpuTraceDefinition> objects = ignored -> null;
    private @Nullable Map<String, AsyncTraceSnapshot> snapshots;

    NiagaraGpuAsyncTraceBindings(JsonObject program) {
        var declared = new LinkedHashMap<String, Binding>();
        var copied = new HashMap<String, Object>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var data = item.getAsJsonObject();
            if (!"NiagaraDataInterfaceAsyncGpuTrace".equals(data.get("class").getAsString()) || !data.has("shader_symbol")) continue;
            String symbol = data.get("shader_symbol").getAsString();
            var source = Source.read(data);
            var sourceBinding = data.getAsJsonObject("binding");
            String initialization = sourceBinding != null && sourceBinding.has("InstanceInitialization")
                    ? sourceBinding.get("InstanceInitialization").getAsString() : "loaded";
            String original = SourceNames.canonical(source.original());
            Object proxy = initialization.equals("copy") ? copied.computeIfAbsent(original, ignored -> new Object()) : original;
            boolean preStage = !data.has("async_trace_pre_stage") || data.get("async_trace_pre_stage").getAsBoolean();
            var binding = new Binding(source, proxy, preStage);
            if (declared.putIfAbsent(symbol, binding) != null)
                throw new IllegalArgumentException("Duplicate Niagara asynchronous GPU trace symbol: " + symbol);
        }
        symbols = Collections.unmodifiableMap(declared);
    }

    void registry(NiagaraGpuAsyncTraces value) { registry = value; }
    void source(Function<String, @Nullable NiagaraAsyncGpuTraceDefinition> value) { objects = value; }
    void snapshots(@Nullable Map<String, AsyncTraceSnapshot> value) { snapshots = value; }

    void prepare(NiagaraGpuComputeStage stage, Map<String, String> selections, int particles) {
        if (symbols.isEmpty()) return;
        active.clear();
        symbols.forEach((symbol, binding) -> {
            boolean hasResources = stage.hasParameter(symbol + "_RWRayRequests")
                    || stage.hasParameter(symbol + "_IntersectionResults")
                    || stage.hasParameter(symbol + "_RWRayTraceCounts");
            if (!binding.preStage() && !hasResources) return;
            var traces = Objects.requireNonNull(registry, "Niagara asynchronous GPU trace requires a render-frame registry");
            if (!binding.preStage()) {
                active.put(symbol, traces.prepareDummy());
                return;
            }
            var source = binding.source();
            String identity = selections.getOrDefault(source.key(), source.original());
            var snapshot = snapshots == null ? null : snapshots.get(SourceNames.canonical(identity));
            var selected = snapshots == null ? objects.apply(identity) : null;
            if (snapshot != null) selected = snapshot.definition();
            if (selected == null && SourceNames.equal(identity, source.original())) selected = source.definition();
            var batch = traces.prepare(snapshot == null ? binding.fallbackProxy() : snapshot.proxy(), identity,
                    Objects.requireNonNull(selected, "Missing active Niagara GPU trace object: " + identity), particles);
            active.put(symbol, batch);
        });
    }

    void finalizeRequests(CommandEncoder encoder) {
        for (var batch : active.values()) batch.finalizeRequests(encoder);
    }

    @Nullable byte[] value(String name) {
        int separator = name.lastIndexOf('_');
        int member = name.lastIndexOf('.') + 1;
        if (symbols.isEmpty() || separator <= member) return null;
        String symbol = name.substring(member, separator);
        if (!symbols.containsKey(symbol)) return null;
        var batch = active.get(symbol);
        return switch (name.substring(separator + 1)) {
            case "MaxRayTraceCount" -> batch == null ? ZERO : NiagaraGpuAsyncTraces.word(batch.maximum());
            case "RayRequestsOffset", "IntersectionResultsOffset", "RayTraceCountsOffset" -> ZERO;
            default -> null;
        };
    }

    void bind(ShaderBinding.StorageBuffer binding, ComputeBindings.Builder resources) {
        String name = binding.name();
        int separator = name.lastIndexOf('_');
        if (active.isEmpty() || separator < 0) return;
        var batch = active.get(name.substring(0, separator));
        if (batch == null) return;
        GpuBufferSlice data = switch (name.substring(separator + 1)) {
            case "RWRayRequests" -> batch.requests();
            case "IntersectionResults" -> batch.previous();
            case "RWRayTraceCounts" -> batch.counts();
            default -> null;
        };
        if (data != null) resources.storage(name, data, binding.access());
    }

    void clearStage() { active.clear(); }
}
