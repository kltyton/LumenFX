package com.kltyton.lumenfx.client.simulation.niagara;

import java.util.Arrays;

/** External-function FRandomStream and PCG counters, separate from the VM arithmetic RNG. */
public final class NiagaraExternalRandom {
    private int[] counters = new int[0];
    private int stream, row, seed1, seed2, seed3;
    private float x, y;

    void begin(int count) {
        if (counters.length < count) counters = new int[count];
        else Arrays.fill(counters, 0, count, 0);
        stream = NiagaraSeed.next();
    }

    void select(int row, int seed1, int seed2, int seed3) {
        this.row = row;
        this.seed1 = seed1;
        this.seed2 = seed2;
        this.seed3 = seed3;
    }

    public float next() {
        if (seed3 == -1) return fraction();
        pcg();
        return x;
    }
    public float nextFromStream() { return fraction(); }

    public void barycentric(int[] output, int offset) {
        if (seed3 == -1) {
            streamBarycentric(output, offset);
            return;
        }
        pcg();
        writeBarycentric(output, offset);
    }

    public void streamBarycentric(int[] output, int offset) {
        x = fraction();
        y = fraction();
        writeBarycentric(output, offset);
    }

    private void writeBarycentric(int[] output, int offset) {
        float root = (float)Math.sqrt(x);
        output[offset] = Float.floatToRawIntBits(1f - root);
        output[offset + 1] = Float.floatToRawIntBits(root * (1f - y));
        output[offset + 2] = Float.floatToRawIntBits(y * root);
    }

    private float fraction() {
        stream = stream * 196314165 + 907633515;
        return Float.intBitsToFloat(0x3f800000 | stream >>> 9) - 1f;
    }

    private void pcg() {
        int a = seed1 * 1664525 + 1013904223;
        int b = seed2 * 1664525 + 1013904223;
        int c = (counters[row]++ | seed3 << 16) * 1664525 + 1013904223;
        a += b * c;
        b += c * a;
        c += a * b;
        a += b * c;
        b += c * a;
        x = (a >>> 8) * (1f / 16777216f);
        y = (b >>> 8) * (1f / 16777216f);
    }
}
