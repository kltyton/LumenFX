package com.kltyton.lumenfx.client.render.ribbon;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3fc;

/** Offsets a Shuriken ribbon in the camera plane using the joins of its projected edges. */
public final class ShurikenRibbonJoins {
    // The source join has a denominator floor near a reversal and bounds the miter length.
    private static final double MINIMUM_JOIN_DENOMINATOR = 0.01;
    private static final double MAXIMUM_MITER_SCALE = 3;
    private final Vector3d right = new Vector3d();
    private final Vector3d up = new Vector3d();
    private final Vector3d forward = new Vector3d();
    private final Vector2d previousSide = new Vector2d();
    private final Vector2d nextSide = new Vector2d();
    private final Vector2d previousPoint = new Vector2d();
    private final Vector2d nextPoint = new Vector2d();
    private Vec3 origin = Vec3.ZERO;

    public void view(Vec3 origin, Vector3fc right, Vector3fc up, Vector3fc forward) {
        this.origin = origin;
        this.right.set(right);
        this.up.set(up);
        this.forward.set(forward);
    }

    public void apply(double[] values, int count, int stride, int widthOffset, int sideOffset) {
        project(values, 0, previousPoint);
        project(values, stride, nextPoint);
        nextSide.set(0, -1);
        edgeSide();
        previousSide.set(nextSide);
        for (int i = 0; i < count; i++) {
            int point = i * stride;
            if (i > 0 && i < count - 1) {
                previousPoint.set(nextPoint);
                project(values, point + stride, nextPoint);
                edgeSide();
            }
            double dot = previousSide.dot(nextSide);
            double divisor = Math.max(MINIMUM_JOIN_DENOMINATOR, 1 + dot);
            double x = previousSide.x + (nextSide.x - previousSide.x * dot) / divisor;
            double y = previousSide.y + (nextSide.y - previousSide.y * dot) / divisor;
            double width = values[point + widthOffset] * 0.5;
            double scale = width * Math.min(1, MAXIMUM_MITER_SCALE / Math.hypot(x, y));
            int side = point + sideOffset;
            values[side] = scale * (x * right.x + y * up.x);
            values[side + 1] = scale * (x * right.y + y * up.y);
            values[side + 2] = scale * (x * right.z + y * up.z);
            previousSide.set(nextSide);
        }
    }

    private void project(double[] values, int offset, Vector2d output) {
        double x = values[offset] - origin.x;
        double y = values[offset + 1] - origin.y;
        double z = values[offset + 2] - origin.z;
        double depth = forward.x * x + forward.y * y + forward.z * z;
        depth = Math.copySign(Math.max(Math.abs(depth), 1e-8), depth);
        output.set((right.x * x + right.y * y + right.z * z) / depth,
                (up.x * x + up.y * y + up.z * z) / depth);
    }

    private void edgeSide() {
        double x = nextPoint.x - previousPoint.x;
        double y = nextPoint.y - previousPoint.y;
        double length = Math.hypot(x, y);
        if (length > 1e-10) nextSide.set(y / length, -x / length);
    }
}

