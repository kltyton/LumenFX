package com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshFrame;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Binds each shader symbol to the selected live dynamic mesh for the queued GPU step. */
public final class NiagaraGpuDynamicMeshBindings {
    private enum Storage { Sections, Indices, Vertices }
    private static final class Binding {
        final String key, original;
        NiagaraGpuDynamicMeshes.@Nullable Mesh selected;
        Binding(String key, String original) { this.key = key; this.original = original; }
    }
    private record BufferBinding(Binding binding, Storage stream) { }
    private final Map<String, Binding> symbols;
    private final Map<String, BufferBinding> buffers;

    public NiagaraGpuDynamicMeshBindings(JsonObject program) {
        var names = new HashMap<String, Binding>();
        var storage = new HashMap<String, BufferBinding>();
        for (var entry : program.getAsJsonArray("interfaces")) {
            var value = entry.getAsJsonObject();
            if (!value.has("shader_symbol") || !value.get("class").getAsString().equals("NiagaraDataInterfaceDynamicMesh")) continue;
            String name = value.get("shader_symbol").getAsString();
            var binding = new Binding(SourceNames.canonical(value.get("compile_name").getAsString()), value.get("interface_id").getAsString());
            if (names.putIfAbsent(name, binding) != null) throw new IllegalArgumentException("Duplicate Niagara dynamic mesh shader symbol " + name);
            storage.put(name + "_SectionBuffer", new BufferBinding(binding, Storage.Sections));
            storage.put(name + "_IndexBuffer", new BufferBinding(binding, Storage.Indices));
            storage.put(name + "_VertexBuffer", new BufferBinding(binding, Storage.Vertices));
        }
        symbols = Map.copyOf(names);
        buffers = Map.copyOf(storage);
    }

    public void beginTick(CommandEncoder encoder, @Nullable NiagaraGpuDynamicMeshes registry, Map<String, String> selections,
                          Map<String, NiagaraDynamicMeshFrame> snapshots) {
        if (symbols.isEmpty()) return;
        var meshes = Objects.requireNonNull(registry);
        var prepared = new HashSet<String>();
        for (var binding : symbols.values()) {
            String id = selections.getOrDefault(binding.key, binding.original);
            if (prepared.add(id))
                meshes.prepare(encoder, id, Objects.requireNonNull(snapshots.get(id), "Missing Niagara dynamic mesh for GPU step: " + id));
            binding.selected = meshes.mesh(id);
        }
    }

    public @Nullable byte[] value(String name) {
        int start = name.lastIndexOf('.') + 1, separator = name.lastIndexOf('_');
        if (separator <= start) return null;
        var binding = symbols.get(name.substring(start, separator));
        if (binding == null || binding.selected == null) return null;
        var frame = binding.selected.layout();
        return switch (name.substring(separator + 1)) {
            case "NumSections" -> word(frame.sections().size());
            case "NumTriangles" -> word(frame.triangles());
            case "NumVertices" -> word(frame.vertices());
            case "NumTexCoords" -> word(frame.uvChannels());
            case "PositionOffset" -> word(0);
            case "TangentBasisOffset" -> word(offset(frame.tangentOffset()));
            case "TexCoordOffset" -> word(offset(frame.uvOffset()));
            case "ColorOffset" -> word(offset(frame.colorOffset()));
            default -> null;
        };
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer storage) {
            var binding = buffers.get(storage.name());
            if (binding == null) continue;
            var mesh = Objects.requireNonNull(binding.binding().selected);
            var buffer = switch (binding.stream()) {
                case Sections -> mesh.sections();
                case Indices -> mesh.indices();
                case Vertices -> mesh.vertices();
            };
            resources.storage(storage.name(), buffer.slice(), storage.access());
        }
    }

    private static int offset(int words) { return words < 0 ? -1 >>> 2 : words; }
    private static byte[] word(int value) { return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
    public void clearSnapshots() { for (var binding : symbols.values()) binding.selected = null; }
}
