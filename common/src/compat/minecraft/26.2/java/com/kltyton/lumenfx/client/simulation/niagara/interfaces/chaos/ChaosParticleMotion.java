package com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos;

import com.kltyton.lumenfx.effect.niagara.physics.ChaosDestructionDefinition.Motion;
import com.kltyton.lumenfx.effect.niagara.physics.ChaosDestructionDefinition.Range;
import com.kltyton.lumenfx.effect.niagara.physics.ChaosDestructionDefinition.Velocity;
import com.kltyton.lumenfx.math.WindowsSourceRandom;
import net.minecraft.world.phys.Vec3;

final class ChaosParticleMotion {
    private ChaosParticleMotion() { }

    static Vec3 position(Vec3 incoming, Range range, WindowsSourceRandom random) {
        double minimum = Math.min(range.min(), range.max());
        double maximum = Math.max(range.min(), range.max());
        return incoming.add(signedOffset(minimum, maximum, random), signedOffset(minimum, maximum, random),
                signedOffset(minimum, maximum, random));
    }

    static Vec3 velocity(Vec3 inherited, Vec3 normal, boolean collision, Motion settings, WindowsSourceRandom random) {
        Vec3 low = settings.offsetMin();
        Vec3 high = settings.offsetMax();
        Vec3 offset = new Vec3(between(low.x, high.x, random), between(low.y, high.y, random), between(low.z, high.z, random));
        Vec3 added = Vec3.ZERO;
        if (settings.model() != Velocity.COLLISION_CONE || collision) {
            Vec3 direction = settings.model() == Velocity.COLLISION_CONE
                    ? cone(normal, (float) Math.toRadians(settings.spreadDegrees()), random) : distribution(random);
            Range magnitude = settings.randomVelocity();
            added = direction.scale(random.range(magnitude.min(), Math.max(magnitude.min(), magnitude.max())));
            if (settings.model() == Velocity.STREAMERS && random.unit() < 0.2) added = added.scale(1.25);
        }
        Vec3 result = inherited.scale(settings.inheritedVelocity()).add(added).add(offset);
        double lengthSquared = result.lengthSqr();
        Range limit = settings.finalVelocity();
        if (limit.min() >= 0 && lengthSquared < limit.min() * limit.min()) return normal(result).scale(limit.min());
        if (limit.max() >= 0 && lengthSquared > limit.max() * limit.max()) return normal(result).scale(limit.max());
        return result;
    }

    private static double between(double a, double b, WindowsSourceRandom random) {
        return random.range(Math.min(a, b), Math.max(a, b));
    }

    private static float signedOffset(double minimum, double maximum, WindowsSourceRandom random) {
        int sign = random.range(0, 1) * 2 - 1;
        return (float) (sign * random.range(minimum, maximum));
    }

    private static Vec3 distribution(WindowsSourceRandom random) {
        // The saved Windows target evaluates the vector constructor's arguments from right to left.
        float z = -1 + 2 * random.unit();
        float y = -1 + 2 * random.unit();
        float x = -1 + 2 * random.unit();
        return normal(new Vec3(x, y, z));
    }

    private static Vec3 cone(Vec3 direction, float halfAngle, WindowsSourceRandom random) {
        if (halfAngle <= 0) return normal(direction);
        float azimuth = (2 * (float) Math.PI) * random.unit();
        float polar = (float) Math.acos(2 * random.unit() - 1) % halfAngle;
        double yaw = Math.atan2(direction.y, direction.x);
        double pitch = Math.atan2(direction.z, Math.hypot(direction.x, direction.y));
        Vec3 axis = new Vec3(Math.cos(pitch) * Math.cos(yaw), Math.cos(pitch) * Math.sin(yaw), Math.sin(pitch));
        Vec3 tangent = new Vec3(-Math.sin(yaw), Math.cos(yaw), 0);
        return normal(rotate(rotate(direction, tangent, polar), axis, azimuth));
    }

    private static Vec3 rotate(Vec3 value, Vec3 axis, double angle) {
        double cosine = Math.cos(angle);
        return value.scale(cosine).add(axis.cross(value).scale(Math.sin(angle)))
                .add(axis.scale(axis.dot(value) * (1 - cosine)));
    }

    private static Vec3 normal(Vec3 value) {
        double squared = value.lengthSqr();
        return squared >= 1.0e-8 ? value.scale(1 / Math.sqrt(squared)) : Vec3.ZERO;
    }
}
