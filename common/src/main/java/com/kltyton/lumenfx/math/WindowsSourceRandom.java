package com.kltyton.lumenfx.math;

/** The per-thread Windows UCRT stream used by FMath::FRand. */
public final class WindowsSourceRandom {
    private static final ThreadLocal<WindowsSourceRandom> STREAM = ThreadLocal.withInitial(WindowsSourceRandom::new);
    private int state = 1;

    private WindowsSourceRandom() { }

    public static WindowsSourceRandom current() { return STREAM.get(); }

    public float unit() {
        state = state * 214013 + 2531011;
        return (state >>> 16 & 32767) / 32767.0f;
    }

    public float signedUnit() { return -1.0f + 2.0f * unit(); }

    public int range(int minimum, int maximum) {
        int width = maximum - minimum + 1;
        return minimum + (width > 0 ? Math.min((int) (unit() * width), width - 1) : 0);
    }

    public double range(double minimum, double maximum) {
        return minimum + (maximum - minimum) * unit();
    }
}
