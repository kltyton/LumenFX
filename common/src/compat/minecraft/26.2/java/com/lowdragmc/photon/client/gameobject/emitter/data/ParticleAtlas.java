package com.lowdragmc.photon.client.gameobject.emitter.data;

import org.joml.Vector4f;

/** Normalized texture coordinates for particles using a uniformly tiled sheet. */
public final class ParticleAtlas {
    private ParticleAtlas() {
    }

    /** A negative row selects the whole sheet. The caller defines how to turn its frame sample into an integer. */
    public static Vector4f wrappedFrame(int frame, int columns, int rows, int row, Vector4f destination) {
        long index = wrappedIndex(frame, columns, rows, row);
        return cell((int) (index % columns), (int) (index / columns), columns, rows, destination);
    }

    public static long wrappedIndex(int frame, int columns, int rows, int row) {
        if (row < 0) return Math.floorMod((long) frame, (long) columns * rows);
        return (long) row * columns + Math.floorMod(frame, columns);
    }

    static Vector4f cell(int column, int row, int columns, int rows, Vector4f destination) {
        float u = (float) column / columns;
        float v = (float) row / rows;
        return destination.set(u, v, u + 1f / columns, v + 1f / rows);
    }

    /**
     * Writes both SubUV frames as column, row, and reciprocal sheet size. The shader applies address modes;
     * the next frame keeps its original row when it steps past the final frame.
     */
    public static float sourceSubImages(float image, float columns, float rows, boolean blend,
                                        float[] destination, int current, int next) {
        float fraction = image - (float)Math.floor(image);
        float first = image - fraction + .5f;
        float frameU = 1f / columns;
        float frameV = 1f / rows;
        sourceCell(first, columns, frameU, frameV, destination, current);
        sourceCell(first + 1f, columns, frameU, frameV, destination, next);
        return blend ? fraction : 0;
    }

    private static void sourceCell(float frame, float columns, float frameU, float frameV, float[] destination, int offset) {
        destination[offset] = (float)Math.floor(frame % columns);
        destination[offset + 1] = (float)Math.floor(frame * frameU);
        destination[offset + 2] = frameU;
        destination[offset + 3] = frameV;
    }
}
