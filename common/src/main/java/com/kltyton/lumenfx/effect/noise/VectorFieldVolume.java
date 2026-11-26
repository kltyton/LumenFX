package com.kltyton.lumenfx.effect.noise;

import com.kltyton.lumenfx.math.HalfFloat;

/** Immutable vector field with source-space bounds and centered trilinear sampling. */
public final class VectorFieldVolume {
    private final int sizeX, sizeY, sizeZ;
    private final double minX, minY, minZ, inverseX, inverseY, inverseZ;
    private final double maxX, maxY, maxZ;
    private final double inverseBoundsX, inverseBoundsY, inverseBoundsZ;
    private final boolean sourceCoordinates;
    private final float[] values;
    private final short[] sourceHalfRgba;

    public VectorFieldVolume(int sizeX, int sizeY, int sizeZ, double minX, double minY, double minZ,
                             double maxX, double maxY, double maxZ, float[] values) {
        this(sizeX, sizeY, sizeZ, minX, minY, minZ, maxX, maxY, maxZ, values, false, null);
    }

    public static VectorFieldVolume fromSource(int x, int y, int z, double minX, double minY, double minZ,
                                               double maxX, double maxY, double maxZ, float[] values) {
        return new VectorFieldVolume(x, y, z, minX, minY, minZ, maxX, maxY, maxZ, values, true, null);
    }

    public static VectorFieldVolume fromHalfRgba(int x, int y, int z, double minX, double minY, double minZ,
                                                 double maxX, double maxY, double maxZ, short[] rgba,
                                                 boolean sourceCoordinates) {
        int cells = Math.multiplyExact(Math.multiplyExact(x, y), z);
        if (rgba.length != Math.multiplyExact(cells, 4)) throw new IllegalArgumentException("Invalid vector field half payload");
        float[] values = new float[Math.multiplyExact(cells, 3)];
        for (int cell = 0; cell < cells; cell++) {
            for (int axis = 0; axis < 3; axis++) {
                float value = HalfFloat.toFloat(rgba[cell * 4 + axis]);
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite vector field value");
                values[cell * 3 + axis] = value;
            }
        }
        return new VectorFieldVolume(x, y, z, minX, minY, minZ, maxX, maxY, maxZ, values, sourceCoordinates, rgba);
    }

