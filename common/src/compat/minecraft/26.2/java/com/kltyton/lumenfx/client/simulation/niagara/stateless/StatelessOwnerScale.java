package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.math.SourceFloatMath;

/** The source shared scale settings prepare CPU and GPU module parameters with the same signed component scale. */
public final class StatelessOwnerScale {
    private record Setting(boolean enabled, boolean singleAxis, int axis, float amount) {
        float factor(float x, float y, float z) {
            if (!enabled) return 1f;
            float value = singleAxis ? switch (axis) { case 0 -> x; case 1 -> y; default -> z; }
                    : SourceFloatMath.max(SourceFloatMath.max(x, y), z);
            return 1f + (value - 1f) * amount;
        }
    }
    private final JsonObject definition;
    private final Setting sprite, ribbon, camera, light;

    public StatelessOwnerScale(JsonObject metadata, boolean enabled) {
        JsonObject scale = new JsonObject();
        if (enabled) for (var item : metadata.getAsJsonArray("modules")) {
            JsonObject module = item.getAsJsonObject();
            if (!module.get("class").getAsString().equals("NiagaraStatelessModule_ApplyOwnerScaleToAttributes")
                    || !module.get("enabled").getAsBoolean()) continue;
            JsonObject properties = module.getAsJsonObject("properties");
            if (properties.has("SystemScaleData")) scale = properties.getAsJsonObject("SystemScaleData");
        }
        definition = scale.deepCopy();
        sprite = setting(scale, "bScaleInitialSpriteSize", "bScaleInitialSpriteSizeBySingleAxis", "ScaleInitialSpriteSizeAxis", "ScaleInitialSpriteSizeScaleAmount");
        ribbon = setting(scale, "bScaleInitialRibbonWidth", "bScaleInitialRibbonWidthBySingleAxis", "ScaleInitialRibbonWidthScalingAxis", "ScaleInitialRibbonWidthScaleAmount");
        camera = setting(scale, "bScaleCameraOffset", "", "", "ScaleCameraOffsetAmount");
        light = setting(scale, "bScaleInitialLightRadius", "bScaleInitialLightRadiusBySingleAxis", "ScaleInitialLightRadiusScalingAxis", "ScaleInitialLightRadiusScaleAmount");
    }
    public JsonObject definition() { return definition.deepCopy(); }
    public boolean spriteEnabled() { return sprite.enabled(); }
    public boolean ribbonEnabled() { return ribbon.enabled(); }
    public float sprite(float x, float y, float z) { return sprite.factor(x, y, z); }
    public float ribbon(float x, float y, float z) { return ribbon.factor(x, y, z); }
    public float camera(float x, float y, float z) { return camera.factor(x, y, z); }
    public float light(float x, float y, float z) { return light.factor(x, y, z); }

    private static Setting setting(JsonObject source, String enabled, String byAxis, String axis, String amount) {
        int axisIndex = source.has(axis) ? switch (source.get(axis).getAsString().replace("ENiagaraOrientationAxis::", "")) {
            case "XAxis", "0" -> 0; case "YAxis", "1" -> 1; case "ZAxis", "2" -> 2;
            default -> throw new IllegalArgumentException("Invalid stateless owner-scale axis");
        } : 0;
        return new Setting(source.has(enabled) && source.get(enabled).getAsBoolean(), source.has(byAxis) && source.get(byAxis).getAsBoolean(),
                axisIndex, source.has(amount) ? source.get(amount).getAsFloat() : 1f);
    }
}
