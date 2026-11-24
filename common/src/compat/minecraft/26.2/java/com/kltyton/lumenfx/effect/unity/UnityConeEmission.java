package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.unity.UnityShapeSettings.Cone;
import java.util.SplittableRandom;
import net.minecraft.world.phys.Vec3;

/** Cone birth positions and directions in the unrotated particle shape frame. */
public final class UnityConeEmission {
    public record Sample(Vec3 position, Vec3 direction, double outerRadius) { }

    private UnityConeEmission() { }

    public static Sample sample(Cone cone, Vec3 scale, double angle, double thickness, double azimuth, SplittableRandom random) {
        double radius = cone.radius(), length = cone.length();
        double cos = Math.cos(azimuth), sin = Math.sin(azimuth);
        boolean flat = angle == Math.PI * .5;
        double travel = length == 0 ? 0 : length * random.nextDouble();
        // Cone thickness is a fraction of the base disk's area.
        double radialFraction = thickness == 0 ? 1 : Math.sqrt(1 - thickness + thickness * random.nextDouble());
        double radialDirection = flat ? 1 : radialFraction * Math.sin(angle);
        Vec3 ray = new Vec3(radialDirection * cos, flat ? 0 : Math.cos(angle), radialDirection * sin).normalize();
        double baseDistance = radius * radialFraction;
        // The source length is measured along this ray in shape space.
        Vec3 position = new Vec3(baseDistance * cos, 0, baseDistance * sin).add(ray.scale(travel)).multiply(scale);
        double outer = radius + (flat ? travel : travel * ray.y * Math.tan(angle));
        return new Sample(position, ray.multiply(scale).normalize(), outer);
    }
}
