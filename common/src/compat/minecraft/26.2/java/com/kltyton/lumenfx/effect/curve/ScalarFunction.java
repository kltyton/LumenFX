package com.kltyton.lumenfx.effect.curve;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class ScalarFunction {
    private final FloatCurve minimum;
    private final FloatCurve maximum;
    private final List<ScalarFunction> factors;
    private final double[] boundaries;
    private final boolean additive;
    private final int degree;
    private static final double[] NODES = {.1834346424956498, .525532409916329, .7966664774136267, .9602898564975363};
    private static final double[] WEIGHTS = {.362683783378362, .3137066458778873, .2223810344533745, .1012285362903763};

    public ScalarFunction(FloatCurve minimum, FloatCurve maximum) {
        if (minimum == null || maximum == null) throw new IllegalArgumentException("Missing scalar curve");
        this.minimum = minimum;
        this.maximum = maximum;
        factors = List.of();
        additive = false;
        degree = minimum.keys().size() == 1 && maximum.keys().size() == 1 ? 0 : 3;
        TreeSet<Double> times = new TreeSet<>();
        minimum.keys().forEach(key -> times.add(key.time()));
        maximum.keys().forEach(key -> times.add(key.time()));
        boundaries = times.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private ScalarFunction(List<ScalarFunction> values, boolean additive) {
        List<ScalarFunction> flat = new ArrayList<>();
        for (var value : values) {
            if (value.factors.isEmpty() || value.additive != additive) flat.add(value);
            else flat.addAll(value.factors);
        }
        if (flat.isEmpty() || flat.size() > (additive ? 32 : 5)) throw new IllegalArgumentException("Invalid scalar expression arity");
        factors = List.copyOf(flat);
        this.additive = additive;
        degree = additive ? flat.stream().mapToInt(value -> value.degree).max().orElse(0) : flat.stream().mapToInt(value -> value.degree).sum();
        if (!additive && degree > 15) throw new IllegalArgumentException("Curve product exceeds polynomial degree 15");
        minimum = maximum = null;
        TreeSet<Double> times = new TreeSet<>();
        for (var value : flat) for (double boundary : value.boundaries) times.add(boundary);
        boundaries = times.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public static ScalarFunction multiply(List<ScalarFunction> factors) { return new ScalarFunction(factors, false); }
    public static ScalarFunction add(List<ScalarFunction> terms) { return new ScalarFunction(terms, true); }

    public boolean timeInvariant() {
        return degree == 0;
    }

    public static ScalarFunction constant(double value) {
        FloatCurve curve = FloatCurve.constant(value);
        return new ScalarFunction(curve, curve);
    }

    public double magnitudeBound() {
        if (factors.isEmpty()) return Math.max(minimum.magnitudeBound(), maximum.magnitudeBound());
        double bound = additive ? 0 : 1;
        for (var factor : factors) {
            double next = factor.magnitudeBound();
            bound = additive ? bound + next : bound * next;
        }
        return bound;
    }

    public double sample(double time, double random) {
        if (!factors.isEmpty()) {
            double value = additive ? 0 : 1;
            for (int i = 0; i < factors.size(); i++) {
                double next = factors.get(i).sample(time, random);
                value = additive ? value + next : value * next;
            }
            return value;
        }
        double low = minimum.sample(time);
        if (minimum == maximum) return low;
        return low + (maximum.sample(time) - low) * random;
    }

    public double integral(double from, double to, double random) {
        if (from == to) return 0;
        if (!factors.isEmpty()) {
            if (additive) {
                double sum = 0;
                for (int i = 0; i < factors.size(); i++) sum += factors.get(i).integral(from, to, random);
                return sum;
            }
            if (to < from) return -integral(to, from, random);
            double sum = 0, start = from;
            for (double boundary : boundaries) if (boundary > start && boundary < to) {
                sum += integrateProduct(start, boundary, random);
                start = boundary;
            }
            return sum + integrateProduct(start, to, random);
        }
        double low = minimum.integral(from, to);
        if (minimum == maximum) return low;
        return low + (maximum.integral(from, to) - low) * random;
    }

    private double integrateProduct(double from, double to, double random) {
        double middle = (from + to) * .5, radius = (to - from) * .5, sum = 0;
        for (int i = 0; i < NODES.length; i++)
            sum += WEIGHTS[i] * (sample(middle - radius * NODES[i], random) + sample(middle + radius * NODES[i], random));
        return radius * sum;
    }
}
