package com.kltyton.lumenfx.client.render.niagara.gpu.socket;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.socket.NiagaraSocketReader.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuSocketBindings {
    private static final String STORAGE = "_SocketData";
    private record Binding(String compileName, String identity) { }
    private record Active(String identity, Snapshot snapshot) { }
    private final Map<String, Binding> symbols;
    private final Map<String, Active> active = new HashMap<>();
    private @Nullable NiagaraGpuSocketBuffers buffers;

    public NiagaraGpuSocketBindings(JsonObject program) {
        var definitions = new HashMap<String, Binding>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var value = item.getAsJsonObject();
            if (!value.has("socket_reader") || !value.has("shader_symbol")) continue;
            definitions.put(value.get("shader_symbol").getAsString(), new Binding(
                    SourceNames.canonical(value.get("compile_name").getAsString()), value.get("interface_id").getAsString()));
        }
        symbols = Map.copyOf(definitions);
    }

    public void beginTick(@Nullable NiagaraGpuSocketBuffers buffers, Map<String, String> selections, Map<String, Snapshot> snapshots) {
        if (symbols.isEmpty()) return;
        this.buffers = Objects.requireNonNull(buffers);
        symbols.forEach((symbol, binding) -> {
            String identity = selections.getOrDefault(binding.compileName(), binding.identity());
            var state = Objects.requireNonNull(snapshots.get(identity), "Missing Niagara Socket Reader step: " + identity);
            active.put(symbol, new Active(identity, state));
        });
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (active.isEmpty()) return;
        var registry = Objects.requireNonNull(buffers);
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer storage) {
            String name = storage.name();
            if (!name.endsWith(STORAGE)) continue;
            var state = active.get(name.substring(0, name.length() - STORAGE.length()));
            if (state != null) resources.storage(name, registry.buffer(state.identity(), state.snapshot().transforms()), storage.access());
        }
    }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        for (var entry : active.entrySet()) {
            String prefix = entry.getKey() + "_";
            if (!name.startsWith(prefix)) continue;
            var state = entry.getValue().snapshot();
            var frame = state.transforms();
            byte[] value = switch (name.substring(prefix.length())) {
                case "IsDataValid" -> word(state.valid() ? 1 : 0);
                case "InvDeltaSeconds" -> floats(state.inverseDelta());
                case "NumSockets" -> word(frame.sockets());
                case "NumFilteredSockets" -> word(frame.filtered());
                case "NumUnfilteredSockets" -> word(frame.unfiltered());
                case "SocketTransformOffset" -> word(frame.socketOffset());
                case "PreviousSocketTransformOffset" -> word(frame.previousOffset());
                case "ComponentToTranslatedWorld_Translation" -> translation(state.component());
                case "ComponentToTranslatedWorld_Rotation" -> rotation(state.component());
                case "ComponentToTranslatedWorld_Scale" -> scale(state.component());
                case "PreviousComponentToTranslatedWorld_Translation" -> translation(state.previousComponent());
                case "PreviousComponentToTranslatedWorld_Rotation" -> rotation(state.previousComponent());
                case "PreviousComponentToTranslatedWorld_Scale" -> scale(state.previousComponent());
                default -> null;
            };
            if (value != null) return value;
        }
        return null;
    }

    private static byte[] translation(Transform value) { return floats(value.x(), value.y(), value.z()); }
    private static byte[] rotation(Transform value) { return floats(value.qx(), value.qy(), value.qz(), value.qw()); }
    private static byte[] scale(Transform value) { return floats(value.scaleX(), value.scaleY(), value.scaleZ()); }
    private static byte[] word(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
    private static byte[] floats(double... values) {
        var bytes = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (double value : values) bytes.putFloat((float)value);
        return bytes.array();
    }

    public void clearSnapshots() { active.clear(); buffers = null; }
}
