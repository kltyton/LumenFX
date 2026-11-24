package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/** Material definition matching LumenFX 26.2 {@code EffectJson} materials map. */
public final class MaterialModel {
    private final String name;
    private final String fragmentShader;
    private final @Nullable String vertexMaterial;
    private final Map<String, String> textures;
    private final BlendMode blend;
    private final boolean depthTest;
    private final boolean depthWrite;
    private final boolean cull;
    private final float[] parameters;
    private final boolean hdr;
    private final String sourceObject;

    public MaterialModel(String name, String fragmentShader, @Nullable String vertexMaterial,
                         Map<String, String> textures, BlendMode blend,
                         boolean depthTest, boolean depthWrite, boolean cull,
                         float[] parameters, boolean hdr, String sourceObject) {
        this.name = name;
        this.fragmentShader = fragmentShader;
        this.vertexMaterial = vertexMaterial;
        this.textures = Collections.unmodifiableMap(new LinkedHashMap<>(textures));
        this.blend = blend;
        this.depthTest = depthTest;
        this.depthWrite = depthWrite;
        this.cull = cull;
        this.parameters = parameters.clone();
        this.hdr = hdr;
        this.sourceObject = sourceObject;
    }

    public String name() { return name; }
    public String fragmentShader() { return fragmentShader; }
    public @Nullable String vertexMaterial() { return vertexMaterial; }
    public Map<String, String> textures() { return textures; }
    public BlendMode blend() { return blend; }
    public boolean depthTest() { return depthTest; }
    public boolean depthWrite() { return depthWrite; }
    public boolean cull() { return cull; }
    public float[] parameters() { return parameters.clone(); }
    public boolean hdr() { return hdr; }
    public String sourceObject() { return sourceObject; }

    public @Nullable String primaryTexture() {
        String s0 = textures.get("Sampler0");
        if (s0 != null && !s0.isBlank()) return s0;
        for (String v : textures.values()) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    public static MaterialModel defaultMaterial(String name) {
        return new MaterialModel(name, "lumenfx:core/color", null, Map.of(), BlendMode.ALPHA,
                true, false, false, new float[4], false, "");
    }

    public static MaterialModel fromJson(String name, JsonObject value) {
        Map<String, String> textures = new LinkedHashMap<>();
        if (value.has("textures") && value.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject("textures").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    textures.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        String frag = readString(value, "fragment_shader", textures.isEmpty() ? "lumenfx:core/color" : "lumenfx:core/effect");
        String vert = value.has("vertex_material") && value.get("vertex_material").isJsonPrimitive()
                ? value.get("vertex_material").getAsString() : null;
        BlendMode blend = BlendMode.parse(readString(value, "blend", readString(value, "blend_mode", "alpha")));
        String blendOp = readString(value, "blend_op", "add").toLowerCase();
        if (blendOp.contains("reverse_sub")) blend = BlendMode.REVERSE_SUBTRACT;
        else if (blendOp.equals("subtract")) blend = BlendMode.SUBTRACT;

        boolean depthTest = readBool(value, "depth_test", true);
        boolean depthWrite = readBool(value, "depth_write", depthTest && blend == BlendMode.OPAQUE);
        boolean cull = readBool(value, "cull", false);
        float[] params = new float[4];
        if (value.has("parameters") && value.get("parameters").isJsonArray()) {
            JsonArray arr = value.getAsJsonArray("parameters");
            for (int i = 0; i < Math.min(4, arr.size()); i++) params[i] = arr.get(i).getAsFloat();
        }
        boolean hdr = false;
        if (value.has("render") && value.get("render").isJsonObject()) {
            hdr = readBool(value.getAsJsonObject("render"), "hdr", false);
        }
        String sourceObj = readString(value, "source_object", "");
        return new MaterialModel(name, frag, vert, textures, blend, depthTest, depthWrite, cull, params, hdr, sourceObj);
    }

    private static String readString(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static boolean readBool(JsonObject o, String key, boolean fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
    }
}
