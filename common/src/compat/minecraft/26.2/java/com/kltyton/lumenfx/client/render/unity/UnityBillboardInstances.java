package com.kltyton.lumenfx.client.render.unity;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.simulation.EmitterInstance.Snapshot;
import com.kltyton.lumenfx.effect.unity.UnityBillboard;
import com.kltyton.lumenfx.effect.unity.UnityBillboard.Alignment;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting.Mode;
import com.lowdragmc.photon.client.gameobject.particle.renderer.StretchedBillboardBasis;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Packs Unity facing into the shared sprite instance's facing and alignment vectors. */
public final class UnityBillboardInstances {
    private final Quaterniond rotation = new Quaterniond(), tilt = new Quaterniond();
    private final Vector3d facing = new Vector3d(), up = new Vector3d(), right = new Vector3d(), viewRight = new Vector3d(), viewUp = new Vector3d();
    private final Vector3d tiltedFacing = new Vector3d(), tiltedUp = new Vector3d();
    private final Vector3d particleVelocity = new Vector3d();
    private final Vector3f stretchVelocity = new Vector3f(), stretchView = new Vector3f(), stretchRight = new Vector3f();
    private final Vector3f stretchUp = new Vector3f(), stretchNormal = new Vector3f();
    private final double[] velocity = new double[3];
    private Vec3 cameraVelocity = Vec3.ZERO;

    public void prepare(double rightX, double rightY, double rightZ, double upX, double upY, double upZ, Pose pose,
                        Vec3 cameraVelocity, boolean allowRoll) {
        viewRight.set(rightX, rightY, rightZ);
        viewUp.set(upX, upY, upZ);
        if (!allowRoll) {
            viewRight.cross(viewUp, facing).normalize();
            viewRight.set(0, 1, 0).cross(facing);
            if (viewRight.lengthSquared() < 1e-12) viewRight.set(1, 0, 0).fma(-facing.x, facing);
            facing.cross(viewRight.normalize(), viewUp);
        }
        rotation.set(pose.x(), pose.y(), pose.z(), pose.w()).normalize();
        this.cameraVelocity = cameraVelocity;
    }

    public void pack(UnityBillboard billboard, Snapshot snapshot, int row, float[] data, int offset) {
        facing.set(data[offset], data[offset + 1], data[offset + 2]).negate();
        up.set(viewUp);
        if (billboard.mode() == Mode.StretchedBillboard) {
            stretch(billboard, snapshot, row, data, offset);
            return;
        }
        if (billboard.mode() == Mode.Horizontal) {
            facing.set(0, 1, 0);
            up.set(0, 0, -1);
        } else if (billboard.mode() == Mode.VerticalBillboard) {
            facing.y = 0;
            up.set(0, 1, 0);
        } else {
            var birthDirection = snapshot.particles().get(row).unityBirthDirection();
            if (birthDirection != null) {
                alignAtBirth(billboard.alignment(), snapshot, birthDirection);
            } else switch (billboard.alignment()) {
                case VIEW -> viewRight.cross(viewUp, facing);
                case WORLD -> { facing.set(0, 0, 1); up.set(0, 1, 0); }
                case LOCAL -> {
                    rotation.transform(facing.set(0, 0, 1));
                    rotation.transform(up.set(0, 1, 0));
                }
                case FACING -> { if (!billboard.allowRoll()) up.set(0, 1, 0); }
                case VELOCITY -> {
                    facing.set(velocity(snapshot, row));
                    up.set(0, 1, 0);
                }
            }
        }
        if (facing.lengthSquared() < 1e-12) viewRight.cross(viewUp, facing);
        facing.normalize();
        up.fma(-facing.dot(up), facing);
        if (up.lengthSquared() < 1e-12) {
            up.set(viewRight).fma(-facing.dot(viewRight), facing);
            if (up.lengthSquared() < 1e-12) up.set(viewUp).fma(-facing.dot(viewUp), facing);
        }
        up.normalize();
        applyBillboardScale(billboard, snapshot, data, offset);
        applyTiltRotation(billboard, snapshot, row);
        packAxes(billboard, data, offset);
    }

