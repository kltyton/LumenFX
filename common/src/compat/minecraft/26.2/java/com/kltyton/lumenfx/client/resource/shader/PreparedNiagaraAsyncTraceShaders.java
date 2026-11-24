package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/** Offline asynchronous GPU trace provider shaders used by an imported Niagara emitter. */
public record PreparedNiagaraAsyncTraceShaders(String engineHead, PreparedNiagaraShaders.Stage globalDistanceField) {
    static boolean required(JsonObject program) {
        for (var item : program.getAsJsonArray("interfaces"))
            if ("NiagaraDataInterfaceAsyncGpuTrace".equals(item.getAsJsonObject().get("class").getAsString())) return true;
        return false;
    }

    static PreparedNiagaraAsyncTraceShaders prepare(ResourceManager resources) {
        var bundle = NiagaraUtilityShaderResources.prepare(resources, "niagara_async_trace",
                Map.of("global_distance_field", Set.of("default")));
        return new PreparedNiagaraAsyncTraceShaders(bundle.engineHead(), bundle.programs().get("global_distance_field").get("default"));
    }
}
