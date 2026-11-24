package com.kltyton.lumenfx.client.render.niagara.gpu.texture;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture.NiagaraVolumeCache.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuVolumeCacheBindings {
    private record Binding(String original, String compileName, BitSet inputStages) { }
    private record Selected(String id, Snapshot snapshot, BitSet inputStages) { }
    private final NiagaraGpuPrograms programs;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Map<String, Selected> selected = new LinkedHashMap<>();
    private @Nullable NiagaraGpuVolumeCaches registry;

    public NiagaraGpuVolumeCacheBindings(NiagaraGpuPrograms programs, JsonObject source) {
        this.programs = programs;
        for (var element : source.getAsJsonArray("interfaces")) {
            var data = element.getAsJsonObject();
            if (!data.has("volume_cache") || !data.has("shader_symbol")) continue;
            String compileName = data.get("compile_name").getAsString();
            String resolvedName = data.get("resolved_parameter_name").getAsString();
            var stages = new BitSet();
            var sourceStages = source.getAsJsonArray("stages");
            for (int stage = 0; stage < sourceStages.size(); stage++) {
                var inputs = sourceStages.get(stage).getAsJsonObject().getAsJsonArray("InputDataInterfaces");
                if (inputs == null) continue;
                for (var input : inputs) {
                    String name = input.getAsString();
                    if (SourceNames.equal(name, compileName) || SourceNames.equal(name, resolvedName)) {
                        stages.set(stage);
                        break;
                    }
                }
            }
            String symbol = data.get("shader_symbol").getAsString();
            if (bindings.putIfAbsent(symbol, new Binding(data.get("interface_id").getAsString(),
                    SourceNames.canonical(compileName), stages)) != null)
                throw new IllegalArgumentException("Duplicate Niagara Volume Cache shader symbol " + symbol);
        }
    }

    public void beginTick(@Nullable NiagaraGpuVolumeCaches registry, Map<String, String> aliases, Map<String, Snapshot> snapshots) {
        this.registry = registry;
        selected.clear();
        for (var entry : bindings.entrySet()) {
            var binding = entry.getValue();
            String id = aliases.getOrDefault(binding.compileName(), binding.original());
            var snapshot = snapshots.get(id);
            if (snapshot != null) selected.put(entry.getKey(), new Selected(id, snapshot, binding.inputStages()));
        }
    }

    public void preStage(int index) {
        if (registry == null) return;
        for (var value : selected.values()) if (value.inputStages().get(index)) registry.preStage(value.id(), value.snapshot());
    }

    public @Nullable byte[] value(String name) {
        int start = name.lastIndexOf('.') + 1, end = name.lastIndexOf("_TextureSize");
        if (end <= start || end + "_TextureSize".length() != name.length() || !bindings.containsKey(name.substring(start, end))) return null;
        var binding = selected.get(name.substring(start, end));
        var image = binding == null || registry == null ? null : registry.texture(binding.id());
        if (image == null) return new byte[12];
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(image.width()).putFloat(image.height()).putFloat(image.depth()).array();
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        for (var descriptor : shader.bindings()) {
            if (!(descriptor instanceof ShaderBinding.SampledTexture sampled) || !sampled.volume()) continue;
            String name = sampled.name();
            for (String symbol : bindings.keySet()) {
                String imageName = symbol + "_Texture";
                if (!name.equals(imageName) && !name.startsWith(imageName + "_")) continue;
                var binding = selected.get(symbol);
                var image = binding == null || registry == null ? null : registry.texture(binding.id());
                resources.volume(name, image == null ? programs.blackVolumeTexture() : image, 0);
                break;
            }
        }
    }

    public void clear() { beginTick(null, Map.of(), Map.of()); }
}
