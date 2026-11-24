package com.kltyton.lumenfx.client.render.niagara.gpu.mesh;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs.VertexColors;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshGpuGeometry;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshGpuGeometry.Buffer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshReader.GpuSnapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Binds the selected mesh object's immutable simulation step and source-ordered GPU buffers. */
public final class NiagaraGpuStaticMeshBindings implements AutoCloseable {
    private static final class Binding implements AutoCloseable {
        final String key, original, symbol;
        final List<Buffer> used;
        final boolean fieldUsed;
        @Nullable Object geometry;
        @Nullable VertexColors colors;
        @Nullable GpuSnapshot snapshot;
        @Nullable StaticMeshDistanceField field;
        @Nullable NiagaraGpuMeshDistanceField fieldImages;
        Map<String, GpuBuffer> buffers = Map.of();

        Binding(String key, String original, String symbol, List<Buffer> used, boolean fieldUsed) {
            this.key = key;
            this.original = original;
            this.symbol = symbol;
            this.used = used;
            this.fieldUsed = fieldUsed;
        }

        @Override public void close() {
            try { buffers.values().forEach(GpuBuffer::close); }
            finally {
                if (fieldImages != null) fieldImages.close();
                buffers = Map.of(); snapshot = null; geometry = null; colors = null; field = null; fieldImages = null;
            }
        }
    }

    private final NiagaraGpuPrograms programs;
    private final Map<String, Binding> bindings;

    public NiagaraGpuStaticMeshBindings(NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders, JsonObject program) {
        this.programs = programs;
        var reflected = new HashSet<String>();
        var images = new HashSet<String>();
        for (var stage : shaders.stages().values()) for (var variant : stage.values())
            for (var descriptor : variant.shader().bindings())
                if (descriptor instanceof ShaderBinding.StorageBuffer storage) reflected.add(storage.name());
                else if (descriptor instanceof ShaderBinding.SampledTexture sampled && sampled.volume()) images.add(sampled.name());
        var entries = new LinkedHashMap<String, Binding>();
        for (var element : program.getAsJsonArray("interfaces")) {
            var value = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceStaticMesh".equals(value.get("class").getAsString()) || !value.has("shader_symbol")) continue;
            String symbol = value.get("shader_symbol").getAsString();
            var used = Arrays.stream(Buffer.values()).filter(buffer -> reflected.contains(symbol + "_" + buffer.member())).toList();
            var binding = new Binding(SourceNames.canonical(value.get("compile_name").getAsString()),
                    value.get("interface_id").getAsString(), symbol, used, images.stream().anyMatch(name ->
                    image(name, symbol + "_MeshFieldTable") || image(name, symbol + "_MeshFieldAtlas")));
            if (entries.putIfAbsent(symbol, binding) != null)
                throw new IllegalArgumentException("Duplicate Niagara static mesh shader symbol: " + symbol);
        }
        bindings = Map.copyOf(entries);
    }

    public void beginTick(Map<String, String> selections, Map<String, GpuSnapshot> snapshots) {
        for (var binding : bindings.values()) {
            String id = selections.getOrDefault(binding.key, binding.original);
            var snapshot = Objects.requireNonNull(snapshots.get(id), "Static Mesh Reader was not published for the GPU step: " + id);
            var geometry = snapshot.geometry();
            Object identity = geometry.identity();
            var colors = binding.used.contains(Buffer.COLORS) ? geometry.vertexColors() : null;
            boolean geometryChanged = !Objects.equals(binding.geometry, identity);
            if (geometryChanged || !Objects.equals(binding.colors, colors)) {
                var replacement = new LinkedHashMap<String, GpuBuffer>();
                Map<String, GpuBuffer> next;
                try {
                    for (Buffer buffer : binding.used) {
                        if (!geometryChanged && buffer != Buffer.COLORS) continue;
                        String name = binding.symbol + "_" + buffer.member();
                        replacement.put(name, upload(name, geometry, buffer));
                    }
                    var merged = new LinkedHashMap<>(binding.buffers);
                    merged.putAll(replacement);
                    next = Map.copyOf(merged);
                } catch (RuntimeException | Error failure) {
                    replacement.values().forEach(GpuBuffer::close);
                    throw failure;
                }
                var old = binding.buffers;
                binding.buffers = next;
                binding.geometry = identity;
                binding.colors = colors;
                for (String name : replacement.keySet()) {
                    var buffer = old.get(name);
                    if (buffer != null) buffer.close();
                }
            }
            binding.snapshot = snapshot;
            var field = binding.fieldUsed ? snapshot.distanceField() : null;
            if (binding.field != field) {
                var replacement = field == null ? null : new NiagaraGpuMeshDistanceField(binding.symbol, field);
                var old = binding.fieldImages;
                binding.fieldImages = replacement;
                binding.field = field;
                if (old != null) old.close();
            }
        }
    }

    private GpuBuffer upload(String name, NiagaraStaticMeshGpuGeometry geometry, Buffer buffer) {
        long size = geometry.size(buffer);
        if (size > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara static mesh buffer exceeds device capacity: " + name);
        var data = MemoryUtil.memAlloc(Math.toIntExact(size)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            geometry.write(buffer, data);
            data.flip();
            return RenderSystem.getDevice().createBuffer(() -> name, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    public @Nullable byte[] value(String name) {
        int separator = name.lastIndexOf('_'), member = name.lastIndexOf('.') + 1;
        if (separator <= member || bindings.isEmpty()) return null;
        var binding = bindings.get(name.substring(member, separator));
        if (binding == null || binding.snapshot == null) return null;
        String field = name.substring(separator + 1);
        byte[] value = binding.snapshot.value(field);
        if (value != null) return value;
        if (binding.fieldImages != null) return binding.fieldImages.value(field);
        return switch (field) {
            case "MeshFieldMipLayout", "MeshFieldScales", "MeshFieldAdds" -> NiagaraGpuMeshDistanceField.packed(12).array();
            case "MeshFieldBrickStarts", "MeshFieldAtlasInverseExtent" -> NiagaraGpuMeshDistanceField.packed(3).array();
            case "MeshFieldTiles" -> NiagaraGpuMeshDistanceField.packed(2).array();
            default -> null;
        };
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (bindings.isEmpty()) return;
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer storage) {
            String name = storage.name();
            int separator = name.lastIndexOf('_');
            if (separator < 0) continue;
            var binding = bindings.get(name.substring(0, separator));
            if (binding == null) continue;
            var buffer = binding.buffers.get(name);
            if (buffer != null) resources.storage(name, buffer.slice(), storage.access());
        } else if (descriptor instanceof ShaderBinding.SampledTexture sampled && sampled.volume()) {
            for (var binding : bindings.values()) {
                if (image(sampled.name(), binding.symbol + "_MeshFieldTable")) {
                    resources.volume(sampled.name(), binding.fieldImages == null ? programs.blackIntegerVolumeTexture() : binding.fieldImages.table(), 0);
                    break;
                }
                if (image(sampled.name(), binding.symbol + "_MeshFieldAtlas")) {
                    resources.volume(sampled.name(), binding.fieldImages == null ? programs.blackVolumeTexture() : binding.fieldImages.atlas(), 0);
                    break;
                }
            }
        }
    }

    private static boolean image(String name, String base) { return name.equals(base) || name.startsWith(base + "_"); }

    public void clearSnapshots() { for (var binding : bindings.values()) binding.snapshot = null; }
    @Override public void close() { bindings.values().forEach(Binding::close); }
}
