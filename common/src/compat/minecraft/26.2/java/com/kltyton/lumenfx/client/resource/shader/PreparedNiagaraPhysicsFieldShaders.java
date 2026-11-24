package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

public record PreparedNiagaraPhysicsFieldShaders(String engineHead, PreparedNiagaraShaders.Stage buildClipmap) {
    static boolean required(JsonObject program) {
        for (var item : program.getAsJsonArray("interfaces"))
            if (item.getAsJsonObject().has("physics_field")) return true;
        return false;
    }

    static PreparedNiagaraPhysicsFieldShaders prepare(ResourceManager resources) {
        var bundle = NiagaraUtilityShaderResources.prepare(resources, "niagara_physics_field",
                Map.of("build_clipmap", Set.of("default")));
        return new PreparedNiagaraPhysicsFieldShaders(bundle.engineHead(), bundle.programs().get("build_clipmap").get("default"));
    }
}
