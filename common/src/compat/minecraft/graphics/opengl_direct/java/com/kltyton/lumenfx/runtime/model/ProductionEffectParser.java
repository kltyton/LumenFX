package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.runtime.audio.DirectSpatialAudioEngine.SoundCueSpec;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.gl.volume.DirectGlVolumeAndLightRenderer.LightSpec;
import com.kltyton.lumenfx.runtime.gl.volume.DirectGlVolumeAndLightRenderer.VolumeSpec;
import com.kltyton.lumenfx.runtime.model.EffectModel.DecalAssetSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Authoritative production JSON effect parser matching LumenFX 26.2 {@code EffectJson.read(...)}.
 * Handles {@code format_version: 1} Unity Shuriken, UE5 Niagara, Spatial Audio cues, 3D Gaussian Splats,
 * Groom hair strands, Volumetric Fog/Materials, Deferred Decals, Dynamic Point/Spot Lights, and RVT materials.
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

        List<SoundCueSpec> audioCues = parseAudioCues(root);
        List<GaussianSplatModel> splats = parseSplats(effectId, root);
        List<GroomAssetModel> grooms = parseGrooms(effectId, root);
        List<VolumeSpec> volumes = parseVolumes(root);
        List<DecalAssetSpec> decals = parseDecals(root);
        List<LightSpec> lights = parseLights(root);
        List<String> rvtMaterials = parseRvtMaterials(root, materials);

        EffectModel.Kind kind = niagara != null ? EffectModel.Kind.NIAGARA : EffectModel.Kind.UNITY;
        return new EffectModel(effectId, kind, materials, emitters, niagara, null,
                audioCues, splats, grooms, volumes, decals, lights, rvtMaterials);
    }

    private static List<SoundCueSpec> parseAudioCues(JsonObject root) {
        JsonArray arr = firstArray(root, "audio", "sounds", "audio_cues");
        if (arr == null) return List.of();
        List<SoundCueSpec> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String soundId = readString(o, "sound", readString(o, "sound_id", readString(o, "id", "")));
            if (soundId.isBlank()) continue;
            out.add(new SoundCueSpec(
                    soundId,
                    readFloat(o, "volume", 1.0f),
                    readFloat(o, "pitch", 1.0f),
                    readFloat(o, "reference_distance", 4.0f),
                    readFloat(o, "max_distance", 48.0f),
                    readFloat(o, "rolloff", 1.0f),
                    readFloat(o, "delay", 0.0f),
                    readBool(o, "loop", readBool(o, "looping", false)),
                    readBool(o, "spatialized", readBool(o, "spatial", true))
            ));
        }
        return out;
    }

    private static List<GaussianSplatModel> parseSplats(String effectId, JsonObject root) {
        List<GaussianSplatModel> out = new ArrayList<>();
        JsonElement el = root.has("gaussian_splats") ? root.get("gaussian_splats") : root.get("splats");
        if (el == null) return out;
        if (el.isJsonObject()) {
            GaussianSplatModel m = GaussianSplatModel.fromJson(effectId + "#splats", el.getAsJsonObject());
            if (m.count() > 0) out.add(m);
        } else if (el.isJsonArray()) {
            int i = 0;
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() > 0 && arr.get(0).isJsonObject() && arr.get(0).getAsJsonObject().has("splats")) {
                for (JsonElement item : arr) {
                    if (item.isJsonObject()) {
                        GaussianSplatModel m = GaussianSplatModel.fromJson(effectId + "#splats_" + (i++), item.getAsJsonObject());
                        if (m.count() > 0) out.add(m);
                    }
                }
            } else {
                JsonObject wrapper = new JsonObject();
                wrapper.add("splats", arr);
                GaussianSplatModel m = GaussianSplatModel.fromJson(effectId + "#splats", wrapper);
                if (m.count() > 0) out.add(m);
            }
        }
        return out;
    }

    private static List<GroomAssetModel> parseGrooms(String effectId, JsonObject root) {
        List<GroomAssetModel> out = new ArrayList<>();
        JsonElement el = root.has("grooms") ? root.get("grooms") : root.get("hair");
        if (el == null) return out;
        if (el.isJsonObject()) {
            GroomAssetModel g = GroomAssetModel.fromJson(effectId + "#groom", el.getAsJsonObject());
            if (!g.strands().isEmpty()) out.add(g);
        } else if (el.isJsonArray()) {
            int i = 0;
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    GroomAssetModel g = GroomAssetModel.fromJson(effectId + "#groom_" + (i++), item.getAsJsonObject());
                    if (!g.strands().isEmpty()) out.add(g);
                }
            }
        }
        return out;
    }

    private static List<VolumeSpec> parseVolumes(JsonObject root) {
        JsonArray arr = firstArray(root, "volumes", "volumetric");
        if (arr == null) return List.of();
        List<VolumeSpec> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            float[] pos = readVec3(o, "position", 0f, 0f, 0f);
            float[] size = readVec3(o, "size", 2f, 2f, 2f);
            float[] col = readVec3(o, "color", 0.8f, 0.9f, 1.0f);
            out.add(new VolumeSpec(pos[0], pos[1], pos[2], size[0], size[1], size[2],
                    col[0], col[1], col[2],
                    readFloat(o, "density", 0.65f),
                    readFloat(o, "anisotropy", 0.3f)));
        }
        return out;
    }

    private static List<DecalAssetSpec> parseDecals(JsonObject root) {
        JsonArray arr = firstArray(root, "decals");
        if (arr == null) return List.of();
        List<DecalAssetSpec> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            float[] pos = readVec3(o, "position", 0f, 0f, 0f);
            float[] size = readVec3(o, "size", 2f, 1f, 2f);
            float[] col = readVec4(o, "color", 1f, 1f, 1f, 1f);
            String tex = readString(o, "texture", "");
            out.add(new DecalAssetSpec(pos[0], pos[1], pos[2], size[0], size[1], size[2],
                    col[0], col[1], col[2], col[3], tex.isBlank() ? null : tex));
        }
        return out;
    }

    private static List<LightSpec> parseLights(JsonObject root) {
        JsonArray arr = firstArray(root, "lights");
        if (arr == null) return List.of();
        List<LightSpec> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            float[] pos = readVec3(o, "position", 0f, 0f, 0f);
            float[] col = readVec3(o, "color", 1f, 0.9f, 0.75f);
            float[] dir = readVec3(o, "direction", 0f, -1f, 0f);
            boolean spot = "spot".equalsIgnoreCase(readString(o, "type", "point")) || readBool(o, "spot", false);
            float outerDeg = readFloat(o, "outer_cone_degrees", 45f);
            out.add(new LightSpec(pos[0], pos[1], pos[2],
                    readFloat(o, "radius", 6.0f),
                    col[0], col[1], col[2],
                    readFloat(o, "intensity", 1.5f),
                    spot, dir[0], dir[1], dir[2], (float) Math.cos(Math.toRadians(outerDeg))));
        }
        return out;
    }

    private static List<String> parseRvtMaterials(JsonObject root, Map<String, MaterialModel> materials) {
        List<String> out = new ArrayList<>();
        for (MaterialModel m : materials.values()) {
            if (m.rvt()) out.add(m.name());
        }
        JsonArray arr = firstArray(root, "rvt", "virtual_textures");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) out.add(el.getAsString());
                else if (el.isJsonObject()) {
                    String n = readString(el.getAsJsonObject(), "material", readString(el.getAsJsonObject(), "id", ""));
                    if (!n.isBlank()) out.add(n);
                }
            }
        }
        return out;
    }

    private static @Nullable JsonArray firstArray(JsonObject root, String... keys) {
        for (String k : keys) {
            if (root.has(k) && root.get(k).isJsonArray()) return root.getAsJsonArray(k);
        }
        return null;
    }

    private static float[] readVec3(JsonObject o, String key, float dx, float dy, float dz) {
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            if (a.size() >= 3) return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        }
        return new float[]{dx, dy, dz};
    }

    private static float[] readVec4(JsonObject o, String key, float dx, float dy, float dz, float dw) {
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            if (a.size() >= 4) return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
            if (a.size() == 3) return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), dw};
        }
        return new float[]{dx, dy, dz, dw};
    }

    private static String readString(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static float readFloat(JsonObject o, String key, float fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsFloat() : fallback;
    }

    private static boolean readBool(JsonObject o, String key, boolean fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
    }
}
