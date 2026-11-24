package com.kltyton.lumenfx.client.render.ribbon;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.kltyton.lumenfx.effect.render.RibbonShapeGeometry;
import com.lowdragmc.photon.client.gameobject.particle.renderer.TrailSpline;
import java.util.Arrays;

/** Previous ribbon geometry reuses the current frame's particle order, tangents and distances. */
public final class RibbonMotionBuilder {
    private static final int ROW_STRIDE = 11, POINT_STRIDE = 13;
    private double[] rows = new double[0], points = new double[0];
    private final double[] scratch = new double[9];
    private EffectHandle.Pose currentPose, previousPose;
    private boolean local, customFacing;
    private int mode;
    private double cameraX, cameraY, cameraZ;

    public void begin(EmitterInstance.Snapshot snapshot, double cameraX, double cameraY, double cameraZ) {
        var particles = snapshot.nativeParticles();
        currentPose = snapshot.input().pose(); previousPose = particles.previousOwnerPose();
        local = snapshot.definition().localSpace();
        mode = particles.motion().precise() ? 1 : 2;
        customFacing = particles.ribbonSettings(snapshot.definition().ribbonSettings()).shape().facing() != RibbonSettings.Facing.SCREEN
                && particles.hasPreviousRibbonFacing();
        this.cameraX = cameraX; this.cameraY = cameraY; this.cameraZ = cameraZ;
        int required = Math.multiplyExact(particles.count(), ROW_STRIDE);
        if (rows.length < required) rows = new double[Math.max(required, rows.length * 2)];
        for (int row = 0; row < particles.count(); row++) {
            int offset = row * ROW_STRIDE;
            double x = particles.previousPosition(row, 0) * .01, y = particles.previousPosition(row, 2) * .01;
            double z = particles.previousPosition(row, 1) * .01;
            if (local) {
                rotate(scratch, 0, x * previousPose.scaleX(), y * previousPose.scaleY(), z * previousPose.scaleZ(), previousPose);
                x = scratch[0] + previousPose.position().x;
                y = scratch[1] + previousPose.position().y;
                z = scratch[2] + previousPose.position().z;
            }
            rows[offset] = x; rows[offset + 1] = y; rows[offset + 2] = z;
            x = particles.previousRibbonFacing(row, 0); y = particles.previousRibbonFacing(row, 2); z = particles.previousRibbonFacing(row, 1);
            if (local) direction(rows, offset + 3, x, y, z, previousPose, true);
            else { rows[offset + 3] = x; rows[offset + 4] = y; rows[offset + 5] = z; }
            rows[offset + 6] = particles.previousRibbonTwist(row);
            rows[offset + 7] = particles.previousRibbonWidth(row);
            x = particles.ribbonVelocity(row, 0); y = particles.ribbonVelocity(row, 2); z = particles.ribbonVelocity(row, 1);
            if (local) {
                direction(scratch, 0, x, y, z, currentPose, false);
                direction(rows, offset + 8, scratch[0], scratch[1], scratch[2], currentPose, false);
            } else { rows[offset + 8] = x; rows[offset + 9] = y; rows[offset + 10] = z; }
        }
    }

