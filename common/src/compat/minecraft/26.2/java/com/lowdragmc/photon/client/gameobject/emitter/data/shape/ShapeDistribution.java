package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

/** Inverse distributions for particle positions on radial shapes. */
public final class ShapeDistribution {
    private ShapeDistribution() {
    }

    public static double annulusRadius(double inner, double outer, double fraction) {
        double square = inner * inner;
        return Math.sqrt(square + fraction * (outer * outer - square));
    }

    public static double shellRadius(double inner, double outer, double fraction) {
        double cube = inner * inner * inner;
        return Math.cbrt(cube + fraction * (outer * outer * outer - cube));
    }

    /** Selects the free coordinate of a box edge, weighted by the lengths of the four edges on each axis. */
    public static int boxEdgeAxis(double x, double y, double z, double fraction) {
        double distance = fraction * (x + y + z);
        if (distance < x) return 0;
        return distance < x + y ? 1 : 2;
    }

    public static double triangleEdgeWeight(double areaFraction) {
        return Math.sqrt(areaFraction);
    }

    public static double coneTilt(double radialFraction, double maximumTilt) {
        return radialFraction * maximumTilt;
    }
}
