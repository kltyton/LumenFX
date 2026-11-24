package com.kltyton.lumenfx.client.render.niagara.gpu.property;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.property.NiagaraPropertyInterface.Frame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.property.NiagaraPropertyInterface.Output;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Publishes owner property bytes and source shader-storage attribute offsets for each simulation step. */
public final class NiagaraGpuPropertyInterfaces implements AutoCloseable {
    private static final String DATA = "_PropertyInterfaceData";
    private static final String OFFSETS = "_AttributeReadOffset";

    private static final class Binding {
        final String key, identity, symbol;
        final List<Output> outputs;
        @Nullable GpuBuffer buffer;
        @Nullable Frame uploaded;
        byte @Nullable [] offsets;

        Binding(JsonObject source) {
            key = SourceNames.canonical(source.get("compile_name").getAsString());
            identity = source.get("interface_id").getAsString();
            symbol = source.get("shader_symbol").getAsString();
            outputs = Output.read(source.getAsJsonArray("property_interface_outputs"));
        }

        void retire() {
            if (buffer != null) RenderSystem.queueFencedTask(buffer::close);
            buffer = null;
            uploaded = null;
            offsets = null;
        }
    }

    private final ComputeRuntime runtime;
    private final Map<String, Binding> bindings = new HashMap<>();

    public NiagaraGpuPropertyInterfaces(ComputeRuntime runtime, JsonObject program) {
        this.runtime = runtime;
        for (var element : program.getAsJsonArray("interfaces")) {
            var source = element.getAsJsonObject();
            if (!source.has("property_interface_outputs")) continue;
            var binding = new Binding(source);
            bindings.put(binding.symbol, binding);
        }
    }

    public void beginTick(CommandEncoder encoder, Map<String, String> selections, Map<String, Frame> frames) {
        for (var binding : bindings.values()) {
            String identity = selections.getOrDefault(binding.key, binding.identity);
            var frame = Objects.requireNonNull(frames.get(identity), "Missing Niagara GPU Property Interface " + identity);
            if (binding.uploaded == frame) continue;
            int bytes = frame.byteSize();
            if (bytes > runtime.maxStorageBufferBytes())
                throw new IllegalArgumentException("Niagara Property Interface exceeds GPU storage capacity: " + identity);
            int vectors = Math.max(1, Math.addExact(binding.outputs.size(), 3) / 4);
            var offsets = ByteBuffer.allocate(Math.multiplyExact(vectors, 16)).order(ByteOrder.LITTLE_ENDIAN);
            for (var output : binding.outputs) offsets.putInt(frame.byteOffset(output.name()));

            var old = binding.buffer;
            boolean grow = old == null || old.size() < bytes;
            var target = grow ? RenderSystem.getDevice().createBuffer(() -> binding.symbol + DATA,
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes) : old;
            try {
                GpuBufferSlice source;
                try (var upload = encoder.transientMemory().allocateGpuMapped(bytes, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
                    frame.write(upload.data().order(ByteOrder.nativeOrder()).asIntBuffer());
                    source = upload.slice();
                }
                encoder.copyToBuffer(source, target.slice(0, bytes));
            } catch (RuntimeException | Error failure) {
                if (grow) RenderSystem.queueFencedTask(target::close);
                throw failure;
            }
            if (grow && old != null) RenderSystem.queueFencedTask(old::close);
            binding.buffer = target;
            binding.uploaded = frame;
            binding.offsets = offsets.array();
        }
    }

    public byte @Nullable [] value(String qualified) {
        if (!qualified.endsWith(OFFSETS)) return null;
        String symbol = qualified.substring(qualified.lastIndexOf('.') + 1, qualified.length() - OFFSETS.length());
        var binding = bindings.get(symbol);
        return binding == null ? null : binding.offsets;
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        for (var input : shader.bindings()) {
            if (!(input instanceof ShaderBinding.StorageBuffer storage) || !storage.name().endsWith(DATA)) continue;
            var binding = bindings.get(storage.name().substring(0, storage.name().length() - DATA.length()));
            if (binding == null) continue;
            var frame = Objects.requireNonNull(binding.uploaded, "Niagara GPU Property Interface was not uploaded");
            var buffer = Objects.requireNonNull(binding.buffer);
            resources.storage(storage.name(), buffer.slice(0, frame.byteSize()), storage.access());
        }
    }

    @Override public void close() {
        bindings.values().forEach(Binding::retire);
    }
}