    private VectorFieldVolume(int sizeX, int sizeY, int sizeZ, double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ, float[] values, boolean sourceCoordinates,
                              short[] sourceHalfRgba) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || !Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || maxX <= minX || maxY <= minY || maxZ <= minZ
                || values.length != Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ), 3))
            throw new IllegalArgumentException("Invalid vector field dimensions, bounds or payload");
        this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        inverseX = sizeX / (maxX - minX); inverseY = sizeY / (maxY - minY); inverseZ = sizeZ / (maxZ - minZ);
        inverseBoundsX = 1 / (maxX - minX); inverseBoundsY = 1 / (maxY - minY); inverseBoundsZ = 1 / (maxZ - minZ);
        this.sourceCoordinates = sourceCoordinates;
        this.values = values.clone();
        this.sourceHalfRgba = sourceHalfRgba == null ? null : sourceHalfRgba.clone();
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public boolean sourceCoordinates() { return sourceCoordinates; }
    public double minX() { return minX; }
    public double minY() { return minY; }
    public double minZ() { return minZ; }
    public double maxX() { return maxX; }
    public double maxY() { return maxY; }
    public double maxZ() { return maxZ; }

    public short[] halfRgba() {
        if (sourceHalfRgba != null) return sourceHalfRgba.clone();
        short[] result = new short[Math.multiplyExact(values.length / 3, 4)];
        for (int cell = 0; cell < values.length / 3; cell++)
            for (int axis = 0; axis < 3; axis++) result[cell * 4 + axis] = HalfFloat.fromFloat(values[cell * 3 + axis]);
        return result;
    }

    public void sample(double x, double y, double z, boolean tileX, boolean tileY, boolean tileZ, double[] output, int offset) {
        if (sourceCoordinates) {
            sampleSource(x * 100, z * 100, y * 100, tileX, tileZ, tileY, output, offset);
            double swap = output[offset + 1]; output[offset + 1] = output[offset + 2]; output[offset + 2] = swap;
            return;
        }
        sampleTexels((x - minX) * inverseX - .5, (y - minY) * inverseY - .5, (z - minZ) * inverseZ - .5,
                tileX, tileY, tileZ, output, offset);
    }

    public void sampleNearest(double x, double y, double z, double[] output, int offset) {
        int ix = index(Math.floor((x - minX) * inverseX), sizeX, false);
        int iy = index(Math.floor((y - minY) * inverseY), sizeY, false);
        int iz = index(Math.floor((z - minZ) * inverseZ), sizeZ, false);
        int cell = texel(ix, iy, iz);
        output[offset] = values[cell];
        output[offset + 1] = values[cell + 1];
        output[offset + 2] = values[cell + 2];
    }

    public void sampleSource(double x, double y, double z, boolean tileX, boolean tileY, boolean tileZ, double[] output, int offset) {
        if (!sourceCoordinates) {
            sample(x * .01, z * .01, y * .01, tileX, tileZ, tileY, output, offset);
            double swap = output[offset + 1]; output[offset + 1] = output[offset + 2]; output[offset + 2] = swap;
            return;
        }
        sampleTexels((x - minX) * inverseBoundsX * sizeX - .5, (y - minY) * inverseBoundsY * sizeY - .5,
                (z - minZ) * inverseBoundsZ * sizeZ - .5, tileX, tileY, tileZ, output, offset);
    }

    private void sampleTexels(double x, double y, double z, boolean tileX, boolean tileY, boolean tileZ,
                              double[] output, int offset) {
        double floorX = Math.floor(x), floorY = Math.floor(y), floorZ = Math.floor(z);
        double fx = x - floorX, fy = y - floorY, fz = z - floorZ;
        int x0 = index(floorX, sizeX, tileX), x1 = index(floorX + 1, sizeX, tileX);
        int y0 = index(floorY, sizeY, tileY), y1 = index(floorY + 1, sizeY, tileY);
        int z0 = index(floorZ, sizeZ, tileZ), z1 = index(floorZ + 1, sizeZ, tileZ);
        int a = texel(x0, y0, z0), b = texel(x1, y0, z0);
        int c = texel(x0, y1, z0), d = texel(x1, y1, z0);
        int e = texel(x0, y0, z1), f = texel(x1, y0, z1);
        int g = texel(x0, y1, z1), h = texel(x1, y1, z1);
        for (int axis = 0; axis < 3; axis++) {
            double low0 = lerp(values[a + axis], values[b + axis], fx), low1 = lerp(values[c + axis], values[d + axis], fx);
            double high0 = lerp(values[e + axis], values[f + axis], fx), high1 = lerp(values[g + axis], values[h + axis], fx);
            output[offset + axis] = (float)lerp(lerp(low0, low1, fy), lerp(high0, high1, fy), fz);
        }
    }

    private int texel(int x, int y, int z) { return (x + sizeX * (y + sizeY * z)) * 3; }

    /** Integer loads return zero outside the volume and never apply the sampler's tiling. */
    public void loadSource(int x, int y, int z, double[] output, int offset) {
        int actualY = sourceCoordinates ? y : z, actualZ = sourceCoordinates ? z : y;
        if (x < 0 || actualY < 0 || actualZ < 0 || x >= sizeX || actualY >= sizeY || actualZ >= sizeZ) {
            output[offset] = output[offset + 1] = output[offset + 2] = 0;
            return;
        }
        int texel = texel(x, actualY, actualZ);
        output[offset] = values[texel];
        output[offset + 1] = values[texel + (sourceCoordinates ? 1 : 2)];
        output[offset + 2] = values[texel + (sourceCoordinates ? 2 : 1)];
    }

    private static int index(double value, int size, boolean tile) {
        if (tile) value -= Math.floor(value / size) * size;
        return (int) Math.max(0, Math.min(value, size - 1));
    }
    private static double lerp(double a, double b, double fraction) { return a + fraction * (b - a); }
}
