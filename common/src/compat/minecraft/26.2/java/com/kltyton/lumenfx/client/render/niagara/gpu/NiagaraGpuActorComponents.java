package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraActorComponent.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Packs the captured Actor Component parameters for the selected DI object. */
final class NiagaraGpuActorComponents {
    private record Binding(String key, String original, String symbol) { }
    private final List<Binding> bindings;
    private Map<String, byte[]> parameters = Map.of();

    NiagaraGpuActorComponents(JsonObject program) {
        var configured = new ArrayList<Binding>();
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if ("NiagaraDataInterfaceActorComponent".equals(binding.get("class").getAsString()) && binding.has("shader_symbol"))
                configured.add(new Binding(SourceNames.canonical(binding.get("compile_name").getAsString()),
                        binding.get("interface_id").getAsString(), binding.get("shader_symbol").getAsString()));
        }
        bindings = List.copyOf(configured);
    }

    void beginTick(Map<String, String> selections, Map<String, Snapshot> snapshots) {
        if (bindings.isEmpty()) return;
        var next = new LinkedHashMap<String, byte[]>();
        for (var binding : bindings) {
            String identity = selections.getOrDefault(binding.key(), binding.original());
            var snapshot = Objects.requireNonNull(snapshots.get(identity), "Actor Component not published for GPU step: " + identity);
            String prefix = binding.symbol() + "_";
            next.put(prefix + "Valid", encode(new int[]{snapshot.valid() ? 1 : 0}, 0, 1));
            next.put(prefix + "Matrix", encode(snapshot.read("GetMatrix"), 1, 16));
            int[] transform = snapshot.read("GetTransform");
            next.put(prefix + "Rotation", encode(transform, 4, 4));
            next.put(prefix + "Scale", encode(transform, 8, 3));
            next.put(prefix + "Velocity", encode(snapshot.read("GetVelocity"), 1, 3));
        }
        parameters = Map.copyOf(next);
    }

    @Nullable byte[] value(String name) { return parameters.get(name.substring(name.lastIndexOf('.') + 1)); }

    private static byte[] encode(int[] source, int offset, int words) {
        var data = ByteBuffer.allocate(words * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        data.asIntBuffer().put(source, offset, words);
        return data.array();
    }
}