    private void applyBillboardScale(UnityBillboard billboard, Snapshot snapshot, float[] data, int offset) {
        if (billboard.mode() != Mode.Billboard || billboard.alignment() != Alignment.VIEW) return;
        var definition = snapshot.simulationDefinition();
        if (!definition.localSpace() && !definition.scaleSizeWithOwner()) return;
        var pose = snapshot.input().pose();
        double sx = Math.abs(pose.scaleX()), sy = Math.abs(pose.scaleY()), sz = Math.abs(pose.scaleZ());
        if (!(sx > 0 && sy > 0 && sz > 0) || (sx == sy && sy == sz)) return;
        up.cross(facing, right);
        if (right.lengthSquared() < 1e-12) return;
        right.normalize();
        double rightScale = Math.sqrt(right.x * right.x * sx * sx + right.y * right.y * sy * sy + right.z * right.z * sz * sz);
        double upScale = Math.sqrt(up.x * up.x * sx * sx + up.y * up.y * sy * sy + up.z * up.z * sz * sz);
        data[offset + 3] = (float) (data[offset + 3] * (rightScale / sx));
        data[offset + 4] = (float) (data[offset + 4] * (upScale / sy));
    }

    private void alignAtBirth(Alignment alignment, Snapshot snapshot, Vec3 direction) {
        facing.set(direction.x, direction.y, direction.z);
        if (snapshot.simulationDefinition().localSpace()) {
            var pose = snapshot.input().pose();
            rotation.transform(facing.mul(pose.scaleX(), pose.scaleY(), pose.scaleZ()));
        }
        up.set(0, 1, 0);
        if (alignment != Alignment.WORLD) rotation.transform(up);
        if (facing.lengthSquared() < 1e-12) {
            facing.set(0, 0, 1);
            if (alignment != Alignment.WORLD) rotation.transform(facing);
        }
        double parallel = facing.dot(up);
        if (parallel * parallel > .999 * facing.lengthSquared()) {
            up.set(1, 0, 0);
            if (alignment != Alignment.WORLD) rotation.transform(up);
        }
    }

    private Vector3d velocity(Snapshot snapshot, int row) {
        var particle = snapshot.particles().get(row);
        particle.sampleVelocity(snapshot.time(), snapshot.simulationDefinition(), velocity, 0);
        particleVelocity.set(velocity[0], velocity[1], velocity[2]);
        if (snapshot.simulationDefinition().localSpace()) {
            var pose = snapshot.input().pose();
            particleVelocity.mul(pose.scaleX(), pose.scaleY(), pose.scaleZ());
            rotation.transform(particleVelocity);
        }
        return particleVelocity;
    }

