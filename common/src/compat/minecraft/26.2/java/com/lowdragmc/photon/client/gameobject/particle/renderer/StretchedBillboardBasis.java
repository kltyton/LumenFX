package com.lowdragmc.photon.client.gameobject.particle.renderer;

import org.joml.Vector3f;

/** Velocity-aligned axes shared by Photon and imported particle renderers. */
public final class StretchedBillboardBasis {
    private StretchedBillboardBasis() { }

    public static void compute(Vector3f velocity, Vector3f toCamera, Vector3f right, Vector3f up, Vector3f normal) {
        float speed = velocity.length();
        if (speed > 1e-5f) right.set(velocity).div(speed);
        else right.set(1, 0, 0);
        if (toCamera.lengthSquared() > 1e-5f) up.set(toCamera).normalize();
        else up.set(0, 0, 1);
        up.cross(right);
        if (up.lengthSquared() < 1e-5f) {
            if (Math.abs(right.y) > 0.99f) up.set(0, 0, 1);
            else up.set(0, 1, 0);
            up.cross(right);
        }
        up.normalize();
        right.cross(up, normal).normalize();
    }
}
