package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldConfig.Shape;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public record UnityExternalForces(ScalarFunction multiplier, List<Field> fields, List<WindZone> windZones) {
    public UnityExternalForces(ScalarFunction multiplier, List<Field> fields) {
        this(multiplier, fields, List.of());
    }

    public UnityExternalForces {
        fields = List.copyOf(fields);
        windZones = List.copyOf(windZones);
    }

    /** Creates an immutable snapshot of dynamic force-field states in deterministic key order. */
    public static Map<String, FieldState> copyFieldStates(Map<String, FieldState> states) {
        Objects.requireNonNull(states, "states");
        if (states.isEmpty()) return Map.of();
        var sorted = new TreeMap<String, FieldState>();
        for (var entry : states.entrySet()) {
            sorted.put(Objects.requireNonNull(entry.getKey(), "force field key"),
                    Objects.requireNonNull(entry.getValue(), "force field state"));
        }
        return Collections.unmodifiableMap(sorted);
    }

    /** Full transform from the field's axes into the deployed effect's root coordinates. */
    public record Basis(Vec3 center, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis) {
        public Basis {
            if (!validBasis(center, xAxis, yAxis, zAxis)) throw new IllegalArgumentException("Invalid Unity force field basis");
        }
    }

    public record WindZone(Mode mode, Vec3 center, Vec3 direction, float radius, float main, float turbulence,
                           float pulseMagnitude, float pulseFrequency, @Nullable String sourceId, boolean enabled) {
        public enum Mode { DIRECTIONAL, SPHERICAL }

        public WindZone {
            if (mode == null || center == null || direction == null || !finite(center) || !finite(direction)
                    || direction.lengthSqr() <= 1e-12 || !Float.isFinite(radius) || radius < 0 || !Float.isFinite(main)
                    || !Float.isFinite(turbulence) || !Float.isFinite(pulseMagnitude) || !Float.isFinite(pulseFrequency))
                throw new IllegalArgumentException("Invalid Unity wind zone");
            direction = direction.normalize();
        }
    }

    public record FieldState(boolean enabled, @Nullable Basis basis, @Nullable Field dynamicField,
                             @Nullable WindZone dynamicWindZone) {
        public FieldState(boolean enabled, @Nullable Basis basis) {
            this(enabled, basis, null, null);
        }

        public FieldState {
            if (dynamicField != null && dynamicWindZone != null)
                throw new IllegalArgumentException("A force field override cannot provide both a field and a wind zone");
        }

        public static FieldState dynamic(Field field) { return new FieldState(true, null, field, null); }
        public static FieldState dynamic(Field field, @Nullable Basis basis) { return new FieldState(true, basis, field, null); }
        public static FieldState dynamic(WindZone windZone) { return new FieldState(true, null, null, windZone); }
        public static FieldState dynamic(WindZone windZone, @Nullable Basis basis) { return new FieldState(true, basis, null, windZone); }
    }

    public record Field(Shape shape, Vec3 center, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis, float start, float end,
                        float length, List<ScalarFunction> direction, ScalarFunction gravity, float gravityFocus,
                        ScalarFunction rotationSpeed, ScalarFunction rotationAttraction, float randomX, float randomZ,
                        ScalarFunction drag, boolean dragBySize, boolean dragBySpeed, @Nullable VectorFieldVolume volume,
                        ScalarFunction vectorSpeed, ScalarFunction vectorAttraction, @Nullable String sourceId, boolean enabled) {
        public Field(Shape shape, Vec3 center, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis, float start, float end,
                     float length, List<ScalarFunction> direction, ScalarFunction gravity, float gravityFocus,
                     ScalarFunction rotationSpeed, ScalarFunction rotationAttraction, float randomX, float randomZ,
                     ScalarFunction drag, boolean dragBySize, boolean dragBySpeed, @Nullable VectorFieldVolume volume,
                     ScalarFunction vectorSpeed, ScalarFunction vectorAttraction) {
            this(shape, center, xAxis, yAxis, zAxis, start, end, length, direction, gravity, gravityFocus, rotationSpeed,
                    rotationAttraction, randomX, randomZ, drag, dragBySize, dragBySpeed, volume, vectorSpeed, vectorAttraction,
                    null, true);
        }

        public Field {
            direction = List.copyOf(direction);
            if (direction.size() != 3 || !validBasis(center, xAxis, yAxis, zAxis) || !Float.isFinite(start) || start < 0
                    || !Float.isFinite(end) || end < 0 || !Float.isFinite(length) || length < 0
                    || !Float.isFinite(gravityFocus) || gravityFocus < 0 || gravityFocus > 1
                    || !Float.isFinite(randomX) || randomX < 0 || !Float.isFinite(randomZ) || randomZ < 0)
                throw new IllegalArgumentException("Invalid Unity external force field");
        }

    }

    private static boolean validBasis(Vec3 center, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis) {
        double determinant = xAxis.dot(yAxis.cross(zAxis));
        return finite(center) && finite(xAxis) && finite(yAxis) && finite(zAxis)
                && Double.isFinite(determinant) && Math.abs(determinant) >= 1e-9;
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