    private void stretch(UnityBillboard billboard, Snapshot snapshot, int row, float[] data, int offset) {
        var particleVelocity = velocity(snapshot, row);
        stretchVelocity.set((float) particleVelocity.x, (float) particleVelocity.y, (float) particleVelocity.z);
        float speedScale = billboard.velocityScale();
        if (billboard.usesCameraVelocity() && cameraVelocity.lengthSqr() > 0) {
            float cameraScale = billboard.cameraVelocityScale();
            stretchVelocity.mul(speedScale).sub((float) (cameraVelocity.x * cameraScale),
                    (float) (cameraVelocity.y * cameraScale), (float) (cameraVelocity.z * cameraScale));
            speedScale = 1;
        }
        stretchView.set((float) facing.x, (float) facing.y, (float) facing.z);
        if (billboard.freeformStretching() && stretchView.lengthSquared() > 1e-5f) {
            stretchView.normalize();
            stretchVelocity.fma(-stretchVelocity.dot(stretchView), stretchView);
        }
        StretchedBillboardBasis.compute(stretchVelocity, stretchView, stretchRight, stretchUp, stretchNormal);
        float width = data[offset + 3];
        float stretch = billboard.lengthScale() == 0 ? 0
                : width * (billboard.lengthScale() + speedScale * stretchVelocity.length());
        float trailingOffset = (stretch - width) * .5f;
        data[offset] -= stretchRight.x * trailingOffset;
        data[offset + 1] -= stretchRight.y * trailingOffset;
        data[offset + 2] -= stretchRight.z * trailingOffset;
        if (billboard.freeformStretching() && !billboard.rotateWithStretch()) {
            viewRight.cross(viewUp, facing);
            double horizontal = Math.abs(stretchRight.x * viewRight.x + stretchRight.y * viewRight.y + stretchRight.z * viewRight.z);
            double vertical = Math.abs(stretchRight.x * viewUp.x + stretchRight.y * viewUp.y + stretchRight.z * viewUp.z);
            float addedLength = Math.max(0, stretch - width);
            data[offset + 3] += (float) (addedLength * horizontal);
            data[offset + 4] += (float) (addedLength * vertical);
        } else {
            facing.set(stretchNormal.x, stretchNormal.y, stretchNormal.z);
            up.set(stretchUp.x, stretchUp.y, stretchUp.z);
            data[offset + 3] = stretch;
        }
        if (billboard.lengthScale() == 0) data[offset + 3] = data[offset + 4] = 0;
        applyTiltRotation(billboard, snapshot, row);
        packAxes(billboard, data, offset);
    }

    private void applyTiltRotation(UnityBillboard billboard, Snapshot snapshot, int row) {
        var particle = snapshot.particles().get(row);
        var birth = particle.meshBirth();
        double rx = (birth == null ? 0 : birth.rotationX()) + particle.integratedRotationX();
        double ry = (birth == null ? 0 : birth.rotationY()) + particle.integratedRotationY();
        var angular = billboard.angularVelocity();
        if (!angular.isEmpty()) {
            double age = particle.normalizedAge(snapshot.time());
            rx += particle.spinOverLife(angular.get(0), age, snapshot.time());
            ry += particle.spinOverLife(angular.get(1), age, snapshot.time());
        }
        if (Math.abs(rx) <= 1e-9 && Math.abs(ry) <= 1e-9) return;
        up.cross(facing, right);
        if (right.lengthSquared() < 1e-12) return;
        right.normalize();
        tilt.rotationYXZ((float) -ry, (float) -rx, 0);
        tiltedUp.set(0, 1, 0);
        tilt.transform(tiltedUp);
        tiltedFacing.set(0, 0, 1);
        tilt.transform(tiltedFacing);
        double ux = tiltedUp.x, uy = tiltedUp.y, uz = tiltedUp.z;
        double fx = tiltedFacing.x, fy = tiltedFacing.y, fz = tiltedFacing.z;
        tiltedUp.set(
                right.x * ux + up.x * uy + facing.x * uz,
                right.y * ux + up.y * uy + facing.y * uz,
                right.z * ux + up.z * uy + facing.z * uz).normalize();
        facing.set(
                right.x * fx + up.x * fy + facing.x * fz,
                right.y * fx + up.y * fy + facing.y * fz,
                right.z * fx + up.z * fy + facing.z * fz).normalize();
        up.set(tiltedUp);
    }

    private void packAxes(UnityBillboard billboard, float[] data, int offset) {
        data[offset + 34] = (float) facing.x;
        data[offset + 35] = (float) facing.y;
        data[offset + 36] = (float) facing.z;
        data[offset + 37] = (float) up.x;
        data[offset + 38] = (float) up.y;
        data[offset + 39] = (float) up.z;
        data[offset + 40] = 2;
        data[offset + 41] = 2;
        data[offset + 43] = .5f + billboard.pivotX();
        data[offset + 44] = .5f - billboard.pivotY();
        var limits = billboard.sizeLimits();
        if (limits != null) {
            // Unity sprites do not use Niagara's distance-facing bounds or raw SubUV index.
            data[offset + 45] = limits.minimum();
            data[offset + 46] = limits.maximum();
            data[offset + 47] = -1;
        }
    }
}
