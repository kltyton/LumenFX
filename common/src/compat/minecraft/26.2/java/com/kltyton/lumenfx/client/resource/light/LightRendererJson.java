package com.kltyton.lumenfx.client.resource.light;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.render.light.LightRendererSettings;
import net.minecraft.world.phys.Vec3;

public final class LightRendererJson {
    private LightRendererJson() { }

    public static LightRendererSettings read(JsonObject value) {
        var defaults = LightRendererSettings.DEFAULT;
        Vec3 color = defaults.colorAdd();
        if (value.has("color_add")) {
            var channels = value.getAsJsonArray("color_add");
            if (channels.size() != 3) throw new IllegalArgumentException("Light color addition requires three components");
            color = new Vec3(channels.get(0).getAsDouble(), channels.get(1).getAsDouble(), channels.get(2).getAsDouble());
        }
        return new LightRendererSettings(flag(value, "inverse_squared", defaults.inverseSquaredFalloff()),
                flag(value, "affects_translucency", defaults.affectsTranslucency()), flag(value, "allow_megalights", defaults.allowMegaLights()),
                flag(value, "megalights_cast_shadows", defaults.megaLightsCastShadows()), flag(value, "alpha_scales_brightness", defaults.alphaScalesBrightness()),
                number(value, "radius_scale", defaults.radiusScale()), number(value, "default_exponent", defaults.defaultExponent()),
                number(value, "specular_scale", defaults.specularScale()), number(value, "diffuse_scale", defaults.diffuseScale()), color,
                flag(value, "override_inverse_exposure_blend", defaults.overrideInverseExposureBlend()),
                number(value, "inverse_exposure_blend", defaults.inverseExposureBlend()),
                number(value, "inverse_exposure_default", defaults.defaultInverseExposureBlend()));
    }

    private static boolean flag(JsonObject value, String name, boolean fallback) { return value.has(name) ? value.get(name).getAsBoolean() : fallback; }
    private static float number(JsonObject value, String name, float fallback) { return value.has(name) ? value.get(name).getAsFloat() : fallback; }
}
