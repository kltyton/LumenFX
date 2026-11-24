package com.kltyton.lumenfx.math;

/** FRandomStream's scalar sequence, distinct from VM XORWOW and per-particle PCG. */
public final class SourceRandomStream {
    private int initialSeed, seed;
    public SourceRandomStream(int seed) { initialize(seed); }
    public void initialize(int value) { initialSeed = value; seed = value; }
    public void reset() { seed = initialSeed; }
    public int seed() { return seed; }
    public int nextUInt() { seed = seed * 196314165 + 907633515; return seed; }
    public float fraction() { return Float.intBitsToFloat(0x3f800000 | nextUInt() >>> 9) - 1f; }
    public int rangeInt(int minimum, int maximum) {
        int range = maximum - minimum + 1;
        return minimum + (range > 0 ? SourceFloatMath.truncToInt(fraction() * (float)range) : 0);
    }
    public float rangeFloat(float minimum, float maximum) { return (maximum - minimum) * fraction() + minimum; }
}
