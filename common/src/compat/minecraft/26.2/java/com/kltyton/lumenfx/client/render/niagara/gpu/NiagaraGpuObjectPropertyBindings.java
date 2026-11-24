package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Kind;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader.Request;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Publishes each compiled reader's loose offsets, transforms and word-addressed data buffer. */
final class NiagaraGpuObjectPropertyBindings implements AutoCloseable {
    private record Property(Kind kind, String name) { }
    private static final class Binding implements AutoCloseable {
        final String key, original, symbol;
        final List<Request> functions;
        @Nullable GpuBuffer data;

        Binding(String key, String original, String symbol, List<Request> functions) {
            this.key = key;
            this.original = original;
            this.symbol = symbol;
            this.functions = List.copyOf(functions);
        }

        @Override public void close() {
            if (data != null) { data.close(); data = null; }
        }
    }

    private final NiagaraGpuPrograms programs;
    private final List<Binding> bindings;
    private final Map<String, byte[]> uniforms = new HashMap<>();
    private final Map<String, GpuBufferSlice> buffers = new HashMap<>();

    NiagaraGpuObjectPropertyBindings(NiagaraGpuPrograms programs, JsonObject metadata) {
        this.programs = programs;
        var readers = new ArrayList<Binding>();
        for (var element : metadata.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceUObjectPropertyReader".equals(binding.get("class").getAsString())
                    || !binding.has("shader_symbol")) continue;
            var functions = new ArrayList<Request>();
            for (var item : binding.getAsJsonArray("property_reader_functions")) {
                var request = item.getAsJsonObject();
                functions.add(Request.read(request.get("name").getAsString(), request));
            }
            readers.add(new Binding(SourceNames.canonical(binding.get("compile_name").getAsString()),
                    binding.get("interface_id").getAsString(), binding.get("shader_symbol").getAsString(), functions));
        }
        bindings = List.copyOf(readers);
    }

    void beginTick(CommandEncoder encoder, Map<String, String> selections, Map<String, Snapshot> snapshots) {
        uniforms.clear();
        buffers.clear();
        for (var binding : bindings) {
            String identity = selections.getOrDefault(binding.key, binding.original);
            var snapshot = Objects.requireNonNull(snapshots.get(identity), "Object reader not published for GPU step: " + identity);
            prepare(encoder, binding, snapshot);
        }
    }

    private void prepare(CommandEncoder encoder, Binding binding, Snapshot snapshot) {
        int[] offsets = new int[Math.max(4, (Math.addExact(binding.functions.size(), 3) / 4) * 4)];
        Arrays.fill(offsets, -1);
        int[] words = new int[binding.functions.stream().filter(request -> request.kind() != null)
                .mapToInt(request -> request.outputs() - 1).sum()];
        var offsetsByProperty = new HashMap<Property, Integer>();
        int size = 0;
        for (int index = 0; index < binding.functions.size(); index++) {
            var request = binding.functions.get(index);
            if (request.kind() == null) continue;
            var property = new Property(request.kind(), SourceNames.canonical(snapshot.propertyName(request)));
            Integer previous = offsetsByProperty.get(property);
            if (previous != null) { offsets[index] = previous; continue; }
            int[] value = snapshot.read(request);
            if (value[0] != 0) {
                offsets[index] = size;
                System.arraycopy(value, 1, words, size, value.length - 1);
            }
            size += value.length - 1;
            offsetsByProperty.put(property, offsets[index]);
        }
        uniforms.put(binding.symbol + "_PropertyOffsets", encode(offsets, 0, offsets.length));
        transform(binding.symbol + "_Transform", snapshot.read(new Request(null, "", false)));
        transform(binding.symbol + "_InvTransform", snapshot.read(new Request(null, "", true)));
        buffers.put(binding.symbol + "_PropertyData", size == 0 ? programs.dummyBuffer().slice()
                : upload(encoder, binding, words, size));
    }

    private GpuBufferSlice upload(CommandEncoder encoder, Binding binding, int[] words, int count) {
        long bytes = Math.multiplyExact((long)count, Integer.BYTES);
        if (bytes > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara object reader exceeds GPU storage capacity: " + binding.symbol);
        var target = binding.data;
        if (target == null || target.size() < bytes) {
            var replacement = RenderSystem.getDevice().createBuffer(() -> binding.symbol + ".PropertyData",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes);
            binding.data = replacement;
            if (target != null) target.close();
            target = replacement;
        }
        GpuBufferSlice source;
        try (var upload = encoder.transientMemory().allocateGpuMapped(bytes, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
            upload.data().order(ByteOrder.nativeOrder()).asIntBuffer().put(words, 0, count);
            source = upload.slice();
        }
        var slice = target.slice(0, bytes);
        encoder.copyToBuffer(source, slice);
        return slice;
    }

    private void transform(String prefix, int[] words) {
        words[0] = words[0] == 0 ? 0 : 1;
        uniforms.put(prefix + "Valid", encode(words, 0, 1));
        uniforms.put(prefix + "Location", encode(words, 1, 3));
        uniforms.put(prefix + "Rotation", encode(words, 4, 4));
        uniforms.put(prefix + "Scale", encode(words, 8, 3));
    }

    @Nullable byte[] value(String name) { return uniforms.get(name.substring(name.lastIndexOf('.') + 1)); }

    void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        for (var input : shader.bindings()) if (input instanceof ShaderBinding.StorageBuffer storage) {
            var buffer = buffers.get(storage.name());
            if (buffer != null) resources.storage(storage.name(), buffer, storage.access());
        }
    }

    private static byte[] encode(int[] words, int offset, int count) {
        var bytes = ByteBuffer.allocate(Math.multiplyExact(count, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asIntBuffer().put(words, offset, count);
        return bytes.array();
    }

    @Override public void close() {
        bindings.forEach(Binding::close);
        buffers.clear();
        uniforms.clear();
    }
}
