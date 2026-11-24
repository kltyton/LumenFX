package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraVectorFields;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Shader resources and source-space constants for static Niagara vector field interfaces. */
public final class NiagaraGpuVectorFields {
    private record Field(ComputeVolume texture, int axes) { }
    private final Map<String, Field> fields;
    private final Map<String, byte[]> values;

    NiagaraGpuVectorFields(PreparedNiagaraVectorFields prepared, Function<VectorFieldVolume, ComputeVolume> upload) {
        Map<String, Field> built = new LinkedHashMap<>();
        Map<String, byte[]> constants = new LinkedHashMap<>();
        prepared.fields().forEach((symbol, source) -> {
            VectorFieldVolume field = source.volume();
            int axes = source.tilingAxes();
            built.put(symbol, new Field(upload.apply(field), axes));
            constants.put(symbol + "_TilingAxes", floats((axes & 1) != 0 ? 1 : 0,
                    (axes & 2) != 0 ? 1 : 0, (axes & 4) != 0 ? 1 : 0));
            constants.put(symbol + "_Dimensions", floats(field.sizeX(), field.sizeY(), field.sizeZ()));
            constants.put(symbol + "_MinBounds", floats((float) field.minX(), (float) field.minY(), (float) field.minZ()));
            constants.put(symbol + "_MaxBounds", floats((float) field.maxX(), (float) field.maxY(), (float) field.maxZ()));
        });
        fields = Map.copyOf(built);
        values = Map.copyOf(constants);
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder bindings) {
        bind(shader, bindings, Set.of());
    }
    public void bind(ShaderProgram shader, ComputeBindings.Builder bindings, Set<String> overridden) {
        for (ShaderBinding binding : shader.bindings()) {
            if (!(binding instanceof ShaderBinding.SampledTexture texture)) continue;
            for (var entry : fields.entrySet()) {
                if (overridden.contains(entry.getKey())) continue;
                String name = entry.getKey() + "_Texture";
                if (texture.name().equals(name) || texture.name().startsWith(name + "_")) {
                    bindings.volume(texture.name(), entry.getValue().texture(), entry.getValue().axes());
                    break;
                }
            }
        }
    }

    public @Nullable byte[] value(String name) {
        byte[] value = values.get(name);
        return value == null ? null : value.clone();
    }

    private static byte[] floats(float x, float y, float z) {
        return ByteBuffer.allocate(3 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(x).putFloat(y).putFloat(z).array();
    }
}
