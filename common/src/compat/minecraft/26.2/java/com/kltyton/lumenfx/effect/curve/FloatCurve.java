package com.kltyton.lumenfx.effect.curve;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Piecewise curves retain the derivative units of their source keys. */
public final class FloatCurve {
    private final List<Key> keys;
    private final Lookup lookup;
    private final Wrap pre, post;
    private final WeightedCurveSegment[] weighted;
    private final double periodArea;

    public List<Key> keys() { return keys; }
    public Lookup lookup() { return lookup; }
    public enum Wrap { CLAMP, LOOP, PING_PONG }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof FloatCurve curve && keys.equals(curve.keys)
                && Objects.equals(lookup, curve.lookup) && pre == curve.pre && post == curve.post;
    }

    @Override public int hashCode() { return Objects.hash(keys, lookup, pre, post); }
    public record Lookup(float minimumTime, float inverseTimeRange, List<Float> values) {
        public Lookup {
            values = List.copyOf(values);
            if (!Float.isFinite(minimumTime) || Float.isNaN(inverseTimeRange) || inverseTimeRange <= 0
                    || !Float.isFinite(inverseTimeRange) && values.size() != 1
                    || values.isEmpty() || values.stream().anyMatch(value -> !Float.isFinite(value)))
                throw new IllegalArgumentException("Invalid baked curve table");
        }

        public float sample(float time) {
            if (values.size() == 1) return values.getFirst() + 0f;
            float last = values.size() - 1;
            float coordinate = Math.clamp((time - minimumTime) * inverseTimeRange * last, 0, last);
            return interpolate(coordinate);
        }

        public float sampleNiagara(float time) {
            float last = values.size() - 1;
            float coordinate = (time - minimumTime) * inverseTimeRange * last;
            coordinate = coordinate < 0f ? 0f : coordinate < last ? coordinate : last;
            return interpolate(coordinate);
        }

        private float interpolate(float coordinate) {
            int a = (int)coordinate, b = Math.min(a + 1, values.size() - 1);
            float fraction = coordinate - a;
            float left = values.get(a), right = values.get(b);
            return left + fraction * (right - left);
        }
    }
    public enum Interpolation { CONSTANT, LINEAR, CUBIC }
    public record Key(double time, double value, double arriveTangent, double leaveTangent,
                      Interpolation interpolation, Double arriveWeight, Double leaveWeight) {
        public Key(double time, double value, double arriveTangent, double leaveTangent, Interpolation interpolation) {
            this(time, value, arriveTangent, leaveTangent, interpolation, null, null);
        }
    }

    public FloatCurve(List<Key> keys) { this(keys, null); }
    public FloatCurve(List<Key> keys, Lookup lookup) { this(keys, lookup, Wrap.CLAMP, Wrap.CLAMP); }
    public FloatCurve(List<Key> keys, Wrap pre, Wrap post) { this(keys, null, pre, post); }

    public static FloatCurve fromLookup(float minimumTime, float inverseTimeRange, List<Float> values) {
        Lookup table = new Lookup(minimumTime, inverseTimeRange, values);
        List<Key> boundaries = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            double time = minimumTime + (values.size() == 1 ? 0 : (double)i / (values.size() - 1) / inverseTimeRange);
            boundaries.add(new Key(time, values.get(i), 0, 0, Interpolation.LINEAR));
        }
        return new FloatCurve(boundaries, table);
    }

    private FloatCurve(List<Key> keys, Lookup lookup, Wrap pre, Wrap post) {
        this.keys = List.copyOf(keys);
        this.lookup = lookup;
        this.pre = pre;
        this.post = post;
        if (keys.isEmpty()) throw new IllegalArgumentException("A curve needs at least one key");
        double previous = Double.NEGATIVE_INFINITY;
        for (Key key : keys) {
            if (!Double.isFinite(key.time()) || !Double.isFinite(key.value())
                    || !Double.isFinite(key.arriveTangent()) || !Double.isFinite(key.leaveTangent())
                    || key.time() <= previous || key.interpolation() == null
                    || !validWeight(key.arriveWeight()) || !validWeight(key.leaveWeight()))
                throw new IllegalArgumentException("Curve keys must have finite values and increasing times");
            previous = key.time();
        }
        WeightedCurveSegment[] segments = null;
        for (int i = 0; i + 1 < keys.size(); i++) {
            Key a = keys.get(i), b = keys.get(i + 1);
            if (a.interpolation() == Interpolation.CUBIC && (a.leaveWeight() != null || b.arriveWeight() != null)) {
                if (segments == null) segments = new WeightedCurveSegment[keys.size() - 1];
                segments[i] = new WeightedCurveSegment(a, b);
            }
        }
        weighted = segments;
        periodArea = pre == Wrap.CLAMP && post == Wrap.CLAMP ? 0 : integralWithin(keys.getFirst().time(), keys.getLast().time());
    }

    private static boolean validWeight(Double value) { return value == null || value >= 0 && value <= 1; }

    public static FloatCurve constant(double value) {
        return new FloatCurve(List.of(new Key(0, value, 0, 0, Interpolation.CONSTANT)));
    }

    double magnitudeBound() {
        double bound = 0;
        for (int i = 0; i < keys.size(); i++) {
            Key key = keys.get(i);
            bound = Math.max(bound, Math.abs(key.value()));
            if (key.interpolation() != Interpolation.CUBIC || i + 1 == keys.size()) continue;
            Key next = keys.get(i + 1);
            double span = next.time() - key.time();
            double leaveWeight = key.leaveWeight() == null ? 1d / 3 : key.leaveWeight();
            double arriveWeight = next.arriveWeight() == null ? 1d / 3 : next.arriveWeight();
            // A cubic cannot go beyond the extrema of its four Bézier control values.
            bound = Math.max(bound, Math.abs(key.value() + key.leaveTangent() * span * leaveWeight));
            bound = Math.max(bound, Math.abs(next.value() - next.arriveTangent() * span * arriveWeight));
        }
        return bound;
    }

    public double sample(double time) {
        if (lookup != null) return lookup.sample((float)time);
        double first = keys.getFirst().time(), last = keys.getLast().time();
        if (last > first && time < first && pre != Wrap.CLAMP) time = wrap(time, first, last, pre);
        else if (last > first && time > last && post != Wrap.CLAMP) time = wrap(time, first, last, post);
        if (time <= keys.getFirst().time()) return keys.getFirst().value();
        int end = keys.size() - 1;
        if (time >= keys.get(end).time()) return keys.get(end).value();
        int low = 0, high = end;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (keys.get(middle).time() <= time) low = middle;
            else high = middle;
        }
        Key a = keys.get(low), b = keys.get(high);
        double span = b.time() - a.time(), t = (time - a.time()) / span;
        if (weighted != null && weighted[low] != null) return weighted[low].sample(t);
        return switch (a.interpolation()) {
            case CONSTANT -> a.value();
            case LINEAR -> a.value() + (b.value() - a.value()) * t;
            case CUBIC -> {
                double t2 = t * t, t3 = t2 * t;
                yield (2 * t3 - 3 * t2 + 1) * a.value() + (t3 - 2 * t2 + t) * span * a.leaveTangent()
                        + (-2 * t3 + 3 * t2) * b.value() + (t3 - t2) * span * b.arriveTangent();
            }
        };
    }

    public double integral(double from, double to) {
        if (to < from) return -integral(to, from);
        double first = keys.getFirst().time(), last = keys.getLast().time();
        if (last == first || pre == Wrap.CLAMP && post == Wrap.CLAMP) return integralWithin(from, to);
        return extendedPrimitive(to, first, last) - extendedPrimitive(from, first, last);
    }

    private double extendedPrimitive(double time, double first, double last) {
        if (time >= first && time <= last) return integralWithin(first, time);
        Wrap mode = time < first ? pre : post;
        if (mode == Wrap.CLAMP) return time < first ? (time - first) * keys.getFirst().value()
                : periodArea + (time - last) * keys.getLast().value();
        double span = last - first;
        double period = mode == Wrap.PING_PONG ? 2 * span : span;
        double cycles = Math.floor((time - first) / period);
        double offset = (time - first) % period;
        if (offset < 0) offset += period;
        if (mode == Wrap.PING_PONG) {
            double area = cycles * periodArea * 2;
            return offset <= span ? area + integralWithin(first, first + offset)
                    : area + periodArea + integralWithin(last - (offset - span), last);
        }
        return cycles * periodArea + integralWithin(first, first + offset);
    }

    private static double wrap(double time, double first, double last, Wrap mode) {
        double span = last - first, period = mode == Wrap.PING_PONG ? 2 * span : span;
        double offset = (time - first) % period;
        if (offset < 0) offset += period;
        return offset <= span ? first + offset : last - (offset - span);
    }

    private double integralWithin(double from, double to) {
        double sum = 0;
        Key first = keys.getFirst(), last = keys.getLast();
        if (from < first.time()) {
            double end = Math.min(to, first.time());
            sum += (end - from) * first.value();
            from = end;
        }
        for (int i = 0; i + 1 < keys.size() && from < to; i++) {
            Key a = keys.get(i), b = keys.get(i + 1);
            if (from >= b.time() || to <= a.time()) continue;
            double start = Math.max(from, a.time()), end = Math.min(to, b.time());
            double span = b.time() - a.time();
            double left = (start - a.time()) / span, right = (end - a.time()) / span;
            var segment = weighted == null ? null : weighted[i];
            sum += span * (segment == null ? primitive(a, b, right) - primitive(a, b, left)
                    : segment.primitive(right) - segment.primitive(left));
            from = end;
        }
        if (from < to) sum += (to - from) * last.value();
        return sum;
    }

    private static double primitive(Key a, Key b, double t) {
        return switch (a.interpolation()) {
            case CONSTANT -> a.value() * t;
            case LINEAR -> a.value() * t + (b.value() - a.value()) * t * t * .5;
            case CUBIC -> {
                double m0 = (b.time() - a.time()) * a.leaveTangent();
                double m1 = (b.time() - a.time()) * b.arriveTangent();
                double c3 = 2 * a.value() - 2 * b.value() + m0 + m1;
                double c2 = -3 * a.value() + 3 * b.value() - 2 * m0 - m1;
                yield c3 * t * t * t * t * .25 + c2 * t * t * t / 3 + m0 * t * t * .5 + a.value() * t;
            }
        };
    }
}
