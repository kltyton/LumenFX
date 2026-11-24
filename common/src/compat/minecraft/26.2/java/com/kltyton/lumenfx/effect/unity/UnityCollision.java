package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One Shuriken collision response, with authored planes or Minecraft world geometry as its source. */
public record UnityCollision(List<Plane> planes, @Nullable World world, ScalarFunction dampen, ScalarFunction bounce,
                             ScalarFunction lifetimeLoss, double minimumSpeed, double maximumSpeed, double radiusScale,
                             boolean sendCollisionMessages) {
    public UnityCollision(List<Plane> planes, @Nullable World world, ScalarFunction dampen, ScalarFunction bounce,
                          ScalarFunction lifetimeLoss, double minimumSpeed, double maximumSpeed, double radiusScale) {
        this(planes, world, dampen, bounce, lifetimeLoss, minimumSpeed, maximumSpeed, radiusScale, false);
    }

    public enum Quality { HIGH, MEDIUM, LOW }

    public enum Plane2D {
        NONE(-1),
        /** Unity XY mapped to host XY (free host Z axis). */
        XY(2),
        /** ConeVolume-rotated Unity XY mapped to host XZ (free host Y axis). */
        XZ(1),
        /** Host YZ plane (free host X axis). */
        YZ(0);

        private final int freeAxis;
        Plane2D(int freeAxis) { this.freeAxis = freeAxis; }
        public int freeAxis() { return freeAxis; }
        public boolean is2d() { return freeAxis >= 0; }
    }

    public record World(boolean dynamicColliders, int maxDynamicShapes, int layerMask, Quality quality, Plane2D plane2d,
                        double voxelSize, double colliderForce, boolean multiplyForceByCollisionAngle,
                        boolean multiplyForceByParticleSpeed, boolean multiplyForceByParticleSize) {
        public static final double DEFAULT_VOXEL_SIZE = 0.5;

        public World(boolean dynamicColliders, int maxDynamicShapes) {
            this(dynamicColliders, maxDynamicShapes, -1, Quality.HIGH, Plane2D.NONE);
        }
        public World(boolean dynamicColliders, int maxDynamicShapes, int layerMask) {
            this(dynamicColliders, maxDynamicShapes, layerMask, Quality.HIGH, Plane2D.NONE);
        }
        public World(boolean dynamicColliders, int maxDynamicShapes, int layerMask, Quality quality, boolean mode2d) {
            this(dynamicColliders, maxDynamicShapes, layerMask, quality, mode2d ? Plane2D.XY : Plane2D.NONE);
        }
        public World(boolean dynamicColliders, int maxDynamicShapes, int layerMask, Quality quality, Plane2D plane2d) {
            this(dynamicColliders, maxDynamicShapes, layerMask, quality, plane2d, DEFAULT_VOXEL_SIZE, 0.0, false, false, false);
        }
        public World(boolean dynamicColliders, int maxDynamicShapes, int layerMask, Quality quality, Plane2D plane2d, double voxelSize) {
            this(dynamicColliders, maxDynamicShapes, layerMask, quality, plane2d, voxelSize, 0.0, false, false, false);
        }

        public boolean mode2d() { return plane2d.is2d(); }

        public World {
            Objects.requireNonNull(quality, "quality");
            Objects.requireNonNull(plane2d, "plane2d");
            if (maxDynamicShapes < 1) throw new IllegalArgumentException("Invalid Unity world collision shape limit");
            if (!Double.isFinite(voxelSize) || voxelSize <= 0)
                throw new IllegalArgumentException("Invalid Unity world collision voxel size");
            if (!Double.isFinite(colliderForce))
                throw new IllegalArgumentException("Invalid Unity world collision collider force");
        }
    }
    public record Plane(Vec3 point, Vec3 normal, int slot) {
        public Plane(Vec3 point, Vec3 normal) { this(point, normal, -1); }

        public Plane {
            if (slot < -1 || !Double.isFinite(point.lengthSqr()) || !Double.isFinite(normal.lengthSqr())
                    || normal.lengthSqr() < 1e-8)
                throw new IllegalArgumentException("Invalid Unity collision plane");
            normal = normal.normalize();
        }
    }

    public UnityCollision {
        planes = List.copyOf(planes);
        if (planes.isEmpty() != (world != null) || !Double.isFinite(minimumSpeed) || minimumSpeed < 0
                || !Double.isFinite(maximumSpeed) || maximumSpeed < minimumSpeed
                || !Double.isFinite(radiusScale) || radiusScale < 0)
            throw new IllegalArgumentException("Invalid Unity particle collision settings");
    }
}
