package com.kltyton.lumenfx.client.render.unity;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.simulation.EmitterInstance.Snapshot;
import com.kltyton.lumenfx.effect.unity.UnityBillboard.Alignment;
import com.kltyton.lumenfx.effect.unity.UnityMeshMotion;
import com.lowdragmc.photon.client.gameobject.emitter.particle.FacingOrientationMath;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Converts a Shuriken particle into the shared static mesh instance transform. */
public final class UnityMeshInstances {
    private final Quaternionf owner = new Quaternionf(), inverseOwner = new Quaternionf();
    private final Quaternionf facing = new Quaternionf(), rotation = new Quaternionf();
    private final Quaternionf modelForward = new Quaternionf(), modelFront = new Quaternionf();
    private final Vector3f worldUp = new Vector3f(0, 1, 0), forward = new Vector3f(), viewUp = new Vector3f(), viewForward = new Vector3f();
    private final Vector3f authoredUp = new Vector3f();
    private final double[] velocity = new double[3];

    public void prepare(Pose pose, Vec3 cameraForward, double upX, double upY, double upZ, boolean forwardToUp) {
        owner.set(pose.x(), pose.y(), pose.z(), pose.w());
        owner.conjugate(inverseOwner);
        viewForward.set((float) -cameraForward.x, (float) -cameraForward.y, (float) -cameraForward.z);
        viewUp.set((float) upX, (float) upY, (float) upZ);
        modelForward.rotationX(forwardToUp ? (float) (Math.PI * .5) : 0);
        if (!forwardToUp) modelForward.rotationY((float) Math.PI);
        // Shuriken faces the mesh's -Z side toward the view. AxisMapper maps it to +Z or -Y.
        modelFront.rotationX(forwardToUp ? (float) (-Math.PI * .5) : 0);
    }

    public void pack(UnityMeshMotion mesh, Snapshot snapshot, int row, double age, double angle,
                     double cameraX, double cameraY, double cameraZ, float[] data) {
        var definition = snapshot.simulationDefinition();
        var particle = snapshot.particles().get(row);
        var initial = Objects.requireNonNull(particle.meshBirth());
        var angular = mesh.angularVelocity();
        double x = initial.rotationX() + particle.integratedRotationX(), y = initial.rotationY() + particle.integratedRotationY();
        if (!angular.isEmpty()) {
            x += particle.spinOverLife(angular.get(0), age, snapshot.time());
            y += particle.spinOverLife(angular.get(1), age, snapshot.time());
        }
        rotation.rotationYXZ((float) y, (float) x, (float) -angle);
        float qx = rotation.x, qy = rotation.y, qz = rotation.z;
        if (mesh.forwardToUp()) rotation.set(qx, qz, -qy, rotation.w);
        else rotation.set(-qx, -qy, qz, rotation.w);

        particle.sampleVelocity(snapshot.time(), definition, velocity, 0);
        boolean local = definition.localSpace();
        var birthDirection = particle.unityBirthDirection();
        if (birthDirection != null && birthDirection.lengthSqr() > 1e-12 &&
                (mesh.alignment() == Alignment.LOCAL || mesh.alignment() == Alignment.WORLD)) {
            forward.set((float)birthDirection.x, (float)birthDirection.y, (float)birthDirection.z);
            authoredUp.set(0, mesh.forwardToUp() ? 0 : 1, mesh.forwardToUp() ? -1 : 0);
            if (!local && mesh.alignment() == Alignment.LOCAL) owner.transform(authoredUp);
            else if (local && mesh.alignment() == Alignment.WORLD) inverseOwner.transform(authoredUp);
            FacingOrientationMath.alignForward(forward.normalize(), authoredUp, facing);
            rotation.premul(facing.mul(modelForward));
        } else switch (mesh.alignment()) {
            case LOCAL -> {
                if (!local) rotation.premul(owner);
            }
            case WORLD -> {
                if (local) rotation.premul(inverseOwner);
            }
            case VIEW, FACING, VELOCITY -> {
                Vector3f up = worldUp;
                switch (mesh.alignment()) {
                    case VIEW -> { forward.set(viewForward); up = viewUp; }
                    case FACING -> forward.set((float) -cameraX, (float) -cameraY, (float) -cameraZ);
                    case VELOCITY -> {
                        forward.set((float) velocity[0], (float) velocity[1], (float) velocity[2]);
                        if (local) {
                            var pose = snapshot.input().pose();
                            owner.transform(forward.mul(pose.scaleX(), pose.scaleY(), pose.scaleZ()));
                        }
                    }
                    default -> throw new IllegalStateException("Invalid Unity mesh facing");
                }
                if (forward.lengthSquared() < 1e-8f) forward.set(viewForward);
                FacingOrientationMath.alignForward(forward, up, facing);
                facing.mul(mesh.alignment() == Alignment.VELOCITY ? modelForward : modelFront);
                if (local) facing.premul(inverseOwner);
                rotation.premul(facing);
            }
        }

        // The shared mesh vertex program consumes XZY source axes and centimetres.
        data[3] = -rotation.x;
        data[4] = -rotation.z;
        data[5] = -rotation.y;
        data[6] = rotation.w;
        double speed = Math.sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2]);
        double sx = particle.width() * particle.sizeScale(0, age, speed, definition);
        double sy = particle.height() * particle.sizeScale(1, age, speed, definition);
        double sz = initial.depth() * particle.sizeScale(2, age, speed, definition);
        if ((initial.flipMask() & 1) != 0) sx = -sx;
        if ((initial.flipMask() & 2) != 0) sy = -sy;
        if ((initial.flipMask() & 4) != 0) sz = -sz;
        data[7] = (float) sx;
        data[8] = (float) (mesh.forwardToUp() ? sy : sz);
        data[9] = (float) (mesh.forwardToUp() ? sz : sy);
        data[11] = (float) (velocity[0] * 100);
        data[12] = (float) (velocity[2] * 100);
        data[13] = (float) (velocity[1] * 100);
    }
}
