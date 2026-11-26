package com.kltyton.lumenfx.api.client.keyframe;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Parsed options extracted from a Bedrock / GeckoLib particle keyframe's {@code pre_effect_script}
 * and {@code bind_to_actor} fields.
 *
 * <p>Supported key=value directives (separated by {@code ;}, {@code ,}, or whitespace):
 * <ul>
 *   <li>{@code follow=true|false} — follow the target locator/actor every frame (defaults to {@code bindToActor}, or {@code true} when a locator is specified)</li>
 *   <li>{@code rotate=true|false} — inherit locator/bone 3D rotation (default {@code true})</li>
 *   <li>{@code scale=<float>} — uniform scale multiplier (default {@code 1.0})</li>
 *   <li>{@code color=#RRGGBB} or {@code color=#RRGGBBAA} or {@code color=r,g,b[,a]} — RGBA tint multiplier</li>
 *   <li>{@code offset=x,y,z} — world/local offset in blocks</li>
 *   <li>{@code beam_end=<locator>} — second locator driving {@code setBeamEnd}</li>
 *   <li>{@code stop=true} — stop and smoothly drain matching active effects on this actor/controller/locator</li>
 *   <li>{@code restart=true} — close any existing instance in the same slot before spawning</li>
 *   <li>{@code seed=<long>} — explicit deterministic seed override</li>
 * </ul>
 */
public final class KeyframeEffectParams {
    public static final KeyframeEffectParams DEFAULT_UNBOUND =
            new KeyframeEffectParams(false, true, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, false, 0.0, 0.0, 0.0, null, false, false, null, "");

    private final boolean follow;
    private final boolean rotate;
    private final float scale;
    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;
    private final boolean hasColor;
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final @Nullable String beamEndLocator;
    private final boolean stop;
    private final boolean restart;
    private final @Nullable Long seed;
    private final String rawScript;

    public KeyframeEffectParams(boolean follow, boolean rotate, float scale,
                                float red, float green, float blue, float alpha, boolean hasColor,
                                double offsetX, double offsetY, double offsetZ,
                                @Nullable String beamEndLocator, boolean stop, boolean restart,
                                @Nullable Long seed, String rawScript) {
        this.follow = follow;
        this.rotate = rotate;
        this.scale = Float.isFinite(scale) && scale > 0.0F ? scale : 1.0F;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
        this.hasColor = hasColor;
        this.offsetX = Double.isFinite(offsetX) ? offsetX : 0.0;
        this.offsetY = Double.isFinite(offsetY) ? offsetY : 0.0;
        this.offsetZ = Double.isFinite(offsetZ) ? offsetZ : 0.0;
        this.beamEndLocator = beamEndLocator != null && !beamEndLocator.isBlank() ? beamEndLocator.trim() : null;
        this.stop = stop;
        this.restart = restart;
        this.seed = seed;
        this.rawScript = rawScript == null ? "" : rawScript;
    }

    public boolean follow() { return follow; }
    public boolean rotate() { return rotate; }
    public float scale() { return scale; }
    public float red() { return red; }
    public float green() { return green; }
    public float blue() { return blue; }
    public float alpha() { return alpha; }
    public boolean hasColor() { return hasColor; }
    public double offsetX() { return offsetX; }
    public double offsetY() { return offsetY; }
    public double offsetZ() { return offsetZ; }
    public @Nullable String beamEndLocator() { return beamEndLocator; }
    public boolean stop() { return stop; }
    public boolean restart() { return restart; }
    public @Nullable Long seed() { return seed; }
    public String rawScript() { return rawScript; }

    public static KeyframeEffectParams parse(@Nullable String script, @Nullable Boolean bindToActor, boolean hasLocator) {
        boolean follow = bindToActor != null ? bindToActor : hasLocator;
        boolean rotate = true;
        float scale = 1.0F;
        float r = 1.0F, g = 1.0F, b = 1.0F, a = 1.0F;
        boolean hasColor = false;
        double ox = 0.0, oy = 0.0, oz = 0.0;
        String beamEnd = null;
        boolean stop = false;
        boolean restart = false;
        Long seed = null;

        if (script == null || script.isBlank()) {
            return new KeyframeEffectParams(follow, rotate, scale, r, g, b, a, false, ox, oy, oz, null, false, false, null, "");
        }

        // Split on semicolons or newlines first so comma-separated vectors (offset=1,2,3) stay intact.
        String[] statements = script.split("[;\r\n]+");
        for (String stmt : statements) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                String flag = trimmed.toLowerCase(Locale.ROOT);
                if ("stop".equals(flag)) stop = true;
                else if ("restart".equals(flag)) restart = true;
                else if ("follow".equals(flag)) follow = true;
                else if ("detach".equals(flag) || "unfollow".equals(flag)) follow = false;
                continue;
            }
            String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            // Strip optional Molang variable prefixes like v.lumenfx_scale or variable.scale
            if (key.startsWith("v.")) key = key.substring(2);
            else if (key.startsWith("variable.")) key = key.substring(9);
            if (key.startsWith("lumenfx_")) key = key.substring(8);
            else if (key.startsWith("lumenfx.")) key = key.substring(8);

            String val = trimmed.substring(eq + 1).trim();
            if (val.length() >= 2 && ((val.startsWith("'") && val.endsWith("'")) || (val.startsWith("\"") && val.endsWith("\"")))) {
                val = val.substring(1, val.length() - 1).trim();
            }
            switch (key) {
                case "follow", "bind", "bind_to_actor", "attach" -> follow = parseBool(val, follow);
                case "rotate", "inherit_rotation", "rot" -> rotate = parseBool(val, rotate);
                case "scale", "size" -> {
                    float parsed = parseFloat(val, scale);
                    if (Float.isFinite(parsed) && parsed > 0.0F) scale = parsed;
                }
                case "color", "tint" -> {
                    float[] rgba = parseColor(val);
                    if (rgba != null) {
                        r = rgba[0]; g = rgba[1]; b = rgba[2]; a = rgba[3];
                        hasColor = true;
                    }
                }
                case "offset", "pos" -> {
                    double[] vec = parseVec3(val);
                    if (vec != null) {
                        ox = vec[0]; oy = vec[1]; oz = vec[2];
                    }
                }
                case "beam_end", "beam_target", "target_locator" -> {
                    if (!val.isBlank()) beamEnd = val.trim();
                }
                case "stop", "close", "end" -> stop = parseBool(val, true);
                case "restart", "respawn" -> restart = parseBool(val, true);
                case "seed" -> {
                    try {
                        seed = Long.parseLong(val);
                    } catch (NumberFormatException ignored) { }
                }
                default -> { }
            }
        }
        return new KeyframeEffectParams(follow, rotate, scale, r, g, b, a, hasColor, ox, oy, oz, beamEnd, stop, restart, seed, script);
    }

    private static boolean parseBool(String value, boolean fallback) {
        String lower = value.toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "1".equals(lower) || "yes".equals(lower) || "on".equals(lower)) return true;
        if ("false".equals(lower) || "0".equals(lower) || "no".equals(lower) || "off".equals(lower)) return false;
        return fallback;
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static @Nullable double[] parseVec3(String value) {
        String[] parts = value.split("[,\\s]+");
        if (parts.length < 3) return null;
        try {
            return new double[] {
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2])
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static @Nullable float[] parseColor(String value) {
        if (value.startsWith("#")) {
            String hex = value.substring(1);
            try {
                if (hex.length() == 6) {
                    int rgb = Integer.parseInt(hex, 16);
                    return new float[] {
                        ((rgb >>> 16) & 0xFF) / 255.0F,
                        ((rgb >>> 8) & 0xFF) / 255.0F,
                        (rgb & 0xFF) / 255.0F,
                        1.0F
                    };
                }
                if (hex.length() == 8) {
                    long rgba = Long.parseLong(hex, 16);
                    return new float[] {
                        ((rgba >>> 24) & 0xFF) / 255.0F,
                        ((rgba >>> 16) & 0xFF) / 255.0F,
                        ((rgba >>> 8) & 0xFF) / 255.0F,
                        (rgba & 0xFF) / 255.0F
                    };
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
            return null;
        }
        String[] parts = value.split("[,\\s]+");
        if (parts.length < 3) return null;
        try {
            float r = Float.parseFloat(parts[0]);
            float g = Float.parseFloat(parts[1]);
            float b = Float.parseFloat(parts[2]);
            float a = parts.length >= 4 ? Float.parseFloat(parts[3]) : 1.0F;
            if (r > 1.0F || g > 1.0F || b > 1.0F || a > 1.0F) {
                r /= 255.0F; g /= 255.0F; b /= 255.0F;
                if (parts.length >= 4 && a > 1.0F) a /= 255.0F;
            }
            return new float[] { r, g, b, a };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
