package com.kltyton.lumenfx.client.resource.splat;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.render.splat.SplatRendererSettings;

public final class SplatRendererJson {
    private SplatRendererJson() { }

    public static SplatRendererSettings read(JsonObject source) {
        float splatScale = source.has("splat_scale") ? source.get("splat_scale").getAsFloat() : 1.0f;
        float opacityScale = source.has("opacity_scale") ? source.get("opacity_scale").getAsFloat() : 1.0f;
        boolean invertToneCurve = source.has("invert_tone_curve") && source.get("invert_tone_curve").getAsBoolean();
        float inverseToneCurveStrength = source.has("inverse_tone_curve_strength")
                ? source.get("inverse_tone_curve_strength").getAsFloat() : 1.0f;
        boolean invertExpandGamut = !source.has("invert_expand_gamut") || source.get("invert_expand_gamut").getAsBoolean();
        int rendererVisibility = source.has("renderer_visibility") ? source.get("renderer_visibility").getAsInt() : 0;
        return new SplatRendererSettings(splatScale, opacityScale, invertToneCurve,
                inverseToneCurveStrength, invertExpandGamut, rendererVisibility);
    }
}
