package com.kltyton.lumenfx.client.render.niagara.gpu.memory;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.memory.NiagaraMemoryBuffer.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Uses queued instance snapshots to bind native byte-address memory resources. */
public final class NiagaraGpuMemoryBindings {
    private static final class Binding {
        final String key, original;
        @Nullable String selected;
        int elements;
        Binding(String key, String original) { this.key = key; this.original = original; }
    }

    private final Map<String, Binding> symbols, resources;
    private @Nullable NiagaraGpuMemoryBuffers active;

    public NiagaraGpuMemoryBindings(JsonObject program) {
        var all = new HashMap<String, Binding>();
        var buffers = new HashMap<String, Binding>();
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("shader_symbol") || !binding.get("class").getAsString().equals("NiagaraDataInterfaceMemoryBuffer")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            var source = new Binding(SourceNames.canonical(binding.get("compile_name").getAsString()), binding.get("interface_id").getAsString());
            if (all.putIfAbsent(symbol, source) != null) throw new IllegalArgumentException("Duplicate Niagara memory shader symbol " + symbol);
            buffers.put(symbol + "_Buffer", source);
        }
        symbols = Map.copyOf(all);
        resources = Map.copyOf(buffers);
    }

    public void beginTick(CommandEncoder encoder, @Nullable NiagaraGpuMemoryBuffers buffers, Map<String, String> selections,
                          Map<String, Snapshot> snapshots) {
        if (symbols.isEmpty()) return;
        var registry = Objects.requireNonNull(buffers);
        active = registry;
        var prepared = new HashSet<String>();
        for (var binding : symbols.values()) {
            String id = selections.getOrDefault(binding.key, binding.original);
            var snapshot = Objects.requireNonNull(snapshots.get(id), "Memory buffer was not published for the Niagara GPU step: " + id);
            if (prepared.add(id)) registry.prepare(encoder, id, snapshot);
            binding.selected = id;
            binding.elements = snapshot.elements();
        }
    }

    public @Nullable byte[] value(String name) {
        if (!name.endsWith("_NumElements")) return null;
        var binding = symbols.get(name.substring(name.lastIndexOf('.') + 1, name.length() - "_NumElements".length()));
        if (binding == null) return null;
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(binding.elements).array();
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder target) {
        if (resources.isEmpty()) return;
        var buffers = Objects.requireNonNull(active);
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer storage) {
            var binding = resources.get(storage.name());
            if (binding != null) target.storage(storage.name(), buffers.buffer(Objects.requireNonNull(binding.selected)), storage.access());
        }
    }

    public void clearSnapshots() {
        active = null;
        for (var binding : symbols.values()) binding.selected = null;
    }
}
