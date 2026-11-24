package com.kltyton.lumenfx.math;

/** Scalar operations preserve the fixed Windows source's operand order and integer conversion rules. */
public final class SourceFloatMath {
    private SourceFloatMath() { }
    public static float min(float a, float b) { return a < b ? a : b; }
    public static float max(float a, float b) { return b < a ? a : b; }
    public static float clamp(float value, float minimum, float maximum) {
        return value < minimum ? minimum : value < maximum ? value : maximum;
    }
    public static int truncToInt(float value) {
        return !Float.isFinite(value) || value >= 0x1.0p31f || value < -0x1.0p31f ? Integer.MIN_VALUE : (int)value;
    }
    public static int roundToInt(float value) { return nearestToInt(value + value + .5f) >> 1; }
    public static int floorToInt(float value) { return nearestToInt(value + value - .5f) >> 1; }
    public static float fractional(float value) { return value - (float)(value < 0 ? Math.ceil(value) : Math.floor(value)); }
    private static int nearestToInt(float value) {
        return !Float.isFinite(value) || value >= 0x1.0p31f || value < -0x1.0p31f
                ? Integer.MIN_VALUE : (int)Math.rint(value);
    }
}