    public void sample(int point, int rowA, int rowB, double alpha, double span,
                       double[] currentTangents, int tangentA, int tangentB, RibbonSettings settings) {
        int offset = point * POINT_STRIDE, a = rowA * ROW_STRIDE, b = rowB * ROW_STRIDE;
        if (points.length < offset + POINT_STRIDE) points = Arrays.copyOf(points, Math.max(offset + POINT_STRIDE, Math.max(256 * POINT_STRIDE, points.length * 2)));
        points[offset + 12] = mode;
        for (int axis = 0; axis < 3; axis++) points[offset + 9 + axis] = mix(rows[a + 8 + axis], rows[b + 8 + axis], alpha);
        if (mode != 1) return;
        for (int endpoint = 0; endpoint < 2; endpoint++) {
            int source = endpoint == 0 ? tangentA : tangentB;
            double x = currentTangents[source], y = currentTangents[source + 1], z = currentTangents[source + 2];
            if (local) {
                rotate(scratch, endpoint * 3, x * previousPose.scaleX(), y * previousPose.scaleY(),
                        z * previousPose.scaleZ(), previousPose);
            } else { scratch[endpoint * 3] = x; scratch[endpoint * 3 + 1] = y; scratch[endpoint * 3 + 2] = z; }
        }
        for (int axis = 0; axis < 3; axis++) {
            points[offset + axis] = TrailSpline.hermite(rows[a + axis], rows[b + axis],
                    scratch[axis] * span, scratch[3 + axis] * span, alpha);
        }
        double tx = mix(currentTangents[tangentA], currentTangents[tangentB], alpha);
        double ty = mix(currentTangents[tangentA + 1], currentTangents[tangentB + 1], alpha);
        double tz = mix(currentTangents[tangentA + 2], currentTangents[tangentB + 2], alpha);
        if (local) {
            direction(scratch, 6, tx, ty, tz, previousPose, true);
            tx = scratch[6]; ty = scratch[7]; tz = scratch[8];
        }
        double inverse = inverseLength(tx, ty, tz); tx *= inverse; ty *= inverse; tz *= inverse;
        double fx = customFacing ? mix(rows[a + 3], rows[b + 3], alpha) : cameraX - points[offset];
        double fy = customFacing ? mix(rows[a + 4], rows[b + 4], alpha) : cameraY - points[offset + 1];
        double fz = customFacing ? mix(rows[a + 5], rows[b + 5], alpha) : cameraZ - points[offset + 2];
        inverse = inverseLength(fx, fy, fz); fx *= inverse; fy *= inverse; fz *= inverse;
        double sx = ty*fz - tz*fy, sy = tz*fx - tx*fz, sz = tx*fy - ty*fx;
        inverse = inverseLength(sx, sy, sz); sx *= inverse; sy *= inverse; sz *= inverse;
        if (settings.shape().facing() == RibbonSettings.Facing.CUSTOM_SIDE_VECTOR) {
            double px = sx, py = sy, pz = sz; sx = -fx; sy = -fy; sz = -fz; fx = px; fy = py; fz = pz;
        }
        double twist = mix(rows[a + 6], rows[b + 6], alpha), scale = mix(rows[a + 7], rows[b + 7], alpha) * .5;
        double cosine = Math.cos(twist), sine = Math.sin(twist);
        double rx = cosine*sx + sine*fx, ry = cosine*sy + sine*fy, rz = cosine*sz + sine*fz;
        points[offset + 3] = rx*scale; points[offset + 4] = ry*scale; points[offset + 5] = rz*scale;
        points[offset + 6] = -(ty*rz - tz*ry)*scale;
        points[offset + 7] = -(tz*rx - tx*rz)*scale;
        points[offset + 8] = -(tx*ry - ty*rx)*scale;
    }

    public void write(float[] target, int offset, int pointA, int pointB,
                      RibbonShapeGeometry.Vertex first, RibbonShapeGeometry.Vertex second) {
        for (int endpoint = 0; endpoint < 2; endpoint++) {
            int point = (endpoint == 0 ? pointA : pointB) * POINT_STRIDE;
            int destination = offset + endpoint * 8;
            for (int axis = 0; axis < 3; axis++) {
                double center = points[point + axis], side = points[point + 3 + axis];
                if (first != null && second != null) {
                    center += side * (first.x() + second.x()) + points[point + 6 + axis] * (first.y() + second.y());
                    side = side * (second.x() - first.x()) + points[point + 6 + axis] * (second.y() - first.y());
                }
                double camera = axis == 0 ? cameraX : axis == 1 ? cameraY : cameraZ;
                target[destination + axis] = (float)(center - camera);
                target[destination + 4 + axis] = (float)side;
                target[offset + 16 + endpoint * 4 + axis] = (float)points[point + 9 + axis];
            }
            target[destination + 3] = mode;
            target[destination + 7] = target[offset + 19 + endpoint * 4] = 0;
        }
    }

    private static double mix(double a, double b, double alpha) { return a + (b - a)*alpha; }
    private static double inverseLength(double x, double y, double z) {
        double squared = x*x + y*y + z*z;
        return squared > 0 ? 1 / Math.sqrt(squared) : 0;
    }

    private static void direction(double[] target, int offset, double x, double y, double z, EffectHandle.Pose pose,
                                  boolean previousRibbon) {
        x *= pose.scaleX() * com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverseAxisScale(pose, 0, previousRibbon);
        y *= pose.scaleY() * com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverseAxisScale(pose, 1, previousRibbon);
        z *= pose.scaleZ() * com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverseAxisScale(pose, 2, previousRibbon);
        rotate(target, offset, x, y, z, pose);
    }
    private static void rotate(double[] target, int offset, double x, double y, double z, EffectHandle.Pose pose) {
        double qx = pose.x(), qy = pose.y(), qz = pose.z();
        double tx = 2*(qy*z - qz*y), ty = 2*(qz*x - qx*z), tz = 2*(qx*y - qy*x);
        target[offset] = x + pose.w()*tx + qy*tz - qz*ty;
        target[offset + 1] = y + pose.w()*ty + qz*tx - qx*tz;
        target[offset + 2] = z + pose.w()*tz + qx*ty - qy*tx;
    }
}
