package com.kltyton.lumenfx.client.simulation.niagara.parameter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.resource.niagara.NiagaraCollectionJson;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Collects MPC dependencies once when a system is created, before frame and emitter workers consume them. */
public final class NiagaraCollectionSources {
    public record Sources(List<NiagaraCollectionBinding> bindings, List<MaterialCollectionDefinition> materials) { }
    private final Map<String, MaterialCollectionDefinition> materials = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
    private final List<NiagaraCollectionBinding> bindings = new ArrayList<>();
    private NiagaraCollectionSources() { }

    public static List<MaterialCollectionDefinition> materials(NiagaraDefinition definition) {
        return collect(definition).materials();
    }
    public static List<NiagaraCollectionBinding> bindings(NiagaraDefinition definition) {
        return collect(definition).bindings();
    }
    public static Sources collect(NiagaraDefinition definition) {
        NiagaraCollectionSources result = new NiagaraCollectionSources();
        result.program(definition.systemSpawn()); result.program(definition.systemUpdate());
        for (NiagaraDefinition.Emitter emitter : definition.emitters()) {
            if (emitter.simulation() instanceof NiagaraDefinition.CpuSimulation cpu) {
                result.program(cpu.spawn()); result.program(cpu.update());
            } else if (emitter.simulation() instanceof NiagaraDefinition.GpuSimulation gpu) {
                result.metadata(gpu.metadata().getAsJsonArray("parameter_bindings"));
                for (var context : gpu.hostContexts()) for (var binding : context.collections()) result.addBinding(binding);
                if (gpu.hostContexts().isEmpty() && gpu.metadata().has("host_contexts"))
                    for (var context : gpu.metadata().getAsJsonArray("host_contexts"))
                        result.metadata(context.getAsJsonObject().getAsJsonArray("collections"));
            } else if (emitter.simulation() instanceof NiagaraDefinition.StatelessSimulation stateless)
                result.metadata(stateless.metadata().getAsJsonObject("parameters").getAsJsonArray("bindings"));
            for (NiagaraDefinition.EventHandler handler : emitter.eventHandlers()) if (handler.program() != null) result.program(handler.program());
        }
        return new Sources(List.copyOf(result.bindings), List.copyOf(result.materials.values()));
    }
    private void program(VectorProgram program) {
        for (VectorProgram.Parameter parameter : program.parameters()) {
            NiagaraCollectionBinding collection = parameter.collection();
            addBinding(collection);
        }
    }
    private void metadata(JsonArray values) {
        for (JsonElement element : values) {
            JsonObject value = element.getAsJsonObject();
            addBinding(NiagaraCollectionJson.read(value));
        }
    }
    private void addBinding(@Nullable NiagaraCollectionBinding binding) {
        if (binding == null) return;
        bindings.add(binding); add(binding.materialSource());
    }
    private void add(@Nullable MaterialCollectionDefinition definition) {
        if (definition == null) return;
        MaterialCollectionDefinition existing = materials.putIfAbsent(definition.path(), definition);
        if (existing != null && !existing.equals(definition)) throw new IllegalArgumentException("Conflicting MPC definition: " + definition.path());
    }
}
