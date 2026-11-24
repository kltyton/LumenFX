package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Authoritative production JSON effect parser matching LumenFX 26.2 {@code EffectJson.read(...)}.
 * Handles {@code format_version: 1} Unity Shuriken AND UE5 Niagara exports identically to 26.2.
 */
public final class ProductionEffectParser {
    private ProductionEffectParser() { }

    public static EffectModel parse(String effectId, String jsonText, @Nullable McResourceBridge bridge) {
        JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
        return parse(effectId, root, bridge);
    }

    public static EffectModel parse(String effectId, JsonObject root, @Nullable McResourceBridge bridge) {
        if (root == null) throw new JsonParseException("Null effect JSON for " + effectId);
        if (root.has("format_version") && root.get("format_version").getAsInt() != 1) {
            throw new JsonParseException("Unsupported LumenFX format_version for " + effectId);
        }

        Map<String, MaterialModel> materials = new LinkedHashMap<>();
        if (root.has("materials") && root.get("materials").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("materials").entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    materials.put(entry.getKey(), MaterialModel.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }
        }

        List<UnityEmitterModel> emitters = new ArrayList<>();
        if (root.has("emitters") && root.get("emitters").isJsonArray()) {
            int idx = 0;
            for (JsonElement el : root.getAsJsonArray("emitters")) {
                if (el.isJsonObject()) {
                    emitters.add(UnityEmitterModel.fromJson(el.getAsJsonObject(), materials, bridge, "emitter_" + (idx++)));
                }
            }
        }

        NiagaraSystemModel niagara = null;
        if (root.has("niagara") && root.get("niagara").isJsonObject()) {
            niagara = NiagaraSystemModel.fromJson(effectId, root.getAsJsonObject("niagara"), emitters, bridge);
        } else {
            boolean anyNiagara = false;
            for (UnityEmitterModel e : emitters) {
                if (e.integrator() == UnityEmitterModel.IntegratorKind.NIAGARA) {
                    anyNiagara = true;
                    break;
                }
            }
            if (anyNiagara) {
                niagara = NiagaraSystemModel.fromJson(effectId, root, emitters, bridge);
            }
        }

        EffectModel.Kind kind = niagara != null ? EffectModel.Kind.NIAGARA : EffectModel.Kind.UNITY;
        return new EffectModel(effectId, kind, materials, emitters, niagara, null);
    }
}
