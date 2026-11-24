package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.ShapeArcMode;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public record UnityShapeSettings(Vec3 offset, float x, float y, float z, float w, double radiusThickness,
                                 double randomDirection, double sphericalDirection, double randomPosition,
                                 @Nullable Arc arc, @Nullable Traversal edge, @Nullable Donut donut,
                                 @Nullable Cone cone, @Nullable UnityMeshEmission mesh, Vec3 boxThickness, boolean alignToDirection,
                                 UnityShapeTexture.@Nullable Projection texture) {
    public UnityShapeSettings(Vec3 offset, float x, float y, float z, float w, double radiusThickness,
                              double randomDirection, double sphericalDirection, double randomPosition,
                              @Nullable Arc arc, @Nullable Traversal edge, @Nullable Donut donut, @Nullable Cone cone,
                              @Nullable UnityMeshEmission mesh, Vec3 boxThickness, boolean alignToDirection) {
        this(offset, x, y, z, w, radiusThickness, randomDirection, sphericalDirection, randomPosition,
                arc, edge, donut, cone, mesh, boxThickness, alignToDirection, null);
    }
    public record Traversal(ShapeArcMode mode, float spread, ScalarFunction speed) {
        public Traversal {
            if (mode == null || speed == null || !fraction(spread))
                throw new IllegalArgumentException("Invalid Unity particle shape traversal");
        }

        public float sample(float phase, int batchIndex, int batchCount, double randomValue, boolean includeEndpoint) {
            if (mode == ShapeArcMode.PingPong && spread > 0) {
                // Shuriken applies spread to the distance from this leg's starting end.
                int leg = (int) Math.floor(phase);
                float travelled = ShapeArcMode.applySpread(phase - leg, spread);
                return (leg & 1) == 0 ? travelled : 1 - travelled;
            }
            if (includeEndpoint && mode == ShapeArcMode.BurstSpread && batchCount > 1)
                return ShapeArcMode.applySpread((float) batchIndex / (batchCount - 1), spread);
            return mode.samplePhase(spread, phase, batchIndex, batchCount, randomValue);
        }
    }

    public record Arc(double radians, Traversal traversal) {
        public Arc {
            if (!Double.isFinite(radians) || Math.abs(radians) > 2 * Math.PI + 1e-6 || traversal == null)
                throw new IllegalArgumentException("Invalid Unity particle shape arc");
        }
    }

    public record Donut(double ringRadius, double tubeRadius) {
        public Donut {
            if (!Double.isFinite(ringRadius) || ringRadius < 0 || !Double.isFinite(tubeRadius) || tubeRadius < 0)
                throw new IllegalArgumentException("Invalid Unity donut radii");
        }
    }

    public record Cone(double radius, double length) {
        public Cone {
            if (!Double.isFinite(radius) || radius < 0 || !Double.isFinite(length) || length < 0)
                throw new IllegalArgumentException("Invalid Unity cone dimensions");
        }
    }

    public UnityShapeSettings {
        double norm = (double)x * x + (double)y * y + (double)z * z + (double)w * w;
        if (!Double.isFinite(norm) || norm <= 0 || !Double.isFinite(offset.x) || !Double.isFinite(offset.y)
                || !Double.isFinite(offset.z) || !fraction(radiusThickness) || !fraction(randomDirection)
                || !fraction(sphericalDirection) || !Double.isFinite(randomPosition) || randomPosition < 0
                || !fraction(boxThickness.x) || !fraction(boxThickness.y) || !fraction(boxThickness.z))
            throw new IllegalArgumentException("Invalid Unity particle shape");
    }

    private static boolean fraction(double value) {
        return value >= 0 && value <= 1;
    }
}
