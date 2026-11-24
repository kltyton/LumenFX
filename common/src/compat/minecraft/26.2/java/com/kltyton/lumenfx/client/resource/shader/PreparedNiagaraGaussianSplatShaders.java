package com.kltyton.lumenfx.client.resource.shader;

import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/** Resource-prepared UE5.9 Niagara Gaussian Splatting compute and utility programs. */
public record PreparedNiagaraGaussianSplatShaders(
        String engineHead,
        Map<String, Map<String, PreparedNiagaraShaders.Stage>> stages) {
    public PreparedNiagaraGaussianSplatShaders {
        stages = Map.copyOf(stages);
    }

    public static boolean isRequired(EffectDefinition effect) {
        if (effect.emitters().stream().anyMatch(emitter ->
                emitter.renderer() == EmitterDefinition.Renderer.SPLAT || emitter.splatSettings() != null)) {
            return true;
        }
        var niagara = effect.niagara();
        if (niagara == null) {
            return false;
        }
        if (!niagara.gaussianSplatInputs().isEmpty()
                || niagara.mutableInterfaces().stream().anyMatch(mi -> mi.type().contains("gaussian_splat"))) {
            return true;
        }
        var instanceStores = niagara.instanceStores();
        if (instanceStores != null
                && (storeRequiresGaussianSplat(instanceStores.instance())
                        || storeRequiresGaussianSplat(instanceStores.override()))) {
            return true;
        }
        if (programRequiresGaussianSplat(niagara.systemSpawn())
                || programRequiresGaussianSplat(niagara.systemUpdate())) {
            return true;
        }
        for (var emitter : niagara.emitters()) {
            if (emitterRequiresGaussianSplat(emitter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean emitterRequiresGaussianSplat(com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Emitter emitter) {
        var simulation = emitter.simulation();
        if (simulation instanceof com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.CpuSimulation cpu) {
            if (programRequiresGaussianSplat(cpu.spawn()) || programRequiresGaussianSplat(cpu.update())) {
                return true;
            }
        } else if (simulation instanceof com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.GpuSimulation gpu) {
            if (storeRequiresGaussianSplat(gpu.parameterStore()) || metadataRequiresGaussianSplat(gpu.metadata())) {
                return true;
            }
            for (var context : gpu.hostContexts()) {
                if (storeRequiresGaussianSplat(context.parameters())) {
                    return true;
                }
            }
        }
        for (var handler : emitter.eventHandlers()) {
            if (programRequiresGaussianSplat(handler.program())) {
                return true;
            }
        }
        return false;
    }

    private static boolean programRequiresGaussianSplat(
            com.kltyton.lumenfx.effect.niagara.@org.jspecify.annotations.Nullable VectorProgram program) {
        if (program == null) {
            return false;
        }
        for (var binding : program.external()) {
            String type = binding.interfaceType();
            if (type.startsWith("gaussian_splat")
                    || isGaussianSplatType(type)
                    || jsonRequiresGaussianSplat(binding.interfaceData())
                    || jsonRequiresGaussianSplat(binding.bindingInfo())) {
                return true;
            }
        }
        return storeRequiresGaussianSplat(program.parameterStore());
    }

    private static boolean storeRequiresGaussianSplat(
            com.kltyton.lumenfx.effect.niagara.parameter.store.@org.jspecify.annotations.Nullable ScriptParameterStoreDefinition store) {
        if (store == null) {
            return false;
        }
        for (var variable : store.layout().variables()) {
            if (isGaussianSplatType(variable.key().sourceType())) {
                return true;
            }
        }
        for (var value : store.interfaceValues()) {
            if (value != null && jsonRequiresGaussianSplat(value.source())) {
                return true;
            }
        }
        for (var value : store.objectValues()) {
            if (value != null && jsonRequiresGaussianSplat(value.source())) {
                return true;
            }
        }
        for (var reference : store.references()) {
            var value = reference.value();
            if (value != null && jsonRequiresGaussianSplat(value.source())) {
                return true;
            }
            var collection = reference.collection();
            var baseDefault = collection == null ? null : collection.baseDefault();
            if (baseDefault != null && jsonRequiresGaussianSplat(baseDefault.source())) {
                return true;
            }
        }
        for (var source : store.initialSources()) {
            if (storeRequiresGaussianSplat(source.definition())) {
                return true;
            }
        }
        for (var source : store.instanceSources()) {
            if (storeRequiresGaussianSplat(source.definition())) {
                return true;
            }
        }
        return false;
    }

    private static boolean metadataRequiresGaussianSplat(com.google.gson.@org.jspecify.annotations.Nullable JsonObject metadata) {
        if (metadata == null) {
            return false;
        }
        if (arrayHasGaussianSplatJson(metadata.get("interfaces"))) {
            return true;
        }
        var paramMeta = objectOrNull(metadata.get("parameter_metadata"));
        if (paramMeta != null) {
            var infos = paramMeta.get("DataInterfaceParamInfo");
            if (infos != null && infos.isJsonArray()) {
                for (var item : infos.getAsJsonArray()) {
                    var info = objectOrNull(item);
                    if (info != null && isGaussianSplatType(stringOrNull(info, "DIClassName"))) {
                        return true;
                    }
                }
            }
        }
        if (storeJsonRequiresGaussianSplat(objectOrNull(metadata.get("execution_store")))) {
            return true;
        }
        var hostContexts = metadata.get("host_contexts");
        if (hostContexts != null && hostContexts.isJsonArray()) {
            for (var entry : hostContexts.getAsJsonArray()) {
                var context = objectOrNull(entry);
                if (context != null && storeJsonRequiresGaussianSplat(objectOrNull(context.get("execution_store")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean storeJsonRequiresGaussianSplat(com.google.gson.@org.jspecify.annotations.Nullable JsonObject store) {
        if (store == null) {
            return false;
        }
        var variables = store.get("variables");
        if (variables != null && variables.isJsonArray()) {
            for (var entry : variables.getAsJsonArray()) {
                var variable = objectOrNull(entry);
                if (variable != null && isGaussianSplatType(stringOrNull(variable, "source_type"))) {
                    return true;
                }
            }
        }
        if (arrayHasGaussianSplatJson(store.get("interface_values")) || arrayHasGaussianSplatJson(store.get("object_values"))) {
            return true;
        }
        var references = store.get("references");
        if (references != null && references.isJsonArray()) {
            for (var entry : references.getAsJsonArray()) {
                var ref = objectOrNull(entry);
                if (ref == null) {
                    continue;
                }
                if (jsonRequiresGaussianSplat(objectOrNull(ref.get("value")))) {
                    return true;
                }
                var collection = objectOrNull(ref.get("collection"));
                if (collection != null && jsonRequiresGaussianSplat(objectOrNull(collection.get("base_default")))) {
                    return true;
                }
            }
        }
        return sourceArrayRequiresGaussianSplat(store.get("initial_sources"))
                || sourceArrayRequiresGaussianSplat(store.get("instance_sources"));
    }

    private static boolean sourceArrayRequiresGaussianSplat(com.google.gson.@org.jspecify.annotations.Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return false;
        }
        for (var entry : element.getAsJsonArray()) {
            var source = objectOrNull(entry);
            if (source == null) {
                continue;
            }
            var definition = objectOrNull(source.get("definition"));
            if (storeJsonRequiresGaussianSplat(definition != null ? definition : source)) {
                return true;
            }
        }
        return false;
    }

    private static boolean arrayHasGaussianSplatJson(com.google.gson.@org.jspecify.annotations.Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return false;
        }
        for (var entry : element.getAsJsonArray()) {
            if (jsonRequiresGaussianSplat(objectOrNull(entry))) {
                return true;
            }
        }
        return false;
    }

    private static boolean jsonRequiresGaussianSplat(com.google.gson.@org.jspecify.annotations.Nullable JsonObject json) {
        if (json == null) {
            return false;
        }
        if (json.has("gaussian_splat_writer") || json.has("gaussian_splat_reader")
                || isGaussianSplatType(stringOrNull(json, "class"))
                || isGaussianSplatType(stringOrNull(json, "class_path"))) {
            return true;
        }
        var data = objectOrNull(json.get("interface_data"));
        return data != null && (data.has("gaussian_splat_writer") || data.has("gaussian_splat_reader"));
    }

    private static boolean isGaussianSplatType(@org.jspecify.annotations.Nullable String type) {
        return type != null
                && (type.contains("NiagaraDataInterfaceGaussianSplatWriter")
                        || type.contains("NiagaraDataInterfaceGaussianSplatReader"));
    }

    private static com.google.gson.@org.jspecify.annotations.Nullable JsonObject objectOrNull(
            com.google.gson.@org.jspecify.annotations.Nullable JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static @org.jspecify.annotations.Nullable String stringOrNull(com.google.gson.JsonObject object, String member) {
        var value = object.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    public static boolean isRequired(Collection<EffectDefinition> effects) {
        return effects.stream().anyMatch(PreparedNiagaraGaussianSplatShaders::isRequired);
    }

    public static PreparedNiagaraGaussianSplatShaders prepare(ResourceManager resources) {
        var prepared = NiagaraUtilityShaderResources.prepare(resources, "niagara_gaussian_splat",
                Map.of(
                        "build_splats", Set.of("default"),
                        "shading", Set.of("sh0", "sh1", "sh2", "sh3"),
                        "draw_indirect_args", Set.of("default"),
                        "gather_shaded_splats", Set.of("gpu_0", "gpu_1"),
                        "tile_count", Set.of("default"),
                        "prefix_sum", Set.of("default"),
                        "fill_splat_index_buffer", Set.of("default"),
                        "tile_rasterize", Set.of("default")));
        return new PreparedNiagaraGaussianSplatShaders(prepared.engineHead(), prepared.programs());
    }

    public PreparedNiagaraShaders.Stage stage(String program, String variant) {
        var variants = stages.get(program);
        var stage = variants == null ? null : variants.get(variant);
        if (stage == null) {
            throw new IllegalStateException("Missing Gaussian Splat shader stage " + program + "/" + variant);
        }
        return stage;
    }
}
