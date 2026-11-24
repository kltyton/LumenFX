package com.kltyton.lumenfx.client.simulation.niagara;

/** One invocation's XORWOW state; all chunks consume the same four lanes. */
public final class BatchRandom {
    private final int[] words;

    public BatchRandom() {
        NativeVectorMath.initialize();
        words = new int[24];
        initialize(words);
    }

    public BatchRandom(int[] initialState) {
        if (initialState.length != 24) throw new IllegalArgumentException("A vector random state needs 24 words");
        words = initialState.clone();
    }

    public void next(float[] destination) {
        for (int lane = 0; lane < 4; lane++) {
            int t = words[16 + lane], s = words[lane];
            words[16 + lane] = words[12 + lane];
            words[12 + lane] = words[8 + lane];
            words[8 + lane] = words[4 + lane];
            words[4 + lane] = s;
            t ^= t >>> 2;
            t ^= t << 1;
            t ^= s << 4;
            words[lane] = t;
            words[20 + lane] += 362437;
            int bits = t + words[20 + lane];
            destination[lane] = Float.intBitsToFloat(bits >>> 9 | 0x3f800000) - 1f;
        }
    }

    private static native void initialize(int[] words);
}
