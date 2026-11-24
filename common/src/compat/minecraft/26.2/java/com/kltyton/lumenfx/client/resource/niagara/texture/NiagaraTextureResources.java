package com.kltyton.lumenfx.client.resource.niagara.texture;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import org.jspecify.annotations.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Enumerates both interface object defaults and bound user-object defaults before the reload publishes resources. */
public final class NiagaraTextureResources {
    private NiagaraTextureResources() { }

    public static Set<Identifier> collect(EffectDefinition effect) {
        Set<Identifier> result = new LinkedHashSet<>();
        for (var material : effect.materials().values()) for (var texture : material.instanceParameters().textureLayout()) {
            result.add(texture.baseDefault());
            if (texture.objectDefault() != null) result.add(texture.objectDefault());
            if (texture.collection() != null && texture.collection().baseDefault() != null) result.add(texture.collection().baseDefault());
            for (var input : texture.objectInputs()) if (input.defaultTexture() != null) result.add(input.defaultTexture());
            if (texture.interfaceDefinition() != null) add(result, texture.interfaceDefinition());
        }
        var system = effect.niagara();
        if (system == null) return Set.copyOf(result);
        if (system.instanceStores() != null) {
            store(result, system.instanceStores().instance()); store(result, system.instanceStores().override());
        }
        for (var target : system.renderTargets()) add(result, target.data());
        cpu(result, system.systemSpawn()); cpu(result, system.systemUpdate());
        for (var emitter : system.emitters()) {
            for (var renderer : emitter.renderers()) if (renderer.component() != null) {
                JsonObject profile = renderer.component().template().getAsJsonObject("lumenfx_ies");
                if (profile != null) add(result, profile);
                JsonObject source = renderer.component().template().getAsJsonObject("lumenfx_rect_source");
                if (source != null) add(result, source);
            }
            if (emitter.simulation() instanceof NiagaraDefinition.CpuSimulation simulation) {
                cpu(result, simulation.spawn()); cpu(result, simulation.update());
            } else if (emitter.simulation() instanceof NiagaraDefinition.GpuSimulation simulation) {
                store(result, simulation.parameterStore());
                if (simulation.parameterStore() == null && simulation.metadata().has("execution_store"))
                    store(result, simulation.metadata().getAsJsonObject("execution_store"));
                for (var context : simulation.hostContexts()) store(result, context.parameters());
                if (simulation.hostContexts().isEmpty() && simulation.metadata().has("host_contexts"))
                    for (var context : simulation.metadata().getAsJsonArray("host_contexts"))
                        store(result, context.getAsJsonObject().getAsJsonObject("execution_store"));
                for (var item : simulation.metadata().getAsJsonArray("interfaces")) {
                    JsonObject binding = item.getAsJsonObject();
                    if ("NiagaraDataInterfaceTexture".equals(binding.get("class").getAsString()))
                        add(result, java.util.Objects.requireNonNull(binding.getAsJsonObject("texture_2d"), "Niagara texture object binding is missing"));
                    else if ("NiagaraDataInterface2DArrayTexture".equals(binding.get("class").getAsString()))
                        add(result, java.util.Objects.requireNonNull(binding.getAsJsonObject("texture_array"), "Niagara array texture object binding is missing"));
                    else if ("NiagaraDataInterfaceCubeTexture".equals(binding.get("class").getAsString()))
                        add(result, java.util.Objects.requireNonNull(binding.getAsJsonObject("texture_cube"), "Niagara cube texture object binding is missing"));
                    else if ("NiagaraDataInterfaceVolumeTexture".equals(binding.get("class").getAsString()))
                        add(result, java.util.Objects.requireNonNull(binding.getAsJsonObject("texture_volume"), "Niagara volume texture object binding is missing"));
                    else if ("NiagaraDataInterfaceRenderTarget2D".equals(binding.get("class").getAsString()))
                        add(result, java.util.Objects.requireNonNull(binding.getAsJsonObject("render_target_2d"), "Niagara render target object binding is missing"));
                    else if ("NiagaraDataInterfaceRenderTarget2DArray".equals(binding.get("class").getAsString()))
                        add(result, java.util.Objects.requireNonNull(binding.getAsJsonObject("render_target_array"), "Niagara render target array binding is missing"));
                    else if ("NiagaraDataInterfaceRenderTargetVolume".equals(binding.get("class").getAsString()))
                        add(result, java.util.Objects.requireNonNull(binding.getAsJsonObject("render_target_volume"), "Niagara volume target binding is missing"));
                    else if ("NiagaraDataInterfaceRenderTargetCube".equals(binding.get("class").getAsString()))
                        add(result, java.util.Objects.requireNonNull(binding.getAsJsonObject("render_target_cube"), "Niagara cube target binding is missing"));
                }
            }
            for (var handler : emitter.eventHandlers()) if (handler.program() != null) cpu(result, handler.program());
        }
        return Set.copyOf(result);
    }

