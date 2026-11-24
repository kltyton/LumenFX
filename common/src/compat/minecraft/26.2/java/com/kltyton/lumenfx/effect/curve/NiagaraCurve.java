package com.kltyton.lumenfx.effect.curve;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Niagara curve-interface sampling, including rich-curve tangent weights and extrapolation. */
public record NiagaraCurve(List<Key> keys, Extrapolation pre, Extrapolation post, FloatCurve.@Nullable Lookup lookup) {
    public enum Weight { NONE, ARRIVE, LEAVE, BOTH }
    public enum Extrapolation { CONSTANT, LINEAR, CYCLE, CYCLE_WITH_OFFSET, OSCILLATE, NONE }
    public record Key(float time, float value, float arrive, float leave, float arriveWeight, float leaveWeight,
                      Weight weight, FloatCurve.Interpolation interpolation) { }

    public NiagaraCurve {
        keys = List.copyOf(keys);
        if (keys.isEmpty() || pre == null || post == null) throw new IllegalArgumentException("Invalid Niagara curve");
        float previous = Float.NEGATIVE_INFINITY;
        for (Key key : keys) {
            if (!Float.isFinite(key.time()) || !Float.isFinite(key.value()) || !Float.isFinite(key.arrive())
                    || !Float.isFinite(key.leave()) || !Float.isFinite(key.arriveWeight()) || !Float.isFinite(key.leaveWeight())
                    || key.time() < previous || key.weight() == null || key.interpolation() == null)
                throw new IllegalArgumentException("Invalid Niagara curve key");
            previous = key.time();
        }
    }

