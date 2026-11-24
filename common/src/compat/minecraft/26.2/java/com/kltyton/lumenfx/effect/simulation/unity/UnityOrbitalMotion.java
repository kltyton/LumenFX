package com.kltyton.lumenfx.effect.simulation.unity;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.effect.unity.UnitySpaceMotion.Orbit;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Orbit and radial terms share the emitter's motion step, limiter and collision response. */
public final class UnityOrbitalMotion {
    private final Orbit settings;
    private final boolean local;
    private final Quaterniond rotation = new Quaterniond();
    private final Vector3d axis = new Vector3d();
    private final Vector3d offset = new Vector3d();
    private final Vector3d movement = new Vector3d();
    private Pose pose = Pose.at(Vec3.ZERO);

    public UnityOrbitalMotion(Orbit settings, boolean local) {
        this.settings = settings;
        this.local = local;
    }

    public void pose(Pose value) {
        pose = value;
        if (!local) rotation.set(value.x(), value.y(), value.z(), value.w()).normalize();
    }

    /** At birth (step zero) returns the instantaneous velocity; otherwise returns average step velocity. */
    public Vector3d velocity(double x, double y, double z, double age, double random, double step) {
        axis.set(settings.angular().get(0).sample(age, random), settings.angular().get(1).sample(age, random),
                settings.angular().get(2).sample(age, random));
        offset.set(settings.offset().get(0).sample(age, random), settings.offset().get(1).sample(age, random),
                settings.offset().get(2).sample(age, random));
        if (!local) {
            rotation.transform(axis);
            rotation.transform(offset.mul(pose.scaleX(), pose.scaleY(), pose.scaleZ()));
            offset.add(pose.position().x, pose.position().y, pose.position().z);
        }
        movement.set(x - offset.x, y - offset.y, z - offset.z);
        double radius = movement.length();
        if (radius < 1e-9) return movement.zero();
        double radial = settings.radial().sample(age, random);
        if (step == 0) return axis.cross(movement, axis).add(movement.mul(radial / radius));

        double speed = axis.length();
        if (speed != 0) {
            // The chord of the rotation preserves radius during pure orbit across repeated steps.
            axis.div(speed);
            movement.rotateAxis(speed * step, axis.x, axis.y, axis.z);
        }
        movement.mul((radius + radial * step) / radius);
        return movement.sub(x - offset.x, y - offset.y, z - offset.z).div(step);
    }
}