    private static void cpu(Set<Identifier> output, VectorProgram program) {
        store(output, program.parameterStore());
        for (var binding : program.external()) if (binding.interfaceType().equals("texture_2d") || binding.interfaceType().equals("texture_array")
                || binding.interfaceType().equals("texture_cube")
                || binding.interfaceType().equals("texture_volume")
                || binding.interfaceType().equals("render_target_2d") || binding.interfaceType().equals("render_target_array")
                || binding.interfaceType().equals("render_target_volume") || binding.interfaceType().equals("render_target_cube"))
            add(output, java.util.Objects.requireNonNull(binding.interfaceData(), "Niagara texture object binding is missing"));
    }
    private static void store(Set<Identifier> output, @Nullable ScriptParameterStoreDefinition definition) {
        if (definition == null) return;
        for (var value : definition.interfaceValues()) object(output, value);
        for (var value : definition.objectValues()) object(output, value);
        for (var reference : definition.references()) {
            object(output, reference.value());
            if (reference.collection() != null) object(output, reference.collection().baseDefault());
        }
        for (var source : definition.initialSources()) store(output, source.definition());
        for (var source : definition.instanceSources()) store(output, source.definition());
    }
    private static void object(Set<Identifier> output, @Nullable ObjectValue value) {
        if (value != null) object(output, value.source());
    }
    private static void object(Set<Identifier> output, JsonObject data) {
        if (data.has("runtime_texture")) output.add(Identifier.parse(data.get("runtime_texture").getAsString()));
        if (data.has("texture_2d_defaults")) add(output, data.getAsJsonObject("texture_2d_defaults"));
        if (data.has("texture_array_defaults")) add(output, data.getAsJsonObject("texture_array_defaults"));
        if (data.has("texture_cube_defaults")) add(output, data.getAsJsonObject("texture_cube_defaults"));
        if (data.has("texture_volume_defaults")) add(output, data.getAsJsonObject("texture_volume_defaults"));
        if (data.has("render_target_2d_defaults")) add(output, data.getAsJsonObject("render_target_2d_defaults"));
        if (data.has("render_target_array_defaults")) add(output, data.getAsJsonObject("render_target_array_defaults"));
        if (data.has("render_target_volume_defaults")) add(output, data.getAsJsonObject("render_target_volume_defaults"));
        if (data.has("render_target_cube_defaults")) add(output, data.getAsJsonObject("render_target_cube_defaults"));
    }

    private static void store(Set<Identifier> output, JsonObject definition) {
        for (String field : new String[] {"interface_values", "object_values"}) {
            var values = definition.getAsJsonArray(field);
            if (values != null) for (var value : values) if (!value.isJsonNull()) object(output, value.getAsJsonObject());
        }
        for (var item : definition.getAsJsonArray("references")) {
            JsonObject reference = item.getAsJsonObject();
            if (!reference.get("value").isJsonNull()) object(output, reference.getAsJsonObject("value"));
            JsonObject collection = reference.getAsJsonObject("collection");
            if (collection != null && !collection.get("base_default").isJsonNull())
                object(output, collection.getAsJsonObject("base_default"));
        }
        for (String field : new String[] {"initial_sources", "instance_sources"}) {
            var values = definition.getAsJsonArray(field);
            if (values != null) for (var item : values) store(output, item.getAsJsonObject().getAsJsonObject("definition"));
        }
    }

    private static void add(Set<Identifier> output, JsonObject data) {
        if (data.has("source_texture") || data.has("source_user_texture"))
            throw new IllegalArgumentException("Niagara texture source objects have not been converted to runtime resources");
        for (String field : new String[] {"texture", "user_texture"})
            if (data.has(field) && !data.get(field).isJsonNull()) output.add(Identifier.parse(data.get(field).getAsString()));
    }
}
