package com.kltyton.lumenfx.effect.simulation.unity;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.effect.unity.UnityVelocityLimit;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Applies authored limits in the requested axis frame to the combined particle velocity. */
public final class UnityVelocityLimiter {
    private final UnityVelocityLimit settings;
    private final boolean simulationLocal;
    private final Quaterniond orientation = new Quaterniond();
    private final Quaterniond intoLimitSpace = new Quaterniond();
    private final Vector3d velocity = new Vector3d();

    public UnityVelocityLimiter(UnityVelocityLimit settings, boolean simulationLocal) {
        this.settings = settings;
        this.simulationLocal = simulationLocal;
        var space = settings.spaceTransform();
        if (space != null && space.fixed()) {
            space.rotation(orientation).normalize();
            intoLimitSpace.set(orientation).conjugate();
        }
    }

    public void pose(Pose pose) {
        if (!usesRotation()) return;
        var space = settings.spaceTransform();
        if (space != null && space.fixed()) return;
        orientation.set(pose.x(), pose.y(), pose.z(), pose.w()).normalize();
        if (simulationLocal) orientation.conjugate();
        if (space != null) orientation.mul(space.rotation());
        intoLimitSpace.set(orientation).conjugate();
    }

    public Vector3d apply(double x, double y, double z, double age, double size, double random, double delta) {
        return apply(x, y, z, 0, 0, 0, age, size, random, delta);
    }

    public Vector3d apply(double baseX, double baseY, double baseZ, double extraX, double extraY, double extraZ,
                          double age, double size, double random, double delta) {
        velocity.set(baseX + extraX, baseY + extraY, baseZ + extraZ);
        double speed = velocity.length();
        if (speed == 0) return velocity;
        double drag = Math.max(0, settings.drag().sample(age, random));
        if (drag > 0) {
            double dragForce = drag;
            if (settings.multiplyBySize()) dragForce *= Math.PI * (size * 0.5) * (size * 0.5);
            if (settings.multiplyByVelocity()) dragForce *= speed * speed;
            double afterDrag = Math.max(0.0, speed - dragForce * delta);
            velocity.mul(afterDrag / speed);
            speed = afterDrag;
            if (speed == 0) return velocity;
        }
        double rawDampen = settings.dampen();
        if (rawDampen == 0) return velocity;
        double dampen = rawDampen >= 1.0 ? 1.0 : 1.0 - Math.pow(1.0 - rawDampen, delta * 30.0);
        if (settings.separateAxes()) {
            boolean worldLimitInLocalSim = simulationLocal && !settings.localSpace()
                    && (settings.spaceTransform() == null || !settings.spaceTransform().fixed());
            if (worldLimitInLocalSim) {
                // Unity LimitVelocityModule (Local sim + World separate-axis limit) transforms combined velocity by worldToLocal,
                // clamps per-axis, subtracts local animated velocity, and transforms back by localToWorld.
                orientation.transform(velocity);
                var limits = settings.limits();
                velocity.set(damp(velocity.x, limits.get(0).sample(age, random), dampen) - extraX,
                        damp(velocity.y, limits.get(1).sample(age, random), dampen) - extraY,
                        damp(velocity.z, limits.get(2).sample(age, random), dampen) - extraZ);
                intoLimitSpace.transform(velocity);
                velocity.add(extraX, extraY, extraZ);
            } else {
                if (usesRotation()) intoLimitSpace.transform(velocity);
                var limits = settings.limits();
                velocity.set(damp(velocity.x, limits.get(0).sample(age, random), dampen),
                        damp(velocity.y, limits.get(1).sample(age, random), dampen),
                        damp(velocity.z, limits.get(2).sample(age, random), dampen));
                if (usesRotation()) orientation.transform(velocity);
            }
        } else {
            speed = velocity.length();
            if (speed > 0) velocity.mul(damp(speed, settings.limits().getFirst().sample(age, random), dampen) / speed);
        }
        return velocity;
    }

    private boolean usesRotation() {
        return settings.separateAxes() && (settings.spaceTransform() != null || settings.localSpace() != simulationLocal);
    }

    private static double damp(double value, double limit, double dampen) {
        double speed = Math.abs(value);
        double excess = Math.max(0, speed - Math.max(0, limit));
        return Math.copySign(speed - excess * dampen, value);
    }
}
