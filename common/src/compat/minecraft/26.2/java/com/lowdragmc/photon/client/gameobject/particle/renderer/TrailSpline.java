package com.lowdragmc.photon.client.gameobject.particle.renderer;

/** Cubic interpolation for trail control points and ribbons with authored tangents. */
public final class TrailSpline {
    private TrailSpline() {
    }

    public static double catmullRom(double a, double b, double c, double d, double t) {
        double square = t * t;
        double cube = square * t;
        return .5 * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * square
                + (-a + 3 * b - 3 * c + d) * cube);
    }

    public static double hermite(double start, double end, double startTangent, double endTangent, double t) {
        double square = t * t;
        double cube = square * t;
        return (2 * cube - 3 * square + 1) * start + (cube - 2 * square + t) * startTangent
                + (-2 * cube + 3 * square) * end + (cube - square) * endTangent;
    }

    /** Hermite basis with tangents in distance units, retaining the source float evaluation order. */
    public record FloatHermite(float startPosition, float startTangent, float endPosition, float endTangent) {
        public float sample(float start, float end, float startSlope, float endSlope) {
            return ((startPosition * start + startTangent * startSlope) + endTangent * endSlope) + endPosition * end;
        }
    }

    public static FloatHermite hermiteWeights(float t, float span) {
        float square = t * t;
        float cube = square * t;
        return new FloatHermite((2f * cube - 3f * square) + 1f, span * ((cube - 2f * square) + t),
                -2f * cube + 3f * square, span * (cube - square));
    }
}
