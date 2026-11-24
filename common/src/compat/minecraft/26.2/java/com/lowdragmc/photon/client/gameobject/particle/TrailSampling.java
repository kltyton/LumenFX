package com.lowdragmc.photon.client.gameobject.particle;

/** Vertex distance and retention shared by trails using different clocks and coordinate spaces. */
public final class TrailSampling {
    private TrailSampling() { }

    public static boolean reachesNextVertex(double distanceSquared, double minimumDistance) {
        return distanceSquared >= minimumDistance * minimumDistance;
    }

    public static boolean reachesNextVertex(float distanceSquared, float minimumDistance) {
        return distanceSquared >= minimumDistance * minimumDistance;
    }

    public static boolean retained(double remainingLifetime) {
        return remainingLifetime >= 0;
    }
}
