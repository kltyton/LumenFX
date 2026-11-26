package com.kltyton.lumenfx.internal.photon.core.utils;

import org.joml.Vector4f;

import java.util.concurrent.ThreadLocalRandom;

public final class ColorUtils {
    private ColorUtils() { }

    public static float alpha(int c) { return ((c >>> 24) & 0xFF) / 255f; }
    public static float red(int c) { return ((c >>> 16) & 0xFF) / 255f; }
    public static float green(int c) { return ((c >>> 8) & 0xFF) / 255f; }
    public static float blue(int c) { return (c & 0xFF) / 255f; }

    public static int alphaI(int c) { return (c >>> 24) & 0xFF; }
    public static int redI(int c) { return (c >>> 16) & 0xFF; }
    public static int greenI(int c) { return (c >>> 8) & 0xFF; }
    public static int blueI(int c) { return c & 0xFF; }

    public static int color(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int color(float a, float r, float g, float b) {
        return color(
                Math.max(0, Math.min(255, Math.round(a * 255f))),
                Math.max(0, Math.min(255, Math.round(r * 255f))),
                Math.max(0, Math.min(255, Math.round(g * 255f))),
                Math.max(0, Math.min(255, Math.round(b * 255f))));
    }

    public static int color(double a, double r, double g, double b) {
        return color((float) a, (float) r, (float) g, (float) b);
    }

    public static int blendRGBColor(int c1, int c2, float h) {
        float t = Math.max(0f, Math.min(1f, h));
        float a = alpha(c1) + (alpha(c2) - alpha(c1)) * t;
        float r = red(c1) + (red(c2) - red(c1)) * t;
        float g = green(c1) + (green(c2) - green(c1)) * t;
        float b = blue(c1) + (blue(c2) - blue(c1)) * t;
        return color(a, r, g, b);
    }

    public static int randomColor() {
        var r = ThreadLocalRandom.current();
        return color(255, r.nextInt(256), r.nextInt(256), r.nextInt(256));
    }

    public static int randomColor(int c1, int c2) {
        return blendRGBColor(c1, c2, ThreadLocalRandom.current().nextFloat());
    }

    public static int fromVector4f(Vector4f v) {
        return color(v.w, v.x, v.y, v.z);
    }

    public static Vector4f toVector4f(int c) {
        return new Vector4f(red(c), green(c), blue(c), alpha(c));
    }

    public static int mulColor(int c1, int c2) {
        return color(alpha(c1) * alpha(c2), red(c1) * red(c2), green(c1) * green(c2), blue(c1) * blue(c2));
    }
}
