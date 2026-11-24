package com.kltyton.lumenfx.api.client.physics;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Unreal component transform in source axes and centimeters. */
public record PhysicsFieldTransform(Vec3 position, double qx, double qy, double qz, double qw, Vec3 scale) {
    public static final PhysicsFieldTransform IDENTITY = new PhysicsFieldTransform(Vec3.ZERO, 0, 0, 0, 1, new Vec3(1, 1, 1));

    public PhysicsFieldTransform {
        Objects.requireNonNull(position);
        Objects.requireNonNull(scale);
    }

    Vec3 inversePosition(Vec3 worldPosition) {
        Vec3 relative = worldPosition.subtract(position);
        var local = new Quaterniond(qx, qy, qz, qw).conjugate().transform(new Vector3d(relative.x, relative.y, relative.z));
        return new Vec3(local.x * reciprocal(scale.x), local.y * reciprocal(scale.y), local.z * reciprocal(scale.z));
    }

    private static double reciprocal(double value) { return Math.abs(value) <= 1.0e-8f ? 0 : 1 / value; }
}
