package com.kltyton.lumenfx.effect.unity;

/** Coordinate math shared by Shuriken's single-row atlas selection and drawing. */
public final class UnityAtlasAnimation {
    private UnityAtlasAnimation() { }

    public static int fixedRow(int authoredRow, int columns, int rows) {
        return (int) ((authoredRow * rowExtent(columns, rows)) * rows);
    }

    public static float sampleRow(int row, int columns, int rows, float phase) {
        float extent = rowExtent(columns, rows);
        float start = row * extent;
        float end = start + extent;
        // Unity rounds both endpoints before interpolating in normalized sheet coordinates.
        float coordinate = start + phase * (end - start);
        return coordinate * (columns * rows);
    }

    private static float rowExtent(int columns, int rows) {
        return (1f / (columns * rows)) * columns;
    }
}
