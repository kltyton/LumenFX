package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.resource.niagara.MeshRendererInfoJson;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererInformation;
import java.util.LinkedHashMap;
import java.util.Map;

/** Source-space renderer information prepared with the emitter's shader resources. */
public final class PreparedNiagaraMeshInfo {
    private final Map<String, MeshRendererInformation> interfaces;

    public PreparedNiagaraMeshInfo(Map<String, MeshRendererInformation> interfaces) {
        this.interfaces = Map.copyOf(interfaces);
    }

    public PreparedNiagaraMeshInfo(JsonObject program) {
        var result = new LinkedHashMap<String, MeshRendererInformation>();
        for (var item : program.getAsJsonArray("interfaces")) {
            JsonObject binding = item.getAsJsonObject();
            if (!"NiagaraDataInterfaceMeshRendererInfo".equals(binding.get("class").getAsString())
                    || !binding.has("shader_symbol")) continue;
            result.put(binding.get("shader_symbol").getAsString(), MeshRendererInfoJson.read(binding.getAsJsonObject("mesh_info")));
        }
        interfaces = Map.copyOf(result);
    }

    public Map<String, MeshRendererInformation> interfaces() { return interfaces; }
}
