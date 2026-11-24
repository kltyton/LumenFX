package com.kltyton.lumenfx.client.render.math;

import com.kltyton.lumenfx.math.SourceFloatMath;

/** Scalar renderer operations from the fixed Windows reference profile. */
public final class UnrealRendererMath {
    private UnrealRendererMath() { }

    public static float min(float a, float b) { return SourceFloatMath.min(a, b); }
    public static float max(float a, float b) { return SourceFloatMath.max(a, b); }
    public static float clamp(float value, float minimum, float maximum) { return SourceFloatMath.clamp(value, minimum, maximum); }
    public static int truncToInt(float value) { return SourceFloatMath.truncToInt(value); }
    public static int roundToInt(float value) { return SourceFloatMath.roundToInt(value); }

    public static float acosFast(float value) {
        float x = Math.abs(value);
        float result = (-.156583f * x + .5f * (float)Math.PI) * (float)Math.sqrt(Math.max(0f, 1f - x));
        return value >= 0 ? result : (float)Math.PI - result;
    }
}
