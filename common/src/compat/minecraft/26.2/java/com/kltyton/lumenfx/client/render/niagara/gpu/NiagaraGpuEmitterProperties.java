package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraEmitterBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.EmitterBinding;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** One tick's immutable game-to-render snapshot for emitter properties queries. */
final class NiagaraGpuEmitterProperties {
    private record Binding(String key, String original, String target) { }
    private final Map<String, Binding> emitters;
    private final Function<String, NiagaraEmitterBounds.Snapshot> sources;
    private Map<String, byte[]> parameters = Map.of();

    NiagaraGpuEmitterProperties(JsonObject program, Function<String, NiagaraEmitterBounds.Snapshot> sources) {
        this.sources = sources;
        Map<String, Binding> bindings = new LinkedHashMap<>();
        for (var element : program.getAsJsonArray("interfaces")) {
            JsonObject binding = element.getAsJsonObject();
            if (!binding.get("class").getAsString().equals("NiagaraDataInterfaceEmitterProperties")) continue;
            if (!binding.has("emitter_properties"))
                throw new IllegalArgumentException("Niagara emitter properties lacks its source binding");
            if (!binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            String key = SourceNames.canonical(binding.get("compile_name").getAsString());
            var value = new Binding(key, binding.get("interface_id").getAsString(),
                    binding.getAsJsonObject("emitter_properties").get("emitter").getAsString());
            if (bindings.putIfAbsent(symbol, value) != null)
                throw new IllegalArgumentException("Duplicate Niagara emitter properties symbol " + symbol);
        }
        emitters = Map.copyOf(bindings);
    }

    void beginTick(Map<String, String> selections, @Nullable Function<String, @Nullable EmitterBinding> objects,
                   @Nullable Map<String, NiagaraEmitterBounds.Snapshot> snapshots) {
        if (emitters.isEmpty()) return;
        Map<String, byte[]> next = new LinkedHashMap<>();
        emitters.forEach((symbol, binding) -> {
            String identity = selections.getOrDefault(binding.key(), binding.original());
            var object = objects == null || !selections.containsKey(binding.key()) ? null : objects.apply(identity);
            if (object != null && !object.type().equals("NiagaraDataInterfaceEmitterProperties"))
                throw new IllegalStateException("Selected emitter properties object has a different DI class: " + identity);
            if (object == null && !SourceNames.equal(identity, binding.original()))
                throw new IllegalStateException("Selected emitter properties object has no binding: " + identity);
            String target = object == null ? binding.target() : object.emitter();
            var snapshot = snapshots == null ? sources.apply(target) : snapshots.get(SourceNames.canonical(target));
            next.put(symbol + "_LocalSpace", integer(snapshot != null && snapshot.localSpace() ? 1 : 0));
            putBounds(next, symbol + "_Bounds", snapshot == null ? null : snapshot.bounds());
            putBounds(next, symbol + "_FixedBounds", snapshot == null ? null : snapshot.fixedBounds());
        });
        parameters = Map.copyOf(next);
    }

    @Nullable byte[] value(String qualified) {
        return parameters.get(qualified.substring(qualified.lastIndexOf('.') + 1));
    }

    private static void putBounds(Map<String, byte[]> parameters, String prefix, @Nullable NiagaraBounds box) {
        parameters.put(prefix + "Valid", integer(box == null ? 0 : 1));
        parameters.put(prefix + "Min", vector(box == null ? 0 : (float)box.minX(),
                box == null ? 0 : (float)box.minY(), box == null ? 0 : (float)box.minZ()));
        parameters.put(prefix + "Max", vector(box == null ? 0 : (float)box.maxX(),
                box == null ? 0 : (float)box.maxY(), box == null ? 0 : (float)box.maxZ()));
    }

    private static byte[] integer(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] vector(float x, float y, float z) {
        return ByteBuffer.allocate(3 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(x).putFloat(y).putFloat(z).array();
    }
}
