package com.kltyton.lumenfx.client.render.niagara.gpu.chaos;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuChaosBindings {
    private record Interface(String key, String identity) { }
    private record Resource(Interface source, Column column) { }
    private record Uniform(Interface source, boolean time) { }
    private record Active(String identity, ChaosDestructionFrame frame) { }

    private final Map<String, Resource> resources = new HashMap<>();
    private final Map<String, Uniform> uniforms = new HashMap<>();
    private final Map<Interface, Active> active = new HashMap<>();
    private @Nullable NiagaraGpuChaosBuffers owner;

    public NiagaraGpuChaosBindings(JsonObject program) {
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("chaos_destruction") || !binding.has("shader_symbol")) continue;
            var source = new Interface(SourceNames.canonical(binding.get("compile_name").getAsString()),
                    binding.get("interface_id").getAsString());
            String symbol = binding.get("shader_symbol").getAsString();
            for (Column column : Column.values()) resources.put(symbol + "_" + column.symbol(), new Resource(source, column));
            uniforms.put(symbol + "_LastSpawnedPointID", new Uniform(source, false));
            uniforms.put(symbol + "_SolverTime", new Uniform(source, true));
        }
    }

    public void beginTick(@Nullable NiagaraGpuChaosBuffers owner, Map<String, String> aliases,
                          Map<String, ChaosDestructionFrame> frames) {
        active.clear();
        this.owner = owner;
        for (var uniform : uniforms.values()) {
            var source = uniform.source();
            active.computeIfAbsent(source, key -> {
                String identity = aliases.getOrDefault(key.key(), key.identity());
                return new Active(identity, Objects.requireNonNull(frames.get(identity),
                        "Missing Niagara Chaos Destruction step: " + identity));
            });
        }
    }

    public @Nullable byte[] value(String name) {
        var uniform = uniforms.get(name.substring(name.lastIndexOf('.') + 1));
        if (uniform == null) return null;
        var frame = active.get(uniform.source()).frame();
        int bits = frame.count() == 0 ? 0 : uniform.time() ? Float.floatToRawIntBits(frame.time()) : frame.lastId();
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(bits).array();
    }

    public void bind(CommandEncoder encoder, ShaderProgram shader, ComputeBindings.Builder target) {
        for (var resource : shader.bindings()) {
            if (!(resource instanceof ShaderBinding.StorageBuffer storage)) continue;
            var descriptor = resources.get(storage.name());
            if (descriptor == null) continue;
            var current = active.get(descriptor.source());
            var slice = Objects.requireNonNull(owner).prepare(encoder, current.identity(), descriptor.column(), current.frame());
            target.storage(storage.name(), slice, storage.access());
        }
    }

    public void clear() {
        active.clear();
        owner = null;
    }
}
