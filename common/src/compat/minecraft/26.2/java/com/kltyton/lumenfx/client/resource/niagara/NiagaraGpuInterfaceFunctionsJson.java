package com.kltyton.lumenfx.client.resource.niagara;

import com.google.gson.JsonArray;
import com.kltyton.lumenfx.effect.niagara.interfaces.NiagaraGpuInterfaceFunctions;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Decodes the original GPU-function associations once during resource loading. */
public final class NiagaraGpuInterfaceFunctionsJson {
    private NiagaraGpuInterfaceFunctionsJson() { }
    public static @Nullable NiagaraGpuInterfaceFunctions read(@Nullable JsonArray values) {
        if (values == null) return null;
        var result = new ArrayList<NiagaraGpuInterfaceFunctions.Binding>();
        for (var item : values) {
            var value = item.getAsJsonObject();
            List<String> functions = value.getAsJsonArray("functions").asList().stream().map(function -> function.getAsString()).toList();
            result.add(new NiagaraGpuInterfaceFunctions.Binding(value.get("source_path").getAsString(),
                    value.get("variable").isJsonNull() ? null : ScriptParameterStoreJson.key(value.getAsJsonObject("variable")), functions));
        }
        return new NiagaraGpuInterfaceFunctions(result);
    }
}
