package com.kltyton.lumenfx.effect.noise;

/** The seeded Niagara simplex Jacobian and its curl in source coordinate units. */
public final class NiagaraCurlNoise {
    private final float offsetX, offsetY, offsetZ;

    public NiagaraCurlNoise(int seed) {
        long random = random(seed, seed, seed);
        offsetX = ((random >>> 32) & 0xffffL) * .01f;
        offsetY = ((random >>> 16) & 0xffffL) * .01f;
        offsetZ = (random & 0xffffL) * .01f;
    }

    public float offsetX() { return offsetX; }
    public float offsetY() { return offsetY; }
    public float offsetZ() { return offsetZ; }

    public void sample(float x, float y, float z, float[] output) {
        if (output.length < 3) throw new IllegalArgumentException("Niagara curl output requires three components");
        x += offsetX; y += offsetY; z += offsetZ;
        float skew = (x + y + z) * (1f / 3f);
        float tx = (float)Math.floor(x + skew), ty = (float)Math.floor(y + skew), tz = (float)Math.floor(z + skew);
        float unskew = (tx + ty + tz) * (1f / 6f);
        float bx = tx - unskew, by = ty - unskew, bz = tz - unskew;
        float fx = x - bx, fy = y - by, fz = z - bz;
        float gx = fx >= fy ? 1 : 0, gy = fy >= fz ? 1 : 0, gz = fz >= fx ? 1 : 0;
        float hx = 1 - gz, hy = 1 - gx, hz = 1 - gy;
        float j01 = 0, j02 = 0, j10 = 0, j12 = 0, j20 = 0, j21 = 0;
        for (int corner = 0; corner < 4; corner++) {
            float cx = bx, cy = by, cz = bz;
            if (corner == 1) {
                cx += Math.min(gx, hx) - 1f / 6f; cy += Math.min(gy, hy) - 1f / 6f; cz += Math.min(gz, hz) - 1f / 6f;
            } else if (corner == 2) {
                cx += Math.max(gx, hx) - 1f / 3f; cy += Math.max(gy, hy) - 1f / 3f; cz += Math.max(gz, hz) - 1f / 3f;
            } else if (corner == 3) { cx += .5f; cy += .5f; cz += .5f; }
            float rx = x - cx, ry = y - cy, rz = z - cz;
            long hash = random((int)Math.floor(6f * cx + .5f), (int)Math.floor(6f * cy + .5f), (int)Math.floor(6f * cz + .5f));
            int ix = (int)(hash >>> 32) & 0xffff, iy = (int)(hash >>> 16) & 0xffff, iz = (int)hash & 0xffff;
            float xx = gradient(ix, 0x8000), xy = gradient(ix, 0x4000), xz = gradient(ix, 0x2000);
            float yx = gradient(iy, 0x8000), yy = gradient(iy, 0x4000), yz = gradient(iy, 0x2000);
            float zx = gradient(iz, 0x8000), zy = gradient(iz, 0x4000), zz = gradient(iz, 0x2000);
            float gradX = xx * rx + xy * ry + xz * rz;
            float gradY = yx * rx + yy * ry + yz * rz;
            float gradZ = zx * rx + zy * ry + zz * rz;
            float distance = rx * rx + ry * ry + rz * rz;
            float s = Math.clamp(distance + distance, 0f, 1f);
            float value = (1024f / 375f) * (1f + s * (-3f + s * (3f - s)));
            float derivative = (1024f / 375f) * (-12f + s * (24f - s * 12f));
            j01 += value * xy + (derivative * gradX) * ry;
            j02 += value * xz + (derivative * gradX) * rz;
            j10 += value * yx + (derivative * gradY) * rx;
            j12 += value * yz + (derivative * gradY) * rz;
            j20 += value * zx + (derivative * gradZ) * rx;
            j21 += value * zy + (derivative * gradZ) * ry;
        }
        output[0] = j12 - j21; output[1] = j20 - j02; output[2] = j01 - j10;
    }

    private static float gradient(int value, int mask) { return (value & mask) == 0 ? -1f : 1f; }

    private static long random(int x, int y, int z) {
        x = x * 1664525 + 1013904223; y = y * 1664525 + 1013904223; z = z * 1664525 + 1013904223;
        x += y * z; y += z * x; z += x * y; x += y * z; y += z * x; z += x * y;
        return ((long)(x >>> 16) << 32) | ((long)(y >>> 16) << 16) | (z >>> 16);
    }
}
