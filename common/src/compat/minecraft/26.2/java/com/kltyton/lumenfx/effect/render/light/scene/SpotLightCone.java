package com.kltyton.lumenfx.effect.render.light.scene;

import com.kltyton.lumenfx.math.SourceFloatMath;

/** The source proxy clamps authored degrees before building cone and projection parameters. */
public record SpotLightCone(float cosineOuter, float sineOuter, float inverseCosineDifference, float tangentOuter) {
    public static SpotLightCone fromDegrees(float inner, float outer) {
        float pi = (float)Math.PI;
        float innerRadians = SourceFloatMath.clamp(inner, 0f, 88.9f) * pi / 180f;
        float outerRadians = SourceFloatMath.clamp(outer * pi / 180f, innerRadians + .001f, 88.9f * pi / 180f + .001f);
        float cosineInner = (float)Math.cos(innerRadians);
        float cosineOuter = (float)Math.cos(outerRadians);
        return new SpotLightCone(cosineOuter, (float)Math.sin(outerRadians),
                1f / (cosineInner - cosineOuter), (float)Math.tan(outerRadians));
    }

    /** Unity stores full cone widths and clamps the cosine interval in its realtime light shader. */
    public static SpotLightCone fromUnityAngles(float inner, float outer) {
        double outerRadians = Math.toRadians(Math.clamp(outer, 1f, 179f) * .5);
        double innerRadians = Math.toRadians(Math.clamp(inner, 0f, Math.clamp(outer, 1f, 179f)) * .5);
        float cosineOuter = (float)Math.cos(outerRadians);
        return new SpotLightCone(cosineOuter, (float)Math.sin(outerRadians),
                1f / Math.max(.001f, (float)Math.cos(innerRadians) - cosineOuter), (float)Math.tan(outerRadians));
    }

    public double boundsCenterOffset(float radius) {
        return cosineOuter < .70710678118f ? (double)radius * cosineOuter : radius / (2.0 * cosineOuter);
    }

    public double boundsRadius(float radius) {
        return cosineOuter < .70710678118f ? (double)radius * sineOuter : boundsCenterOffset(radius);
    }
}
