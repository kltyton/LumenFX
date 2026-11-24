package com.kltyton.lumenfx.client.resource.niagara;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.HostExecutionContext;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Decodes GPU host parameter contexts in the same resource generation as their Combined context. */
public final class GpuHostContextJson {
    private GpuHostContextJson() { }
    public static List<HostExecutionContext> read(@Nullable JsonArray values) {
        return read(values, id -> { throw new JsonParseException("Vector field resolver required for " + id); });
    }
    public static List<HostExecutionContext> read(@Nullable JsonArray values, Function<Identifier, VectorFieldVolume> fields) {
        if (values == null) return List.of();
        var result = new ArrayList<HostExecutionContext>();
        for (var item : values) {
            JsonObject value = item.getAsJsonObject();
            var collections = new ArrayList<NiagaraCollectionBinding>();
            for (var binding : value.getAsJsonArray("collections")) {
                var collection = NiagaraCollectionJson.read(binding.getAsJsonObject());
                if (collection == null) throw new JsonParseException("GPU host collection has no source definition");
                collections.add(collection);
            }
            result.add(new HostExecutionContext(text(value, "usage"), text(value, "usage_id"), value.get("event_index").getAsInt(),
                    ScriptParameterStoreJson.read(value.getAsJsonObject("execution_store"), fields), collections));
        }
        return List.copyOf(result);
    }
    private static String text(JsonObject value, String name) {
        return !value.has(name) || value.get(name).isJsonNull() ? "" : value.get(name).getAsString();
    }
}
