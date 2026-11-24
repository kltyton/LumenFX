package com.kltyton.lumenfx.effect.unity;

/** The base texture coordinates and material transform of a Shuriken trail. */
public record UnityTrailTexture(Mode mode, double scaleU, double scaleV, double offsetU, double offsetV) {
    public enum Mode { STRETCH, TILE, DISTRIBUTE_PER_SEGMENT, REPEAT_PER_SEGMENT }

    public UnityTrailTexture {
        if (mode == null || !Double.isFinite(scaleU) || !Double.isFinite(scaleV)
                || !Double.isFinite(offsetU) || !Double.isFinite(offsetV))
            throw new IllegalArgumentException("Invalid Unity trail texture transform");
    }

    public double u(double distance, double length, double segment, int segments) {
        double coordinate = switch (mode) {
            case STRETCH -> distance / length;
            case TILE -> distance;
            case DISTRIBUTE_PER_SEGMENT -> segment / segments;
            case REPEAT_PER_SEGMENT -> segment;
        };
        return coordinate * scaleU + offsetU;
    }

    public double rasterOffsetV() { return 1 - scaleV - offsetV; }
}
