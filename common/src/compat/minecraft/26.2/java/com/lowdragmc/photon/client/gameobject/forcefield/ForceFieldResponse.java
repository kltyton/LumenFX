/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.forcefield;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Shared directional, gravity, vortex and drag response for particle force fields. */
public final class ForceFieldResponse {
    private ForceFieldResponse() { }

    public static float influence(ForceFieldConfig.Shape shape, float start, float end, float halfLength, Vector3f local) {
        if (end <= 0) return 0;
        float radius = switch (shape) {
            case Sphere -> local.length();
            case Hemisphere -> local.y < 0 ? Float.MAX_VALUE : local.length();
            case Cylinder -> Math.max((float) Math.sqrt(local.x * local.x + local.z * local.z),
                    halfLength == end ? Math.abs(local.y) : Math.abs(local.y) / halfLength * end);
            case Box -> Math.max(Math.abs(local.x), Math.max(Math.abs(local.y), Math.abs(local.z)));
        };
        if (radius > end) return 0;
        start = Math.min(start, end);
        return radius <= start ? 1f : 1f - (radius - start) / (end - start);
    }

    public static void direction(Matrix4fc basis, Vector3f direction, Vector3f velocity, float step) {
        if (direction.lengthSquared() > 0) {
            float magnitude = direction.length();
            basis.transformDirection(direction).normalize(magnitude);
            velocity.add(direction.mul(step));
        }
    }

    public static void gravity(Matrix4fc basis, Vector3f local, Vector3f velocity, Vector3f scratch,
                               float gravity, float focusRadius, float forceScale, float strength, float elapsed) {
        if (gravity == 0 || local.lengthSquared() <= 1e-8f) return;
        scratch.set(local).normalize(focusRadius).sub(local);
        if (scratch.lengthSquared() > 1e-8f) {
            basis.transformDirection(scratch).normalize();
            velocity.add(scratch.mul(gravity * forceScale * strength * elapsed));
        }
    }

    public static void vortex(Matrix4fc basis, Vector3f local, Vector3f velocity, Vector3f scratch,
                              float anchorX, float anchorZ, float speed, float step, float attraction) {
        float radialX = local.x - anchorX, radialZ = local.z - anchorZ;
        if (radialX * radialX + radialZ * radialZ <= 1e-8f) return;
        scratch.set(-radialZ, 0, radialX);
        basis.transformDirection(scratch).normalize(speed);
        velocity.add(scratch.x * step, scratch.y * step, scratch.z * step);
        if (attraction > 0) velocity.lerp(scratch, Math.min(attraction, 1));
    }

    public static void drag(Vector3f velocity, float size, float coefficient, float forceScale, float elapsed,
                            boolean multiplyBySize, boolean multiplyBySpeed) {
        if (multiplyBySize) coefficient *= size;
        if (multiplyBySpeed) coefficient *= velocity.length();
        velocity.mul(Math.max(0f, 1f - coefficient * forceScale * elapsed));
    }
}
