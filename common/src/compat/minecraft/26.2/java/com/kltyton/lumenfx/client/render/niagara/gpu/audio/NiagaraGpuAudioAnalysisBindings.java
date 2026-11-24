package com.kltyton.lumenfx.client.render.niagara.gpu.audio;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.audio.analysis.NiagaraAudioBusAnalysis.Frame;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Binds each queued waveform or spectrum publication to the original GPU function. */
public final class NiagaraGpuAudioAnalysisBindings implements AutoCloseable {
    private static final class Binding {
        final String key, original;
        Frame frame = Frame.EMPTY;
        @Nullable Frame uploaded;
        @Nullable GpuBuffer buffer;
        Binding(String key, String original) { this.key = key; this.original = original; }
    }

    private final NiagaraGpuPrograms programs;
    private final Map<String, Binding> symbols, buffers;

    public NiagaraGpuAudioAnalysisBindings(NiagaraGpuPrograms programs, JsonObject program) {
        this.programs = programs;
        var bindings = new HashMap<String, Binding>();
        var storage = new HashMap<String, Binding>();
        for (var entry : program.getAsJsonArray("interfaces")) {
            var data = entry.getAsJsonObject();
            String type = data.get("class").getAsString();
            boolean spectrum = type.equals("NiagaraDataInterfaceAudioSpectrum");
            if (!data.has("shader_symbol") || !spectrum && !type.equals("NiagaraDataInterfaceAudioOscilloscope")) continue;
            String symbol = data.get("shader_symbol").getAsString();
            var binding = new Binding(SourceNames.canonical(data.get("compile_name").getAsString()), data.get("interface_id").getAsString());
            if (bindings.putIfAbsent(symbol, binding) != null) throw new IllegalArgumentException("Duplicate Niagara audio shader symbol " + symbol);
            storage.put(symbol + (spectrum ? "_SpectrumBuffer" : "_OscilloscopeBuffer"), binding);
        }
        symbols = Map.copyOf(bindings);
        buffers = Map.copyOf(storage);
    }

    public void beginTick(Map<String, String> selections, Map<String, Frame> snapshots) {
        for (var binding : symbols.values()) {
            String identity = selections.getOrDefault(binding.key, binding.original);
            binding.frame = Objects.requireNonNull(snapshots.get(identity), "Audio analysis was not published for the GPU step: " + identity);
        }
    }

    public void bind(CommandEncoder encoder, ShaderProgram shader, ComputeBindings.Builder resources) {
        if (buffers.isEmpty()) return;
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer storage) {
            var binding = buffers.get(storage.name());
            if (binding == null) continue;
            var buffer = binding.frame.channels() == 0 ? programs.dummyBuffer() : upload(encoder, storage.name(), binding);
            resources.storage(storage.name(), buffer.slice(), storage.access());
        }
    }

    private GpuBuffer upload(CommandEncoder encoder, String name, Binding binding) {
        var buffer = binding.buffer;
        if (buffer != null && binding.uploaded == binding.frame) return buffer;
        int size = binding.frame.byteSize();
        if (size > programs.runtime().maxStorageBufferBytes()) throw new IllegalArgumentException("Niagara audio buffer exceeds device capacity: " + name);
        var data = MemoryUtil.memAlloc(size).order(ByteOrder.LITTLE_ENDIAN);
        try {
            binding.frame.write(data);
            data.flip();
            if (buffer != null && buffer.size() == size) {
                encoder.writeToBuffer(buffer.slice(), data);
                binding.uploaded = binding.frame;
                return buffer;
            }
            var replacement = RenderSystem.getDevice().createBuffer(() -> name, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
            binding.buffer = replacement;
            binding.uploaded = binding.frame;
            if (buffer != null) buffer.close();
            return replacement;
        } finally { MemoryUtil.memFree(data); }
    }

    public @Nullable byte[] value(String name) {
        int member = name.lastIndexOf('.') + 1, separator = name.lastIndexOf('_');
        if (separator <= member || symbols.isEmpty()) return null;
        var binding = symbols.get(name.substring(member, separator));
        if (binding == null) return null;
        int value = switch (name.substring(separator + 1)) {
            case "ChannelCount" -> binding.frame.channels();
            case "Resolution" -> binding.frame.resolution();
            default -> -1;
        };
        return value < 0 ? null : ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    public void clearSnapshots() { for (var binding : symbols.values()) binding.frame = Frame.EMPTY; }
    @Override public void close() {
        for (var binding : symbols.values()) {
            if (binding.buffer != null) binding.buffer.close();
            binding.buffer = null;
            binding.uploaded = null;
        }
        clearSnapshots();
    }
}
