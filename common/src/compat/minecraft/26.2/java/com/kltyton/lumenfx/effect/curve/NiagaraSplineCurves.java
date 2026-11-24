package com.kltyton.lumenfx.effect.curve;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

/** Versioned spline component data in its local Unreal axes, with the source distance-to-key table. */
public record NiagaraSplineCurves(List<VectorKey> positions, List<VectorKey> scales, List<RotationKey> rotations,
                                 List<DistanceKey> reparameterization, float loopKeyOffset) {
    public enum Interpolation { CONSTANT, LINEAR, CUBIC }
    public record VectorKey(float time, Vec3 value, Vec3 arrive, Vec3 leave, Interpolation interpolation) {
        public VectorKey {
            Objects.requireNonNull(value); Objects.requireNonNull(arrive); Objects.requireNonNull(leave);
            Objects.requireNonNull(interpolation);
        }
    }
    public record Quaternion(double x, double y, double z, double w) {
        public Quaterniond value() { return new Quaterniond(x, y, z, w); }
    }
    public record RotationKey(float time, Quaternion value, Quaternion arrive, Quaternion leave, Interpolation interpolation) {
        public RotationKey {
            Objects.requireNonNull(value); Objects.requireNonNull(arrive); Objects.requireNonNull(leave);
            Objects.requireNonNull(interpolation);
        }
    }
    public record DistanceKey(float distance, float time) {
        public DistanceKey {
            if (!Float.isFinite(distance) || !Float.isFinite(time) || distance < 0)
                throw new IllegalArgumentException("Invalid Niagara spline reparameterization key");
        }
    }
    public static final NiagaraSplineCurves EMPTY = new NiagaraSplineCurves(List.of(), List.of(), List.of(), List.of(), 0);

    public NiagaraSplineCurves {
        positions = ordered(positions, VectorKey::time, false);
        scales = ordered(scales, VectorKey::time, false);
        rotations = ordered(rotations, RotationKey::time, false);
        reparameterization = ordered(reparameterization, DistanceKey::distance, true);
        if (!Float.isFinite(loopKeyOffset) || loopKeyOffset < 0) throw new IllegalArgumentException("Invalid spline loop offset");
    }

    public float length() { return reparameterization.isEmpty() ? 0 : reparameterization.getLast().distance(); }
    public float finalKeyTime() { return positions.isEmpty() ? 1 : positions.getLast().time(); }

    /** Rebuilds the component distance table from its resolved tangents and component scale. */
    public NiagaraSplineCurves withComponentDistanceTable(int stepsPerSegment, Vec3 componentScale) {
        if (stepsPerSegment <= 0) throw new IllegalArgumentException("Invalid spline reparameterization steps");
        Objects.requireNonNull(componentScale);
        int segments = Math.max(0, positions.size() - (loopKeyOffset > 0 ? 0 : 1));
        var table = new ArrayList<DistanceKey>(Math.addExact(Math.multiplyExact(segments, stepsPerSegment), 1));
        float distance = 0;
        for (int index = 0; index < segments; index++) {
            var a = positions.get(index);
            var b = positions.get((index + 1) % positions.size());
            for (int step = 0; step < stepsPerSegment; step++) {
                float alpha = (float)step / stepsPerSegment;
                float length = step == 0 ? 0 : SplineSegmentLength.integrate(a, b, alpha, componentScale);
                table.add(new DistanceKey(distance + length, index + alpha));
            }
            distance += SplineSegmentLength.integrate(a, b, 1, componentScale);
        }
        table.add(new DistanceKey(distance, segments));
        return new NiagaraSplineCurves(positions, scales, rotations, table, loopKeyOffset);
    }

    public float timeAtDistance(float distance) {
        if (reparameterization.isEmpty() || Float.isNaN(distance)) return 0;
        int index = lower(reparameterization, DistanceKey::distance, distance);
        if (index < 0) return reparameterization.getFirst().time();
        var a = reparameterization.get(index);
        if (index == reparameterization.size() - 1) return a.time();
        var b = reparameterization.get(index + 1);
        float width = b.distance() - a.distance();
        return width > 0 ? a.time() + (b.time() - a.time()) * ((distance - a.distance()) / width) : a.time();
    }

    public Vec3 position(float time) { return vector(positions, time, false); }
    public Vec3 scale(float time) { return vector(scales, time, false); }
    public Vec3 tangent(float time) { return vector(positions, time, true); }

    private Vec3 vector(List<VectorKey> keys, float time, boolean derivative) {
        if (keys.isEmpty() || Float.isNaN(time)) return Vec3.ZERO;
        int index = lower(keys, VectorKey::time, time);
        if (index < 0) return derivative ? keys.getFirst().leave() : keys.getFirst().value();
        var a = keys.get(index);
        boolean last = index == keys.size() - 1;
        if (last && loopKeyOffset == 0) return derivative ? a.arrive() : a.value();
        var b = last ? keys.getFirst() : keys.get(index + 1);
        float width = last ? loopKeyOffset : b.time() - a.time();
        if (last && time >= a.time() + width) return derivative ? b.arrive() : b.value();
        float alpha = (time - a.time()) / width;
        return switch (a.interpolation()) {
            case CONSTANT -> derivative ? Vec3.ZERO : a.value();
            case LINEAR -> derivative ? b.value().subtract(a.value()).scale(1 / width) : a.value().lerp(b.value(), alpha);
            case CUBIC -> cubic(a, b, width, alpha, derivative);
        };
    }

    private static Vec3 cubic(VectorKey a, VectorKey b, float width, double t, boolean derivative) {
        var t0 = a.leave().scale(width);
        var t1 = b.arrive().scale(width);
        var c2 = b.value().subtract(a.value()).scale(3).subtract(t0.scale(2)).subtract(t1);
        var c3 = a.value().subtract(b.value()).scale(2).add(t0).add(t1);
        if (derivative) return c3.scale(3 * t).add(c2.scale(2)).scale(t).add(t0).scale(1 / width);
        return c3.scale(t).add(c2).scale(t).add(t0).scale(t).add(a.value());
    }

    public Quaterniond rotation(float time) {
        if (rotations.isEmpty() || Float.isNaN(time)) return new Quaterniond();
        int index = lower(rotations, RotationKey::time, time);
        if (index < 0) return normalize(rotations.getFirst().value().value());
        var a = rotations.get(index);
        boolean last = index == rotations.size() - 1;
        if (last && loopKeyOffset == 0) return normalize(a.value().value());
        var b = last ? rotations.getFirst() : rotations.get(index + 1);
        float width = last ? loopKeyOffset : b.time() - a.time();
        if (last && time >= a.time() + width) return normalize(b.value().value());
        double alpha = (time - a.time()) / width;
        var first = a.value().value();
        var second = b.value().value();
        return normalize(switch (a.interpolation()) {
            case CONSTANT -> first;
            case LINEAR -> first.slerp(second, alpha);
            case CUBIC -> fullPath(first.slerp(second, alpha),
                    fullPath(scaled(a.leave(), width), scaled(b.arrive(), width), alpha),
                    2 * alpha * (1 - alpha));
        });
    }

    private static Quaterniond scaled(Quaternion value, float width) {
        return new Quaterniond(value.x() * width, value.y() * width, value.z() * width, value.w() * width);
    }

    private static Quaterniond fullPath(Quaterniond a, Quaterniond b, double alpha) {
        double angle = Math.acos(Math.clamp(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w, -1, 1));
        if (Math.abs(angle) < .0001) return a;
        double sine = Math.sin(angle);
        double first = Math.sin((1 - alpha) * angle) / sine, second = Math.sin(alpha * angle) / sine;
        return a.set(first * a.x + second * b.x, first * a.y + second * b.y,
                first * a.z + second * b.z, first * a.w + second * b.w);
    }

    private static Quaterniond normalize(Quaterniond value) {
        return value.lengthSquared() >= 1e-8 ? value.normalize() : value.identity();
    }

    public float nearestPositionTime(Vec3 point) {
        if (positions.isEmpty()) return 0;
        if (positions.size() == 1) return positions.getFirst().time();
        int segments = positions.size() - (loopKeyOffset > 0 ? 0 : 1);
        float bestDistance = Float.POSITIVE_INFINITY, bestTime = positions.getFirst().time();
        for (int index = 0; index < segments; index++) {
            var a = positions.get(index);
            boolean last = index == positions.size() - 1;
            var b = last ? positions.getFirst() : positions.get(index + 1);
            float width = last ? loopKeyOffset : b.time() - a.time();
            var start = a.value().subtract(point);
            var end = b.value().subtract(point);
            var closest = switch (a.interpolation()) {
                case CONSTANT -> SplineNearest.constant(start, end);
                case LINEAR -> SplineNearest.linear(start, end);
                case CUBIC -> SplineNearest.cubic(start, a.leave().scale(width), end, b.arrive().scale(width));
            };
            if (closest.distanceSquared() < bestDistance) {
                bestDistance = closest.distanceSquared();
                bestTime = closest.parameter() * width + a.time();
            }
        }
        return bestTime;
    }

    private static <T> List<T> ordered(List<T> source, ToDoubleFunction<T> key, boolean allowRepeated) {
        var keys = List.copyOf(source);
        double previous = Double.NEGATIVE_INFINITY;
        for (var value : keys) {
            double current = key.applyAsDouble(value);
            if (!Double.isFinite(current) || current < previous || !allowRepeated && current == previous)
                throw new IllegalArgumentException("Unordered Niagara spline keys");
            previous = current;
        }
        return keys;
    }

    private static <T> int lower(List<T> values, ToDoubleFunction<T> key, float input) {
        int low = 0, high = values.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (input < key.applyAsDouble(values.get(middle))) high = middle;
            else low = middle + 1;
        }
        return low - 1;
    }
}
