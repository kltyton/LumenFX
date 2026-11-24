package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraSpriteInfo;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Each GPU emitter publishes the parameters and bounds selected by its actual DI slots. */
final class NiagaraGpuRendererInfoBindings {
    private record Binding(String key, String original, String symbol, boolean mesh) { }
    private final NiagaraGpuRendererInfoRegistry registry;
    private final List<Binding> bindings;
    private Map<String, byte[]> values = Map.of();
    private Map<String, GpuBufferSlice> buffers = Map.of();

    NiagaraGpuRendererInfoBindings(JsonObject program, NiagaraGpuRendererInfoRegistry registry) {
        this.registry = registry;
        var result = new ArrayList<Binding>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            String type = binding.get("class").getAsString();
            if (!type.equals("NiagaraDataInterfaceSpriteRendererInfo") && !type.equals("NiagaraDataInterfaceMeshRendererInfo")) continue;
            if (!binding.has("shader_symbol")) continue;
            result.add(new Binding(SourceNames.canonical(binding.get("compile_name").getAsString()), binding.get("interface_id").getAsString(),
                    binding.get("shader_symbol").getAsString(), type.equals("NiagaraDataInterfaceMeshRendererInfo")));
        }
        bindings = List.copyOf(result);
    }
    void prepare(Map<String, String> selections) {
        var parameters = new LinkedHashMap<String, byte[]>();
        var bounds = new LinkedHashMap<String, GpuBufferSlice>();
        for (var binding : bindings) {
            String identity = selections.get(binding.key());
            if (identity == null) continue;
            if (binding.mesh()) {
                var snapshot = registry.mesh(identity);
                if (snapshot == null && SourceNames.equal(identity, binding.original())) continue;
                Objects.requireNonNull(snapshot, "Selected mesh info has no object configuration: " + identity);
                var info = snapshot.information();
                parameters.put(binding.symbol() + "_NumMeshes", words(info.meshCount()));
                parameters.put(binding.symbol() + "_bSubImageBlend", words(info.subImageBlend() ? 1 : 0));
                parameters.put(binding.symbol() + "_SubImageSize", words(Float.floatToRawIntBits(info.columns()), Float.floatToRawIntBits(info.rows())));
                bounds.put(binding.symbol() + "_MeshDataBuffer", snapshot.buffer());
            } else {
                var information = registry.sprite(identity);
                if (information == null && SourceNames.equal(identity, binding.original())) continue;
                parameters.putAll(PreparedNiagaraSpriteInfo.parameters(binding.symbol(), Objects.requireNonNull(information,
                        "Selected sprite info has no object configuration: " + identity)));
            }
        }
        values = Map.copyOf(parameters); buffers = Map.copyOf(bounds);
    }
    @Nullable byte[] value(String name) { byte[] value = values.get(name); return value == null ? null : value.clone(); }
    Map<String, GpuBufferSlice> buffers() { return buffers; }
    private static byte[] words(int... values) {
        var result = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) result.putInt(value);
        return result.array();
    }
}
