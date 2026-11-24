package com.kltyton.lumenfx.client.render.niagara.gpu.geometry;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.GeometryCollectionFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.GeometryCollectionFrame.Buffer;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuGeometryBindings {
    private record Binding(String compileName, String identity) { }
    private record Resource(Binding binding, Buffer kind) { }
    private record Uniform(Binding binding, Function<GeometryCollectionFrame, byte[]> value) { }
    private record Active(String identity, GeometryCollectionFrame frame) { }

    private final NiagaraGpuPrograms programs;
    private final Map<String, Binding> bindings = new HashMap<>();
    private final Map<String, Resource> resources = new HashMap<>();
    private final Map<String, Uniform> uniforms = new HashMap<>();
    private final Map<Binding, Active> active = new HashMap<>();
    private @Nullable NiagaraGpuGeometryBuffers owner;

    public NiagaraGpuGeometryBindings(NiagaraGpuPrograms programs, JsonObject program) {
        this.programs = programs;
        for (var item : program.getAsJsonArray("interfaces")) {
            var source = item.getAsJsonObject();
            if (!source.has("geometry_collection") || !source.has("shader_symbol")) continue;
            String symbol = source.get("shader_symbol").getAsString();
            var binding = new Binding(SourceNames.canonical(source.get("compile_name").getAsString()), source.get("interface_id").getAsString());
            if (bindings.putIfAbsent(symbol, binding) != null)
                throw new IllegalArgumentException("Duplicate Niagara Geometry Collection shader symbol " + symbol);
            for (var kind : Buffer.values()) resources.put(symbol + "_" + kind.symbol(), new Resource(binding, kind));
            uniforms.put(symbol + "_BoundsMin", new Uniform(binding, frame -> vector(frame.minimum())));
            uniforms.put(symbol + "_BoundsMax", new Uniform(binding, frame -> vector(frame.maximum())));
            uniforms.put(symbol + "_NumPieces", new Uniform(binding, frame -> bytes(1).putInt(frame.count()).array()));
            uniforms.put(symbol + "_RootTransform_Translation", new Uniform(binding, frame -> numbers(frame.root().x(), frame.root().y(), frame.root().z())));
            uniforms.put(symbol + "_RootTransform_Rotation", new Uniform(binding, frame -> {
                var root = frame.root();
                return numbers(root.qx(), root.qy(), root.qz(), root.qw());
            }));
            uniforms.put(symbol + "_RootTransform_Scale", new Uniform(binding, frame -> numbers(frame.root().scaleX(), frame.root().scaleY(), frame.root().scaleZ())));
        }
    }

    public void beginTick(@Nullable NiagaraGpuGeometryBuffers buffers, Map<String, String> aliases,
                          Map<String, GeometryCollectionFrame> frames) {
        active.clear();
        if (bindings.isEmpty()) return;
        owner = Objects.requireNonNull(buffers);
        for (var binding : bindings.values()) {
            String id = aliases.getOrDefault(binding.compileName(), binding.identity());
            var frame = Objects.requireNonNull(frames.get(id), "Missing Niagara Geometry Collection step: " + id);
            active.put(binding, new Active(id, frame));
        }
    }

    public @Nullable byte[] value(String qualifiedName) {
        var uniform = uniforms.get(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
        if (uniform == null) return null;
        return uniform.value().apply(active.get(uniform.binding()).frame());
    }

    public void bind(CommandEncoder encoder, ShaderProgram shader, ComputeBindings.Builder target) {
        if (active.isEmpty()) return;
        var buffers = Objects.requireNonNull(owner);
        for (var resource : shader.bindings()) {
            if (!(resource instanceof ShaderBinding.StorageBuffer storage)) continue;
            var descriptor = resources.get(storage.name());
            if (descriptor == null) continue;
            var current = active.get(descriptor.binding());
            var entry = buffers.prepare(encoder, current.identity(), current.frame());
            var slice = entry.buffer(descriptor.kind());
            target.storage(storage.name(), slice == null ? programs.dummyBuffer().slice() : slice, storage.access());
        }
    }

    public void clear() { active.clear(); owner = null; }

    private static ByteBuffer bytes(int words) {
        return ByteBuffer.allocate(words * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] vector(Vec3 value) { return numbers(value.x, value.y, value.z); }

    private static byte[] numbers(double... values) {
        var result = bytes(values.length);
        for (double value : values) result.putFloat((float)value);
        return result.array();
    }
}
