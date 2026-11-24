package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraCurves;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** External curve symbols follow live object proxies; compiled internal LUT offsets stay in the static buffer. */
final class NiagaraGpuCurveBindings {
    private record Binding(String key, String original, String symbol) { }
    private final NiagaraGpuCurveRegistry registry;
    private final Map<String, Binding> bindings;
    private Map<String, GpuBufferSlice> overrides = Map.of();
    private Map<String, byte[]> values = Map.of();

    NiagaraGpuCurveBindings(JsonObject program, PreparedNiagaraCurves prepared, NiagaraGpuCurveRegistry registry) {
        this.registry = registry;
        var originals = new LinkedHashMap<String, String>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var value = item.getAsJsonObject();
            originals.put(SourceNames.canonical(value.get("compile_name").getAsString()), value.get("interface_id").getAsString());
        }
        var result = new LinkedHashMap<String, Binding>();
        for (var curve : prepared.bindings()) if (curve.unique()) {
            String key = SourceNames.canonical(curve.name());
            result.put(key, new Binding(key, Objects.requireNonNull(originals.get(key)), curve.symbol()));
        }
        bindings = Map.copyOf(result);
    }
    void prepare(Map<String, String> selections) {
        var buffers = new LinkedHashMap<String, GpuBufferSlice>();
        var parameters = new LinkedHashMap<String, byte[]>();
        for (var binding : bindings.values()) {
            String identity = selections.get(binding.key());
            if (identity == null) continue;
            var snapshot = registry.prepare(identity);
            if (snapshot == null && SourceNames.equal(identity, binding.original())) continue;
            Objects.requireNonNull(snapshot, "Selected curve has no GPU LUT: " + identity);
            var curve = snapshot.curve();
            String symbol = binding.symbol();
            buffers.put(symbol + "_CurveLUT", snapshot.buffer());
            parameters.put(symbol + "_MinTime", word(curve.minimumBits()));
            parameters.put(symbol + "_MaxTime", word(curve.maximumBits()));
            parameters.put(symbol + "_InvTimeRange", word(curve.inverseRangeBits()));
            parameters.put(symbol + "_CurveLUTNumMinusOne", word(curve.samplesMinusOne()));
            parameters.put(symbol + "_LUTOffset", word(-1));
        }
        overrides = Map.copyOf(buffers); values = Map.copyOf(parameters);
    }
    Map<String, GpuBufferSlice> overrides() { return overrides; }
    @Nullable byte[] value(String name) { byte[] value = values.get(name); return value == null ? null : value.clone(); }
    private static byte[] word(int value) { return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
}
