/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Camera positions used by particle geometry and facing calculations. */
public final class PhotonCameraUtils {
    private PhotonCameraUtils() {
    }

    /** Origin that emitted vertex positions must be relative to. */
    public static Vector3f renderOrigin(Camera camera) {
        return camera.position().toVector3f();
    }

    /** Origin relative to a runtime's double-precision world anchor, preserving sub-block precision at far coordinates. */
    public static Vector3f renderOrigin(Camera camera, Vec3 worldOrigin) {
        Vec3 pos = camera.position();
        return new Vector3f(
                (float) (pos.x - worldOrigin.x),
                (float) (pos.y - worldOrigin.y),
                (float) (pos.z - worldOrigin.z));
    }

    /** The real eye position for facing/orientation math. */
    public static Vector3f facingEye(Camera camera) {
        return camera.position().toVector3f();
    }

    /** The real eye position relative to a runtime's double-precision world anchor. */
    public static Vector3f facingEye(Camera camera, Vec3 worldOrigin) {
        return renderOrigin(camera, worldOrigin);
    }

    /** Camera world position minus the runtime's double-precision world anchor, kept in double precision. */
    public static Vec3 relativePosition(Camera camera, Vec3 worldOrigin) {
        Vec3 pos = camera.position();
        return worldOrigin.equals(Vec3.ZERO) ? pos : pos.subtract(worldOrigin);
    }
}
