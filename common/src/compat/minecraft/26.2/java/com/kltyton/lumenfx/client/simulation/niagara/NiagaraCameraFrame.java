package com.kltyton.lumenfx.client.simulation.niagara;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Immutable camera values captured on the render thread for Niagara CPU interfaces. */
public record NiagaraCameraFrame(double x, double y, double z,
                                 float forwardX, float forwardY, float forwardZ,
                                 float upX, float upY, float upZ, float rightX, float rightY, float rightZ,
                                 float fieldOfView, float viewportWidth, float viewportHeight, float nearPlane, int splitscreenMode) {
    public NiagaraCameraFrame(double x, double y, double z,
                              float forwardX, float forwardY, float forwardZ,
                              float upX, float upY, float upZ, float rightX, float rightY, float rightZ,
                              float fieldOfView, float viewportWidth, float viewportHeight, float nearPlane) {
        this(x, y, z, forwardX, forwardY, forwardZ, upX, upY, upZ, rightX, rightY, rightZ,
                fieldOfView, viewportWidth, viewportHeight, nearPlane, 0);
    }
    public static NiagaraCameraFrame capture(Vec3 camera, Quaternionf orientation, float horizontalFieldOfView,
                                             int width, int height, float nearPlaneCm) {
        Vector3f forward = orientation.transform(new Vector3f(0, 0, -1));
        Vector3f up = orientation.transform(new Vector3f(0, 1, 0));
        Vector3f right = orientation.transform(new Vector3f(1, 0, 0));
        return new NiagaraCameraFrame(camera.x * 100, camera.z * 100, camera.y * 100,
                forward.x, forward.z, forward.y, up.x, up.z, up.y, right.x, right.z, right.y,
                horizontalFieldOfView, width, height, nearPlaneCm);
    }
}
