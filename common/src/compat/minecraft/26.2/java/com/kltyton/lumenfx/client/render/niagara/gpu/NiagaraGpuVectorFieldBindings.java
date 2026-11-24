package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraVectorFields;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.VectorFieldBinding;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Each emitter selects its own DI objects while immutable volumes remain generation-owned. */
final class NiagaraGpuVectorFieldBindings {
    private record Binding(String key, String original) { }
    private record Field(ComputeVolume texture, int axes) { }
    private final NiagaraGpuPrograms programs;
    private final Function<String, @Nullable VectorFieldBinding> source;
    private final Map<String, Binding> bindings;
    private Map<String, Field> fields = Map.of();
    private Map<String, byte[]> values = Map.of();

    NiagaraGpuVectorFieldBindings(NiagaraGpuPrograms programs, JsonObject program, Function<String, @Nullable VectorFieldBinding> source) {
        this.programs = programs; this.source = source;
        var result = new LinkedHashMap<String, Binding>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.get("class").getAsString().equals("NiagaraDataInterfaceVectorField")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            var entry = new Binding(SourceNames.canonical(binding.get("compile_name").getAsString()), binding.get("interface_id").getAsString());
            if (result.putIfAbsent(symbol, entry) != null) throw new IllegalArgumentException("Duplicate vector field shader symbol " + symbol);
        }
        bindings = Map.copyOf(result);
    }

    void prepare(Map<String, String> selections) {
        var nextFields = new LinkedHashMap<String, Field>();
        var nextValues = new LinkedHashMap<String, byte[]>();
        for (var entry : bindings.entrySet()) {
            String identity = selections.get(entry.getValue().key());
            if (identity == null) continue;
            var selected = source.apply(identity);
            if (selected == null && SourceNames.equal(identity, entry.getValue().original())) continue;
            Objects.requireNonNull(selected, "Selected vector field has no object configuration: " + identity);
            var volume = selected.kind().equals("static")
                    ? Objects.requireNonNull(selected.volume(), "Static vector field has no source volume: " + identity)
                    : PreparedNiagaraVectorFields.blackField();
            String symbol = entry.getKey();
            int axes = selected.tilingAxes();
            nextFields.put(symbol, new Field(programs.vectorFieldVolume(volume), axes));
            nextValues.put(symbol + "_TilingAxes", floats((axes & 1) != 0 ? 1 : 0, (axes & 2) != 0 ? 1 : 0, (axes & 4) != 0 ? 1 : 0));
            nextValues.put(symbol + "_Dimensions", floats(volume.sizeX(), volume.sizeY(), volume.sizeZ()));
            nextValues.put(symbol + "_MinBounds", floats((float)volume.minX(), (float)volume.minY(), (float)volume.minZ()));
            nextValues.put(symbol + "_MaxBounds", floats((float)volume.maxX(), (float)volume.maxY(), (float)volume.maxZ()));
        }
        fields = Map.copyOf(nextFields); values = Map.copyOf(nextValues);
    }

    Set<String> overridden() { return fields.keySet(); }
    @Nullable byte[] value(String name) { byte[] value = values.get(name); return value == null ? null : value.clone(); }
    void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        for (var binding : shader.bindings()) if (binding instanceof ShaderBinding.SampledTexture texture)
            for (var entry : fields.entrySet()) {
                String name = entry.getKey() + "_Texture";
                if (texture.name().equals(name) || texture.name().startsWith(name + "_")) {
                    resources.volume(texture.name(), entry.getValue().texture(), entry.getValue().axes());
                    break;
                }
            }
    }
    private static byte[] floats(float x, float y, float z) {
        return ByteBuffer.allocate(3 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(x).putFloat(y).putFloat(z).array();
    }
}
