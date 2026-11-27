package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/** Material definition matching LumenFX 26.2 {@code EffectJson} materials map plus native OpenGL shader/HDR/soft-particle extensions. */
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
    private final @Nullable String customGlslFragment;
    private final @Nullable String customGlslVertex;
    private final boolean softParticles;
    private final float softFadeDistance;
    private final float emissiveScale;
    private final boolean refraction;
    private final float bloomIntensity;
    private final int subUvRows;
    private final int subUvCols;
    private final boolean nanite;
    private final boolean rvt;

    public MaterialModel(String name, String fragmentShader, @Nullable String vertexMaterial,
                         Map<String, String> textures, BlendMode blend,
                         boolean depthTest, boolean depthWrite, boolean cull,
                         float[] parameters, boolean hdr, String sourceObject) {
        this(name, fragmentShader, vertexMaterial, textures, blend, depthTest, depthWrite, cull,
                parameters, hdr, sourceObject, null, null, false, 0.75f,
                hdr ? 1.5f : 1.0f, false, hdr ? 1.0f : 0.0f, 1, 1, false, false);
    }

    public MaterialModel(String name, String fragmentShader, @Nullable String vertexMaterial,
                         Map<String, String> textures, BlendMode blend,
                         boolean depthTest, boolean depthWrite, boolean cull,
                         float[] parameters, boolean hdr, String sourceObject,
                         @Nullable String customGlslFragment, @Nullable String customGlslVertex,
                         boolean softParticles, float softFadeDistance, float emissiveScale,
                         boolean refraction, float bloomIntensity,
                         int subUvRows, int subUvCols, boolean nanite, boolean rvt) {
        this.name = name;
        this.fragmentShader = fragmentShader;
        this.vertexMaterial = vertexMaterial;
        this.textures = Collections.unmodifiableMap(new LinkedHashMap<>(textures));
        this.blend = blend;
        this.depthTest = depthTest;
        this.depthWrite = depthWrite;
        this.cull = cull;
        this.parameters = parameters.clone();
        this.hdr = hdr || bloomIntensity > 0f || emissiveScale > 1.01f;
        this.sourceObject = sourceObject;
        this.customGlslFragment = customGlslFragment;
        this.customGlslVertex = customGlslVertex;
        this.softParticles = softParticles;
        this.softFadeDistance = Math.max(0.01f, softFadeDistance);
        this.emissiveScale = Math.max(0.01f, emissiveScale);
        this.refraction = refraction;
        this.bloomIntensity = Math.max(0f, bloomIntensity);
        this.subUvRows = Math.max(1, subUvRows);
        this.subUvCols = Math.max(1, subUvCols);
        this.nanite = nanite;
        this.rvt = rvt;
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
    public @Nullable String customGlslFragment() { return customGlslFragment; }
    public @Nullable String customGlslVertex() { return customGlslVertex; }
    public boolean softParticles() { return softParticles; }
    public float softFadeDistance() { return softFadeDistance; }
    public float emissiveScale() { return emissiveScale; }
    public boolean refraction() { return refraction; }
    public float bloomIntensity() { return bloomIntensity; }
    public int subUvRows() { return subUvRows; }
    public int subUvCols() { return subUvCols; }
    public boolean nanite() { return nanite; }
    public boolean rvt() { return rvt; }

    public @Nullable String primaryTexture() {
        String s0 = textures.get("Sampler0");
        if (s0 != null && !s0.isBlank()) return s0;
        for (String v : textures.values()) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    public @Nullable String secondaryTexture() {
        String s1 = textures.get("Sampler1");
        if (s1 != null && !s1.isBlank()) return s1;
        String primary = primaryTexture();
        for (String v : textures.values()) {
            if (v != null && !v.isBlank() && !v.equals(primary)) return v;
        }
        return null;
    }

    public boolean hasCustomShader() {
        if (customGlslFragment != null && !customGlslFragment.isBlank()) return true;
        if (customGlslVertex != null && !customGlslVertex.isBlank()) return true;
        if (vertexMaterial != null && !vertexMaterial.isBlank()) return true;
        return fragmentShader != null && !fragmentShader.isBlank()
                && !fragmentShader.equals("lumenfx:core/color")
                && !fragmentShader.equals("lumenfx:core/effect");
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
        String vert = readNullableString(value, "vertex_material");
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

        JsonObject render = value.has("render") && value.get("render").isJsonObject()
                ? value.getAsJsonObject("render") : null;
        boolean hdr = readBool(value, "hdr", render != null && readBool(render, "hdr", false));
        float emissiveScale = readFloat(value, "emissive_scale",
                readFloat(value, "emissive",
                        render != null ? readFloat(render, "emissive_scale", hdr ? 1.5f : 1.0f) : (hdr ? 1.5f : 1.0f)));
        float bloomIntensity = readFloat(value, "bloom_intensity",
                readFloat(value, "bloom",
                        render != null ? readFloat(render, "bloom", hdr ? 1.0f : 0.0f) : (hdr ? 1.0f : 0.0f)));
        if (bloomIntensity > 0f || emissiveScale > 1.01f) hdr = true;

        boolean softParticles = readBool(value, "soft_particles",
                readBool(value, "depth_fade",
                        render != null && readBool(render, "soft_particles", false)));
        float softFadeDist = readFloat(value, "soft_fade_distance",
                readFloat(value, "depth_fade_distance",
                        render != null ? readFloat(render, "soft_fade_distance", 0.75f) : 0.75f));
        if (value.has("depth_fade_distance") || value.has("soft_fade_distance")) {
            softParticles = true;
        }
        boolean refraction = readBool(value, "refraction",
                render != null && readBool(render, "refraction", false));
        boolean nanite = readBool(value, "nanite",
                render != null && readBool(render, "nanite", false));
        boolean rvt = readBool(value, "rvt", readBool(value, "virtual_texture",
                render != null && readBool(render, "rvt", false)));

        String customFrag = readNullableString(value, "custom_glsl");
        if (customFrag == null) customFrag = readNullableString(value, "fragment_glsl");
        String customVert = readNullableString(value, "vertex_glsl");

        int subRows = 1;
        int subCols = 1;
        if (value.has("sub_uv")) {
            JsonElement su = value.get("sub_uv");
            if (su.isJsonArray() && su.getAsJsonArray().size() >= 2) {
                subRows = Math.max(1, su.getAsJsonArray().get(0).getAsInt());
                subCols = Math.max(1, su.getAsJsonArray().get(1).getAsInt());
            } else if (su.isJsonObject()) {
                JsonObject suo = su.getAsJsonObject();
                subRows = Math.max(1, (int) readFloat(suo, "rows", 1f));
                subCols = Math.max(1, (int) readFloat(suo, "cols", readFloat(suo, "columns", 1f)));
            }
        }

        String sourceObj = readString(value, "source_object", "");
        return new MaterialModel(name, frag, vert, textures, blend, depthTest, depthWrite, cull,
                params, hdr, sourceObj, customFrag, customVert, softParticles, softFadeDist,
                emissiveScale, refraction, bloomIntensity, subRows, subCols, nanite, rvt);
    }

    private static String readString(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static @Nullable String readNullableString(JsonObject o, String key) {
        if (o.has(key) && o.get(key).isJsonPrimitive()) {
            String s = o.get(key).getAsString();
            return s.isBlank() ? null : s;
        }
        return null;
    }

    private static boolean readBool(JsonObject o, String key, boolean fallback) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
        try {
            if (o.get(key).getAsJsonPrimitive().isNumber()) return o.get(key).getAsFloat() > 0f;
            return o.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static float readFloat(JsonObject o, String key, float fallback) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
        try {
            if (o.get(key).getAsJsonPrimitive().isBoolean()) return o.get(key).getAsBoolean() ? 1.0f : 0.0f;
            return o.get(key).getAsFloat();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
