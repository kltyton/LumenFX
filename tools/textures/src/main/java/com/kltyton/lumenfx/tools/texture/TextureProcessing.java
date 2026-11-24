package com.kltyton.lumenfx.tools.texture;

import com.google.gson.JsonObject;
import java.util.List;

/** Source image adjustments, alpha prediction and channel postprocessing around mip generation. */
final class TextureProcessing {
    static final TextureProcessing NONE = new TextureProcessing(new JsonObject(), false, null);
    private static final float TOLERANCE = .0001f;
    private final float brightness, brightnessCurve, vibrance, saturation, rgbCurve, hue, minAlpha, maxAlpha;
    private final float chromaThreshold;
    private final float[] chroma = new float[4];
    private final boolean forceNoAlpha, forceAlpha, chromaKey, hdr, bokeh, flipGreen, replicateRed, replicateAlpha, colorSpace, modern, adjust;

    TextureProcessing(JsonObject json, boolean modern, SourceColorTables tables) {
        this.modern = modern;
        brightness = number(json, "AdjustBrightness", 1); brightnessCurve = number(json, "AdjustBrightnessCurve", 1);
        vibrance = number(json, "AdjustVibrance", 0); saturation = number(json, "AdjustSaturation", 1);
        rgbCurve = number(json, "AdjustRGBCurve", 1); hue = number(json, "AdjustHue", 0);
        minAlpha = number(json, "AdjustMinAlpha", 0); maxAlpha = number(json, "AdjustMaxAlpha", 1);
        chromaThreshold = number(json, "chroma_threshold", 1f / 255f) + .00000001f;
        forceNoAlpha = flag(json, "force_no_alpha"); forceAlpha = flag(json, "force_alpha");
        chromaKey = flag(json, "chroma_key"); hdr = flag(json, "hdr_source"); bokeh = flag(json, "bokeh_alpha");
        flipGreen = flag(json, "flip_green"); replicateRed = flag(json, "replicate_red"); replicateAlpha = flag(json, "replicate_alpha");
        colorSpace = flag(json, "has_color_space");
        if (replicateRed && replicateAlpha) throw new IllegalArgumentException("Source red and alpha replication are mutually exclusive");
        if (chromaKey) {
            var color = json.getAsJsonArray("chroma_color");
            if (color == null || color.size() != 4 || tables == null) throw new IllegalArgumentException("Chroma key requires its source FColor");
            float[] decode = tables.decodeTable(false);
            for (int channel = 0; channel < 4; channel++) {
                int value = color.get(channel).getAsBigDecimal().intValueExact();
                if (value < 0 || value > 255) throw new IllegalArgumentException("Source chroma FColor channel is outside 0..255");
                chroma[channel] = channel < 3 ? decode[value] : value * (1f / 255f);
            }
        }
        adjust = different(brightness, 1) || different(brightnessCurve, 1) || different(vibrance, 0) || different(saturation, 1)
                || different(rgbCurve, 1) || different(hue, 0) || different(minAlpha, 0) || different(maxAlpha, 1) || chromaKey;
    }

