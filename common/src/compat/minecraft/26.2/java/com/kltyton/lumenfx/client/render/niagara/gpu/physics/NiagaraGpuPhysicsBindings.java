package com.kltyton.lumenfx.client.render.niagara.gpu.physics;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraPhysicsAssetReader.Buffer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraPhysicsAssetReader.Frame;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuPhysicsBindings {
    private record Source(String compileName, String identity) { }
    private record Active(String identity, Frame frame) { }
    private final Map<String, Source> symbols = new HashMap<>();
    private final Map<String, Active> selected = new HashMap<>();
    private @Nullable NiagaraGpuPhysicsBuffers buffers;

    public NiagaraGpuPhysicsBindings(JsonObject program) {
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("physics_asset") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            var source = new Source(SourceNames.canonical(binding.get("compile_name").getAsString()), binding.get("interface_id").getAsString());
            if (symbols.putIfAbsent(symbol, source) != null)
                throw new IllegalArgumentException("Duplicate Niagara Physics Asset shader symbol " + symbol);
        }
    }

    public void beginTick(@Nullable NiagaraGpuPhysicsBuffers buffers, Map<String, String> aliases, Map<String, Frame> snapshots) {
        this.buffers = buffers;
        selected.clear();
        for (var item : symbols.entrySet()) {
            var source = item.getValue();
            String identity = aliases.getOrDefault(source.compileName(), source.identity());
            var snapshot = snapshots.get(identity);
            if (snapshot != null) selected.put(item.getKey(), new Active(identity, snapshot));
        }
    }

    public void upload(CommandEncoder encoder) {
        if (buffers == null) return;
        for (var active : selected.values()) buffers.upload(encoder, active.identity(), active.frame());
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
            case "ElementOffsets" -> frame == null ? new byte[16] : ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0).putInt(frame.boxes()).putInt(frame.capsuleOffset()).putInt(frame.size()).array();
            case "BoxOrigin" -> frame == null ? new byte[12] : vector(frame.origin());
            case "BoxExtent" -> frame == null ? new byte[12] : vector(frame.extent());
            default -> null;
        };
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder bindings, GpuBufferSlice dummy) {
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
                case "PhysicsTypeBuffer" -> Buffer.TYPE;
                default -> null;
            };
            if (kind == null) continue;
            var active = selected.get(name.substring(0, separator));
            var buffer = active == null || buffers == null ? null : buffers.buffer(active.identity(), kind);
            bindings.storage(name, buffer == null ? dummy : buffer, storage.access());
        }
    }

    public void clear() { beginTick(null, Map.of(), Map.of()); }

    private static byte[] vector(Vec3 value) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat((float)value.x).putFloat((float)value.y).putFloat((float)value.z).array();
    }
}
