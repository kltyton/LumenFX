package com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Resolves the physical Grid Collection objects selected by the GPU execution parameter stores. */
public final class NiagaraGridCollectionUses {
    public record Use(String emitter, JsonObject program, Map<String, String> selections) { }
    public record Resolved(Set<String> identities, Map<String, Map<String, Integer>> columns,
                           Map<String, Map<String, String>> emitters) {
        public @Nullable String readerTarget(String emitter, String name) {
            var interfaces = emitters.get(SourceNames.canonical(emitter));
            return interfaces == null ? null : interfaces.get(SourceNames.canonical("Emitter." + name));
        }
    }

    private NiagaraGridCollectionUses() { }

    static Resolved resolve(List<Use> uses, String definitionField, String functionsField, String widthField) {
        var columns = new HashMap<String, Map<String, Integer>>();
        var emitters = new HashMap<String, Map<String, String>>();
        var identities = new HashSet<String>();
        for (var use : uses) {
            var emitter = new HashMap<String, String>();
            emitters.put(SourceNames.canonical(use.emitter()), emitter);
            var seen = new HashSet<String>();
            for (var entry : use.program().getAsJsonArray("interfaces")) {
                var binding = entry.getAsJsonObject();
                if (!binding.has(definitionField)) continue;
                String name = SourceNames.canonical(binding.get("compile_name").getAsString());
                String identity = use.selections().getOrDefault(name, binding.get("interface_id").getAsString());
                emitter.put(name, identity);
                identities.add(identity);
                if (!seen.add(identity)) continue;
                var functions = binding.getAsJsonArray(functionsField);
                if (functions == null) continue;
                var attributes = columns.computeIfAbsent(identity, ignored -> new LinkedHashMap<>());
                for (var item : functions) {
                    var function = item.getAsJsonObject();
                    String attribute = SourceNames.canonical(function.get("attribute").getAsString());
                    if (!attribute.isEmpty() && !attribute.equals("none") && function.get("defines_attribute").getAsBoolean())
                        attributes.putIfAbsent(attribute, function.get(widthField).getAsInt());
                }
            }
        }
        return new Resolved(identities, columns, emitters);
    }
}