    Boolean predictAlpha(float[] pixels) {
        if (forceNoAlpha) return false;
        if (forceAlpha || chromaKey) return true;
        if (colorSpace && replicateRed) return null;
        if (bokeh) return true;
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int offset = 0; offset < pixels.length; offset += 4) for (int channel = 0; channel < 4; channel++) {
            bounds[channel] = Math.min(bounds[channel], pixels[offset + channel]);
            bounds[channel + 4] = Math.max(bounds[channel + 4], pixels[offset + channel]);
        }
        if (adjust) { adjust(bounds, 0); adjust(bounds, 4); }
        int alpha = replicateRed ? 0 : 3;
        return Math.min(bounds[alpha], bounds[alpha + 4]) <= 254.5f / 255f;
    }

    void preprocess(float[] pixels) {
        if (adjust) for (int offset = 0; offset < pixels.length; offset += 4) adjust(pixels, offset);
        if (bokeh) {
            float r = 0, g = 0, b = 0;
            int count = pixels.length / 4;
            for (int i = 0; i < pixels.length; i += 4) { r += pixels[i]; g += pixels[i + 1]; b += pixels[i + 2]; }
            float luminance = ((r / count) + (g / count) + (b / count)) * (1f / 3f);
            float scale = .25f / Math.max(luminance, .001f);
            for (int i = 0; i < pixels.length; i += 4) {
                for (int channel = 0; channel < 3; channel++) pixels[i + channel] *= scale;
                pixels[i + 3] = clamp((pixels[i] + pixels[i + 1] + pixels[i + 2]) * (1f / 3f), 0, 1);
            }
        }
        if (flipGreen) for (int i = 1; i < pixels.length; i += 4) pixels[i] = 1 - clamp(pixels[i], 0, 1);
    }

    void postprocess(List<TextureMipGenerator.Level> levels) {
        if (!replicateRed && !replicateAlpha) return;
        int source = replicateRed ? 0 : 3;
        for (var level : levels) for (int i = 0; i < level.rgba().length; i += 4) {
            float value = level.rgba()[i + source];
            for (int channel = 0; channel < 4; channel++) level.rgba()[i + channel] = value;
        }
    }

    private void adjust(float[] pixels, int offset) {
        float r = pixels[offset], g = pixels[offset + 1], b = pixels[offset + 2], a = pixels[offset + 3];
        if (chromaKey && Math.abs(r - chroma[0]) <= chromaThreshold && Math.abs(g - chroma[1]) <= chromaThreshold
                && Math.abs(b - chroma[2]) <= chromaThreshold && Math.abs(a - chroma[3]) <= chromaThreshold) {
            pixels[offset] = pixels[offset + 1] = pixels[offset + 2] = pixels[offset + 3] = 0;
            if (modern) return;
            r = g = b = a = 0;
        }
        if (modern) {
            r = hdr ? Math.max(r, 0) : clamp(r, 0, 1); g = hdr ? Math.max(g, 0) : clamp(g, 0, 1); b = hdr ? Math.max(b, 0) : clamp(b, 0, 1);
        }
        float minimum = Math.min(r, Math.min(g, b)), maximum = Math.max(r, Math.max(g, b));
        float range = maximum - minimum;
        float h = maximum <= 0 || maximum == minimum ? 0 : maximum == r ? (((g - b) / range) * 60 + 360) % 360
                : maximum == g ? ((b - r) / range) * 60 + 120 : ((r - g) / range) * 60 + 240;
        float s = maximum <= 0 ? 0 : range / maximum, v = maximum <= 0 ? 0 : maximum;
        float originalLuminance = v;
        boolean valueCurve = different(brightnessCurve, 1) && brightnessCurve != 0;
        if (brightness != 1 || valueCurve) {
            v *= brightness;
            if (valueCurve) v = (float)Math.pow(v, brightnessCurve);
            if (!hdr) v = clamp(v, 0, 1);
        }
        boolean vibranceActive = different(vibrance, 0);
        if (saturation != 1 || vibranceActive) {
            if (vibranceActive) {
                float inverse = 1 - s;
                float raised = modern ? inverse * inverse * inverse * inverse * inverse : (float)Math.pow(inverse, 5);
                s += clamp(vibrance, 0, 1) * .5f * raised;
            }
            s *= saturation;
            if (!modern) s = clamp(s, 0, 1);
        }
        float hueOffset = hue;
        if (hueOffset < 0 || hueOffset > 360) { hueOffset %= 360; if (hueOffset < 0) hueOffset += 360; }
        h += hueOffset;
        if (!modern && h >= 360) h -= 360;
        h %= 360; if (h < 0) h += 360;
        s = clamp(s, 0, 1);
        float sector = h / 60, fraction = sector - (int)sector;
        float p = v * (1 - s), q = v * (1 - fraction * s), t = v * (1 - (1 - fraction) * s);
        switch ((int)sector % 6) {
            case 0 -> { r = v; g = t; b = p; } case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; } case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; } case 5 -> { r = v; g = p; b = q; }
        }
        if (different(rgbCurve, 1) && rgbCurve != 0) { r = (float)Math.pow(r, rgbCurve); g = (float)Math.pow(g, rgbCurve); b = (float)Math.pow(b, rgbCurve); }
        if (!modern && hdr) { float limit = originalLuminance > 1 ? originalLuminance : 1; r = clamp(r, 0, limit); g = clamp(g, 0, limit); b = clamp(b, 0, limit); }
        pixels[offset] = r; pixels[offset + 1] = g; pixels[offset + 2] = b;
        float alpha = modern ? clamp(a, 0, 1) : a;
        pixels[offset + 3] = minAlpha + alpha * (maxAlpha - minAlpha);
    }

    private static boolean different(float value, float expected) { return Math.abs(value - expected) > TOLERANCE; }
    private static float clamp(float value, float minimum, float maximum) { return value < minimum ? minimum : value < maximum ? value : maximum; }
    private static float number(JsonObject json, String name, float fallback) {
        float value = json.has(name) ? json.get(name).getAsFloat() : fallback;
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite texture processing field " + name);
        return value;
    }
    private static boolean flag(JsonObject json, String name) { return json.has(name) && json.get(name).getAsBoolean(); }
}
