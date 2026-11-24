package com.kltyton.lumenfx.effect.simulation;

/** Three-seed integer mixing and 24-bit float conversion used by imported VM kernels. */
final class NiagaraRandom {
    private NiagaraRandom() { }
    static float unit(int seedX, int seedY, int seedZ) {
        int x = seedX * 1664525 + 1013904223;
        int y = seedY * 1664525 + 1013904223;
        int z = seedZ * 1664525 + 1013904223;
        x += y * z;
        y += z * x;
        z += x * y;
        x += y * z;
        return ((x >>> 8) & 0x00ffffff) / 16777216.0f;
    }
}
