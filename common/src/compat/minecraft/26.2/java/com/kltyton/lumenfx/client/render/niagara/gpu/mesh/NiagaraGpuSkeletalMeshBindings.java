package com.kltyton.lumenfx.client.render.niagara.gpu.mesh;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalGpuSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalGpuSnapshot.Buffer;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraSkeletalGpuLayout;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Owns the skeletal shader buffers on the render thread and binds the queued step's pose. */
public final class NiagaraGpuSkeletalMeshBindings implements AutoCloseable {
    private record Uploaded(GpuBuffer buffer, @Nullable Object identity) { }

    private static final class Binding {
        final String key, original;
        final Map<String, Buffer> declared = new LinkedHashMap<>();
        final Map<String, Uploaded> buffers = new HashMap<>();
        @Nullable NiagaraSkeletalGpuSnapshot snapshot;

        Binding(String key, String original) {
            this.key = key;
            this.original = original;
        }
    }

    private final NiagaraGpuPrograms programs;
    private final NiagaraSkeletalGpuLayout layout;
    private final Map<String, Binding> bindings;
    private final Map<String, Binding> storageOwners;

    public NiagaraGpuSkeletalMeshBindings(NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders, JsonObject program) {
        this.programs = programs;
        layout = NiagaraSkeletalGpuLayout.read(program);
        var reflected = new HashSet<String>();
        for (var stage : shaders.stages().values()) for (var variant : stage.values())
            for (var descriptor : variant.shader().bindings())
                if (descriptor instanceof ShaderBinding.StorageBuffer storage) reflected.add(storage.name());
        var symbols = new LinkedHashMap<String, Binding>();
        var owners = new LinkedHashMap<String, Binding>();
        for (var entry : program.getAsJsonArray("interfaces")) {
            var source = entry.getAsJsonObject();
            if (!source.get("class").getAsString().equals("NiagaraDataInterfaceSkeletalMesh") || !source.has("shader_symbol")) continue;
            String symbol = source.get("shader_symbol").getAsString();
            var binding = new Binding(SourceNames.canonical(source.get("compile_name").getAsString()), source.get("interface_id").getAsString());
            if (symbols.putIfAbsent(symbol, binding) != null)
                throw new IllegalArgumentException("Duplicate Niagara skeletal mesh shader symbol: " + symbol);
            for (Buffer field : Buffer.values()) {
                String name = symbol + "_" + field.member();
                if (!reflected.contains(name)) continue;
                binding.declared.put(name, field);
                owners.put(name, binding);
            }
        }
        bindings = Map.copyOf(symbols);
        storageOwners = Map.copyOf(owners);
    }

    public void beginTick(CommandEncoder encoder, Map<String, String> selections, Map<String, NiagaraSkeletalGpuSnapshot> snapshots) {
        for (var binding : bindings.values()) {
            String id = selections.getOrDefault(binding.key, binding.original);
            var snapshot = Objects.requireNonNull(snapshots.get(id), "Skeletal Mesh Reader was not published for the GPU step: " + id);
            for (var field : binding.declared.entrySet()) upload(encoder, binding, snapshot, field.getKey(), field.getValue());
            binding.snapshot = snapshot;
        }
    }

    private void upload(CommandEncoder encoder, Binding binding, NiagaraSkeletalGpuSnapshot snapshot, String name, Buffer field) {
        var identity = snapshot.bufferIdentity(field);
        var existing = binding.buffers.get(name);
        if (existing != null && existing.identity() == identity) return;
        long size = snapshot.size(field, layout);
        if (size > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara skeletal mesh buffer exceeds device capacity: " + name);
        var bytes = MemoryUtil.memAlloc(Math.toIntExact(size)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            snapshot.write(field, bytes, layout);
            bytes.flip();
            if (field.dynamic() && existing != null && existing.buffer().size() == size) {
                encoder.writeToBuffer(existing.buffer().slice(), bytes);
                binding.buffers.put(name, new Uploaded(existing.buffer(), identity));
            } else {
                var replacement = RenderSystem.getDevice().createBuffer(() -> name, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes);
                binding.buffers.put(name, new Uploaded(replacement, identity));
                if (existing != null) existing.buffer().close();
            }
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    public @Nullable byte[] value(String name) {
        int member = name.lastIndexOf('.') + 1;
        int separator = name.lastIndexOf('_');
        if (separator <= member || bindings.isEmpty()) return null;
        var binding = bindings.get(name.substring(member, separator));
        if (binding == null || binding.snapshot == null) return null;
        return binding.snapshot.value(name.substring(separator + 1), layout);
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (storageOwners.isEmpty()) return;
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer storage) {
            var owner = storageOwners.get(storage.name());
            if (owner == null) continue;
            var buffer = Objects.requireNonNull(owner.buffers.get(storage.name()), "Unbound Niagara skeletal mesh buffer: " + storage.name());
            resources.storage(storage.name(), buffer.buffer().slice(), storage.access());
        }
    }

    public void clearSnapshots() { for (var binding : bindings.values()) binding.snapshot = null; }

    @Override public void close() {
        for (var binding : bindings.values()) {
            binding.snapshot = null;
            for (var uploaded : binding.buffers.values()) uploaded.buffer().close();
            binding.buffers.clear();
        }
    }
}
