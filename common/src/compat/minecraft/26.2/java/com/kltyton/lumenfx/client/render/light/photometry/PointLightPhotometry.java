package com.kltyton.lumenfx.client.render.light.photometry;

import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.client.render.math.SourceColorMath;
import com.kltyton.lumenfx.client.render.math.SourceColorSpace;
import com.kltyton.lumenfx.client.simulation.niagara.NativeVectorMath;
import org.jspecify.annotations.Nullable;

/** Point-component photometry retains the source's scalar CPU math and IES/unit evaluation order. */
public final class PointLightPhotometry {
    static { NativeVectorMath.initialize(); }
    private PointLightPhotometry() { }

    public enum Unit {
        UNITLESS(0), CANDELAS(1), LUMENS(2), NITS(3), EV(4);
        private final int code;
        Unit(int code) { this.code = code; }
        public static Unit source(String value) {
            int separator = value.lastIndexOf("::");
            String name = separator < 0 ? value : value.substring(separator + 2);
            return switch (name) {
                case "Unitless" -> UNITLESS; case "Candelas" -> CANDELAS; case "Lumens" -> LUMENS;
                case "Nits" -> NITS; case "EV" -> EV;
                default -> throw new IllegalArgumentException("Unknown source light intensity unit " + value);
            };
        }
    }
    public record IesBrightness(float brightness, float textureMultiplier) { }

    public static float brightness(float intensity, Unit unit, boolean inverseSquared, float sourceRadius, float sourceLength,
                                   @Nullable IesBrightness ies, boolean useIesBrightness, float iesBrightnessScale) {
        return evaluateBrightness(intensity, unit.code, inverseSquared, sourceRadius, sourceLength, ies != null,
                ies == null ? 0 : ies.brightness(), ies == null ? 0 : ies.textureMultiplier(), useIesBrightness, iesBrightnessScale);
    }

    public static float brightness(float intensity, Unit unit, boolean inverseSquared, float sourceRadius, float sourceLength,
                                   @Nullable IesBrightness ies, boolean useIesBrightness, float iesBrightnessScale, float cosineOuter) {
        return evaluateLocalBrightness(intensity, unit.code, inverseSquared, sourceRadius, sourceLength, ies != null,
                ies == null ? 0 : ies.brightness(), ies == null ? 0 : ies.textureMultiplier(), useIesBrightness, iesBrightnessScale, cosineOuter);
    }

    public static ComponentValue colored(ComponentValue byteColor, float brightness, boolean useTemperature, float temperature,
                                         SourceColorSpace workingColorSpace) {
        if (byteColor.kind() != ComponentValue.Kind.BYTE_COLOR) throw new IllegalArgumentException("Light component color must be a byte color");
        float red = SourceColorMath.linear((int)byteColor.x()) * brightness;
        float green = SourceColorMath.linear((int)byteColor.y()) * brightness;
        float blue = SourceColorMath.linear((int)byteColor.z()) * brightness;
        float alpha = ((int)byteColor.w() * (1f / 255f)) * brightness;
        if (useTemperature) {
            var color = workingColorSpace.temperature(temperature);
            red *= (float)color.x; green *= (float)color.y; blue *= (float)color.z;
        }
        return ComponentValue.floating(ComponentValue.Kind.LINEAR_COLOR, red, green, blue, alpha);
    }

    public static float rectBrightness(float intensity, Unit unit, float width, float height, boolean legacyEv,
                                       @Nullable IesBrightness ies, boolean useIesBrightness, float iesBrightnessScale) {
        return evaluateRectBrightness(intensity, unit.code, width, height, legacyEv, ies != null,
                ies == null ? 0 : ies.brightness(), ies == null ? 0 : ies.textureMultiplier(), useIesBrightness, iesBrightnessScale);
    }

    private static native float evaluateRectBrightness(float intensity, int unit, float width, float height, boolean legacyEv,
                                                        boolean hasIes, float iesBrightness, float iesMultiplier,
                                                        boolean useIesBrightness, float iesBrightnessScale);
    private static native float evaluateBrightness(float intensity, int unit, boolean inverseSquared, float sourceRadius, float sourceLength,
                                                   boolean hasIes, float iesBrightness, float iesMultiplier, boolean useIesBrightness, float iesBrightnessScale);
    private static native float evaluateLocalBrightness(float intensity, int unit, boolean inverseSquared, float sourceRadius, float sourceLength,
                                                        boolean hasIes, float iesBrightness, float iesMultiplier, boolean useIesBrightness,
                                                        float iesBrightnessScale, float cosineOuter);
}