    public float sample(float input) {
        if (lookup != null) return lookup.sampleNiagara(input);
        int last = keys.size() - 1;
        Key firstKey = keys.getFirst(), lastKey = keys.get(last);
        float time = input, offset = 0;
        if (last > 0 && (time <= firstKey.time() || time >= lastKey.time())) {
            boolean before = time <= firstKey.time();
            Extrapolation mode = before ? pre : post;
            if (mode != Extrapolation.LINEAR && mode != Extrapolation.CONSTANT) {
                float duration = lastKey.time() - firstKey.time(), initial = time;
                int cycles = 0;
                if (time > lastKey.time()) { cycles = (int)Math.floor((lastKey.time() - time) / duration); time += duration * cycles; }
                else if (time < firstKey.time()) { cycles = (int)Math.floor((time - firstKey.time()) / duration); time -= duration * cycles; }
                if (time == lastKey.time() && initial < firstKey.time()) time = firstKey.time();
                if (time == firstKey.time() && initial > lastKey.time()) time = lastKey.time();
                cycles = Math.abs(cycles);
                if (mode == Extrapolation.CYCLE_WITH_OFFSET)
                    offset = (before ? firstKey.value() - lastKey.value() : lastKey.value() - firstKey.value()) * cycles;
                else if (mode == Extrapolation.OSCILLATE && (cycles & 1) != 0)
                    time = firstKey.time() + (lastKey.time() - time);
            }
        }
        if (last == 0) return firstKey.value() + offset;
        if (time < firstKey.time()) {
            if (pre != Extrapolation.LINEAR) return firstKey.value() + offset;
            Key next = keys.get(1);
            float delta = next.time() - firstKey.time();
            return (Math.abs(delta) <= 1.e-8f ? firstKey.value()
                    : (next.value() - firstKey.value()) / delta * (time - firstKey.time()) + firstKey.value()) + offset;
        }
        if (time > lastKey.time()) {
            if (post != Extrapolation.LINEAR) return lastKey.value() + offset;
            Key previous = keys.get(last - 1);
            float delta = previous.time() - lastKey.time();
            return (Math.abs(delta) <= 1.e-8f ? lastKey.value()
                    : (previous.value() - lastKey.value()) / delta * (time - lastKey.time()) + lastKey.value()) + offset;
        }
        int low = 0, high = last;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (keys.get(middle).time() <= time) low = middle; else high = middle;
        }
        Key a = keys.get(low), b = keys.get(high);
        float span = b.time() - a.time();
        if (!(span > 0)) return a.value() + offset;
        float alpha = (time - a.time()) / span;
        float value = switch (a.interpolation()) {
            case CONSTANT -> time < b.time() ? a.value() : b.value();
            case LINEAR -> lerp(a.value(), b.value(), alpha);
            case CUBIC -> cubic(a, b, span, alpha);
        };
        return value + offset;
    }

    private static float cubic(Key a, Key b, float span, float alpha) {
        boolean weightedLeave = a.weight() == Weight.LEAVE || a.weight() == Weight.BOTH;
        boolean weightedArrive = b.weight() == Weight.ARRIVE || b.weight() == Weight.BOTH;
        float oneThird = 1f / 3f;
        if (!weightedLeave && !weightedArrive)
            return bezier(a.value(), a.value() + (a.leave() * span) * oneThird,
                    b.value() - (b.arrive() * span) * oneThird, b.value(), alpha);
        float leaveAngle = (float)Math.atan(a.leave()), arriveAngle = (float)Math.atan(b.arrive());
        float leaveY = a.leave() * span, arriveY = b.arrive() * span;
        float leaveWeight = weightedLeave ? a.leaveWeight() : (float)Math.sqrt(span * span + leaveY * leaveY) * oneThird;
        float arriveWeight = weightedArrive ? b.arriveWeight() : (float)Math.sqrt(span * span + arriveY * arriveY) * oneThird;
        float tangentX1 = (float)((float)Math.cos(leaveAngle) * leaveWeight + (double)a.time());
        float tangentX2 = (float)(-(float)Math.cos(arriveAngle) * arriveWeight + (double)b.time());
        float x1 = (tangentX1 - a.time()) / span;
        float x2 = (tangentX2 - a.time()) / span;
        float y1 = a.value() + (float)Math.sin(leaveAngle) * leaveWeight;
        float y2 = b.value() - (float)Math.sin(arriveAngle) * arriveWeight;
        double edge0 = x1, edge1 = (double)x2 - x1, edge2 = 1d - x2;
        double difference = edge1 - edge0;
        double c3 = (edge2 - edge1) - difference, c2 = 3d * difference, c1 = 3d * edge0;
        double parameter = root(c3, c2, c1, -alpha);
        return bezier(a.value(), y1, y2, b.value(), (float)parameter);
    }

    private static double root(double c3, double c2, double c1, double c0) {
        double denominator = c3 != 0 ? c3 : 1.e-8 * 1.e-8;
        double a = c2 / denominator, b = c1 / denominator, c = c0 / denominator;
        double squareOfA = a * a;
        double p = (1d / 3d) * (-(1d / 3d) * squareOfA + b);
        double q = .5d * (((2d / 27d) * a) * squareOfA - ((1d / 3d) * a) * b + c);
        double cubeOfP = (p * p) * p;
        double d = q * q + cubeOfP;
        double[] solutions = new double[3];
        int count;
        if (Math.abs(d) <= 1.e-8) {
            if (Math.abs(q) <= 1.e-8) { solutions[0] = 0; count = 1; }
            else { double u = cubeRoot(-q); solutions[0] = 2 * u; solutions[1] = -u; count = 2; }
        } else if (d < 0) {
            double phi = (1d / 3d) * Math.acos(-q / Math.sqrt(-cubeOfP)), t = 2 * Math.sqrt(-p);
            solutions[0] = t * Math.cos(phi); solutions[1] = -t * Math.cos(phi + Math.PI / 3);
            solutions[2] = -t * Math.cos(phi - Math.PI / 3); count = 3;
        } else {
            double sqrt = Math.sqrt(d), u = cubeRoot(sqrt - q), v = -cubeRoot(sqrt + q);
            solutions[0] = u + v; count = 1;
        }
        double subtraction = (1d / 3d) * a;
        for (int i = 0; i < count; i++) solutions[i] -= subtraction;
        if (count == 1) return solutions[0];
        double chosen = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double value = solutions[i];
            if ((value > 0 || Math.abs(value) <= 1.e-8) && (value < 1 || Math.abs(value - 1) <= 1.e-8)
                    && (chosen < 0 || value > chosen)) chosen = value;
        }
        return chosen == Double.NEGATIVE_INFINITY ? 0 : chosen;
    }

    private static float bezier(float a, float b, float c, float d, float alpha) {
        float ab = lerp(a, b, alpha), bc = lerp(b, c, alpha), cd = lerp(c, d, alpha);
        return lerp(lerp(ab, bc, alpha), lerp(bc, cd, alpha), alpha);
    }
    private static double cubeRoot(double value) {
        return value > 0 ? Math.pow(value, 1d / 3d) : value < 0 ? -Math.pow(-value, 1d / 3d) : 0;
    }
    private static float lerp(float a, float b, float alpha) { return a + alpha * (b - a); }
}
