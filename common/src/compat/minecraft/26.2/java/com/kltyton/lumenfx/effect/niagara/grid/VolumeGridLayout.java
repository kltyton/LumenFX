package com.kltyton.lumenfx.effect.niagara.grid;

import java.nio.FloatBuffer;

/** Niagara's physical placement for scalar attributes in a tiled 3D grid. */
public record VolumeGridLayout(int x, int y, int z, int attributes, int tilesX, int tilesY, int tilesZ) {
    public static final int MAXIMUM_DIMENSION = 2048;

    public static VolumeGridLayout tiled(int x, int y, int z, int attributes) {
        int tilesX = Math.min(MAXIMUM_DIMENSION / x, Math.max(attributes, 0));
        int tilesY = tilesX == 0 ? 0 : Math.min(MAXIMUM_DIMENSION / y, Math.ceilDiv(attributes, tilesX));
        int tilesZ = tilesY == 0 ? 0 : Math.min(MAXIMUM_DIMENSION / z, Math.ceilDiv(attributes, tilesX * tilesY));
        return new VolumeGridLayout(x, y, z, attributes, tilesX, tilesY, tilesZ);
    }

    public int width() { return x * tilesX; }
    public int height() { return y * tilesY; }
    public int depth() { return z * tilesZ; }
    public boolean fits() {
        return tilesX > 0 && tilesY > 0 && tilesZ > 0 && attributes > 0
                && attributes <= (long) tilesX * tilesY * tilesZ;
    }
    public long offsetBytes() { return (2L * attributes + 1) * 4 * Float.BYTES; }

    public void writeOffsets(FloatBuffer destination) {
        float inverseX = 1.0f / tilesX, inverseY = 1.0f / tilesY, inverseZ = 1.0f / tilesZ;
        for (int index = 0; index < attributes; index++) {
            int tileX = index % tilesX;
            int tileY = index / tilesX % tilesY;
            int tileZ = index / (tilesX * tilesY);
            writeVector(destination, index, tileX * x, tileY * y, tileZ * z, 0);
            writeVector(destination, index + attributes, inverseX * tileX, inverseY * tileY, inverseZ * tileZ, 0);
        }
        writeVector(destination, attributes * 2, 65535, 65535, 65535, 65535);
    }

    private static void writeVector(FloatBuffer values, int index, float x, float y, float z, float w) {
        int word = index * 4;
        values.put(word, x).put(word + 1, y).put(word + 2, z).put(word + 3, w);
    }
}
