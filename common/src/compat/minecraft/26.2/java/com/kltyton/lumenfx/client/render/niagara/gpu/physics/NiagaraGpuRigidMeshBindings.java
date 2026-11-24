package com.kltyton.lumenfx.client.render.niagara.gpu.physics;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshWorlds.Snapshot;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraRigidMeshCollision.Buffer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraRigidMeshCollision.Frame;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class NiagaraGpuRigidMeshBindings {
    private record Source(String compileName, String id) { }
    private record Selection(String id, Frame frame) { }
    private final Map<String, Source> symbols = new HashMap<>();
    private final Map<String, Selection> selected = new HashMap<>();
    private final NiagaraGpuPrograms programs;
    private @Nullable Buffers registry;

    public NiagaraGpuRigidMeshBindings(NiagaraGpuPrograms programs, JsonObject program) {
        this.programs = programs;
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("rigid_mesh_collision") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            var source = new Source(SourceNames.canonical(binding.get("compile_name").getAsString()), binding.get("interface_id").getAsString());
            if (symbols.putIfAbsent(symbol, source) != null)
                throw new IllegalArgumentException("Duplicate Niagara Rigid Mesh Collision shader symbol " + symbol);
        }
    }

    public void beginTick(@Nullable Buffers registry, Map<String, String> aliases, Map<String, Frame> snapshots) {
        this.registry = registry;
        selected.clear();
        for (var entry : symbols.entrySet()) {
            var source = entry.getValue();
            String id = aliases.getOrDefault(source.compileName(), source.id());
            var frame = snapshots.get(id);
            if (frame != null && frame.capacity() > 0) selected.put(entry.getKey(), new Selection(id, frame));
        }
    }

    public void upload(CommandEncoder encoder) {
        if (registry == null) return;
        for (var value : selected.values()) registry.upload(encoder, value.id(), value.frame(), programs);
    }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int separator = name.lastIndexOf('_');
        if (separator < 0) return null;
        String symbol = name.substring(0, separator);
        if (!symbols.containsKey(symbol)) return null;
        var active = selected.get(symbol);
        var frame = active == null ? null : active.frame();
        return switch (name.substring(separator + 1)) {
            case "MaxTransforms" -> words(frame == null ? 0 : frame.capacity() * 2);
            case "CurrentOffset" -> words(0);
            case "PreviousOffset" -> words(frame == null ? 0 : frame.capacity() * 3);
            case "ElementOffsets" -> frame == null ? new byte[16] : words(0, frame.boxes(), frame.capsulesAt(), frame.count());
            case "CombinedBBoxWorldMin" -> frame == null ? new byte[12] : vector(frame.minimum());
            case "CombinedBBoxWorldMax" -> frame == null ? new byte[12] : vector(frame.maximum());
            default -> null;
        };
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (symbols.isEmpty()) return;
        for (var resource : shader.bindings()) {
            if (!(resource instanceof ShaderBinding.StorageBuffer storage)) continue;
            String name = storage.name();
            int separator = name.lastIndexOf('_');
            if (separator < 0 || !symbols.containsKey(name.substring(0, separator))) continue;
            Buffer kind = switch (name.substring(separator + 1)) {
                case "WorldTransformBuffer" -> Buffer.WORLD;
                case "InverseTransformBuffer" -> Buffer.INVERSE;
                case "ElementExtentBuffer" -> Buffer.EXTENT;
                case "MeshScaleBuffer" -> Buffer.SCALE;
                case "PhysicsTypeBuffer" -> Buffer.TYPE;
                case "DFIndexBuffer" -> Buffer.DISTANCE_FIELD;
                default -> null;
            };
            if (kind == null) continue;
            var value = selected.get(name.substring(0, separator));
            var buffer = value == null || registry == null ? null : registry.buffer(value.id(), kind);
            resources.storage(name, (buffer == null ? programs.dummyBuffer() : buffer).slice(), storage.access());
        }
    }

    public void clear() { beginTick(null, Map.of(), Map.of()); }

    private static byte[] words(int... values) {
        var buffer = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }
    private static byte[] vector(Vec3 value) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat((float)value.x).putFloat((float)value.y).putFloat((float)value.z).array();
    }

    /** System-owned proxy buffers. Only the distance-field indices depend on the currently published scene. */
    public static final class Buffers implements AutoCloseable {
        private final Map<String, Entry> entries = new HashMap<>();

        private static final class Entry implements AutoCloseable {
            final int capacity;
            final Map<Buffer, GpuBuffer> buffers = new EnumMap<>(Buffer.class);
            @Nullable Frame frame;
            @Nullable Snapshot scene;

            Entry(String id, Frame data, NiagaraGpuPrograms programs) {
                capacity = data.capacity();
                if (data.bytes(Buffer.WORLD) > programs.runtime().maxStorageBufferBytes())
                    throw new IllegalArgumentException("Niagara Rigid Mesh Collision exceeds storage capacity: " + id);
                try {
                    for (Buffer kind : Buffer.values())
                        buffers.put(kind, RenderSystem.getDevice().createBuffer(() -> id + ".RigidMesh." + kind,
                                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data.bytes(kind)));
                } catch (RuntimeException | Error failure) {
                    close();
                    throw failure;
                }
            }
            @Override public void close() { buffers.values().forEach(GpuBuffer::close); }
        }

        private void upload(CommandEncoder encoder, String id, Frame frame, NiagaraGpuPrograms programs) {
            String key = SourceNames.canonical(id);
            var previous = entries.get(key);
            var entry = previous != null && previous.capacity == frame.capacity() ? previous : new Entry(id, frame, programs);
            var fields = programs.sharedMeshFields();
            var scene = fields.scene();
            if (entry.frame == frame && entry.scene == scene) return;
            ByteBuffer pixels = null;
            try {
                pixels = MemoryUtil.memAlloc(frame.bytes(Buffer.WORLD)).order(ByteOrder.LITTLE_ENDIAN);
                for (var item : entry.buffers.entrySet()) {
                    Buffer kind = item.getKey();
                    if (kind != Buffer.DISTANCE_FIELD && entry.frame == frame) continue;
                    pixels.clear();
                    if (kind == Buffer.DISTANCE_FIELD) {
                        for (int index = 0; index < frame.capacity(); index++) {
                            var reference = frame.distanceField(index);
                            pixels.putInt(reference == null ? -1 : fields.sceneIndex(reference));
                        }
                    } else frame.write(kind, pixels);
                    pixels.flip();
                    encoder.writeToBuffer(item.getValue().slice(), pixels);
                }
                entry.frame = frame;
                entry.scene = scene;
                if (entry != previous) {
                    entries.put(key, entry);
                    if (previous != null) previous.close();
                }
            } catch (RuntimeException | Error failure) {
                if (entry != previous) entry.close();
                throw failure;
            } finally {
                if (pixels != null) MemoryUtil.memFree(pixels);
            }
        }

        private @Nullable GpuBuffer buffer(String id, Buffer kind) {
            var entry = entries.get(SourceNames.canonical(id));
            return entry == null ? null : entry.buffers.get(kind);
        }

        @Override public void close() {
            entries.values().forEach(Entry::close);
            entries.clear();
        }
    }
}
