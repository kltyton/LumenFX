package com.kltyton.lumenfx.client.render.niagara.gpu.export;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.export.NiagaraParticleExportState.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/** The export UAV is reset and submitted for each source stage that binds it. */
public final class NiagaraGpuParticleExportBindings implements AutoCloseable {
    private static final class Binding {
        final String key, original;
        @Nullable StageBuffer selected;
        Binding(String key, String original) { this.key = key; this.original = original; }
    }

    private static final class StageBuffer {
        @Nullable GpuBuffer buffer;
        @Nullable Snapshot snapshot;
        int capacity;
        boolean bound;
    }

    private final NiagaraGpuPrograms programs;
    private final Map<String, Binding> buffers, uniforms;
    private final Map<String, StageBuffer> physicalBuffers = new LinkedHashMap<>();
    private @Nullable ClientLevel level;
    private int readbackLimit, maxParticles;

    public NiagaraGpuParticleExportBindings(NiagaraGpuPrograms programs, JsonObject program) {
        this.programs = programs;
        var names = new HashMap<String, Binding>();
        var values = new HashMap<String, Binding>();
        for (var entry : program.getAsJsonArray("interfaces")) {
            var source = entry.getAsJsonObject();
            if (!source.get("class").getAsString().equals("NiagaraDataInterfaceExport") || !source.has("shader_symbol")) continue;
            String symbol = source.get("shader_symbol").getAsString();
            var binding = new Binding(SourceNames.canonical(source.get("compile_name").getAsString()), source.get("interface_id").getAsString());
            if (names.putIfAbsent(symbol + "_RWWriteBuffer", binding) != null)
                throw new IllegalArgumentException("Duplicate Niagara particle export shader symbol: " + symbol);
            values.put(symbol + "_WriteBufferSize", binding);
        }
        buffers = Map.copyOf(names);
        uniforms = Map.copyOf(values);
    }

    public void beginTick(Map<String, String> selections, Map<String, Snapshot> snapshots, @Nullable ClientLevel level, int readbackLimit) {
        this.level = level;
        this.readbackLimit = readbackLimit;
        for (var buffer : physicalBuffers.values()) buffer.snapshot = null;
        for (var binding : buffers.values()) {
            String identity = selections.getOrDefault(binding.key, binding.original);
            var selected = physicalBuffers.computeIfAbsent(identity, ignored -> new StageBuffer());
            selected.snapshot = Objects.requireNonNull(snapshots.get(identity), "Particle Export was not published for the GPU step: " + identity);
            selected.bound = false;
            selected.capacity = 0;
            binding.selected = selected;
        }
        var iterator = physicalBuffers.values().iterator();
        while (iterator.hasNext()) {
            var buffer = iterator.next();
            if (buffer.snapshot != null) continue;
            if (buffer.buffer != null) buffer.buffer.close();
            iterator.remove();
        }
    }

    public void preStage(int maxParticles) { this.maxParticles = maxParticles; }

    public void bind(CommandEncoder encoder, ShaderProgram shader, ComputeBindings.Builder resources) {
        if (buffers.isEmpty()) return;
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer storage) {
            String name = storage.name();
            var binding = buffers.get(name);
            if (binding == null) continue;
            var selected = Objects.requireNonNull(binding.selected);
            if (!selected.bound) {
                var snapshot = selected.snapshot;
                int count = 0;
                if (level != null && snapshot != null && snapshot.callback() != null) {
                    var authored = snapshot.definition();
                    count = authored.perParticle() ? (int)(maxParticles * authored.multiplier()) : authored.fixedCount();
                    if (authored.perParticle() && readbackLimit > 0) count = Math.min(count, readbackLimit);
                }
                if (count != 0) {
                    var buffer = allocate(selected, count, name);
                    try (var stack = MemoryStack.stackPush()) {
                        var zero = stack.malloc(Integer.BYTES).order(ByteOrder.nativeOrder());
                        zero.putInt(0).flip();
                        encoder.writeToBuffer(buffer.slice(0, Integer.BYTES), zero);
                    }
                }
                selected.capacity = count;
                selected.bound = true;
            }
            var buffer = selected.capacity == 0 ? programs.dummyBuffer() : Objects.requireNonNull(selected.buffer);
            resources.storage(name, buffer.slice(), storage.access());
        }
    }

    private GpuBuffer allocate(StageBuffer binding, int count, String name) {
        long rounded = (count + 63L) / 64L * 64L;
        long bytes = NiagaraGpuParticleExportReadbacks.size(Math.toIntExact(rounded));
        if (bytes > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara particle export buffer exceeds device capacity: " + name);
        var buffer = binding.buffer;
        if (buffer != null && buffer.size() >= bytes) return buffer;
        var replacement = RenderSystem.getDevice().createBuffer(() -> name,
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC), bytes);
        binding.buffer = replacement;
        if (buffer != null) buffer.close();
        return replacement;
    }

    public @Nullable byte[] value(String name) {
        var binding = uniforms.get(name.substring(name.lastIndexOf('.') + 1));
        if (binding == null) return null;
        var selected = binding.selected;
        int capacity = selected == null ? 0 : selected.capacity;
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(capacity).array();
    }

    public void postStage(CommandEncoder encoder) {
        var world = level;
        if (world == null) return;
        for (var binding : physicalBuffers.values()) {
            var snapshot = binding.snapshot;
            var buffer = binding.buffer;
            if (binding.capacity != 0 && snapshot != null && buffer != null)
                NiagaraGpuParticleExportReadbacks.submit(world, encoder, buffer.slice(), binding.capacity, snapshot);
            binding.capacity = 0;
            binding.bound = false;
        }
    }

    public void clearSnapshots() {
        level = null;
        for (var binding : buffers.values()) binding.selected = null;
        for (var buffer : physicalBuffers.values()) { buffer.capacity = 0; buffer.bound = false; buffer.snapshot = null; }
    }

    @Override public void close() {
        clearSnapshots();
        for (var binding : physicalBuffers.values()) {
            if (binding.buffer != null) binding.buffer.close();
            binding.buffer = null;
        }
        physicalBuffers.clear();
    }
}
