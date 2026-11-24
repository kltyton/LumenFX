package com.kltyton.lumenfx.effect.curve;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Closest-point search on each source interpolation segment. */
final class SplineNearest {
    record Result(float parameter, float distanceSquared) { }
    private SplineNearest() { }

    static Result constant(Vec3 a, Vec3 b) {
        float first = (float)a.lengthSqr(), second = (float)b.lengthSqr();
        return first < second ? new Result(0, first) : new Result(1, second);
    }

    static Result linear(Vec3 a, Vec3 b) {
        var direction = b.subtract(a);
        float length = (float)direction.lengthSqr();
        float alpha = length > 0 ? Math.clamp(-(float)a.dot(direction) / length, 0, 1) : 0;
        return new Result(alpha, (float)a.lerp(b, alpha).lengthSqr());
    }

    static Result cubic(Vec3 a, Vec3 arrive, Vec3 b, Vec3 leave) {
        var c2 = b.subtract(a).scale(3).subtract(arrive.scale(2)).subtract(leave);
        var c3 = a.subtract(b).scale(2).add(arrive).add(leave);
        double[] polynomial = {
                a.dot(arrive), arrive.dot(arrive) + 2 * a.dot(c2),
                3 * (c2.dot(arrive) + c3.dot(a)),
                4 * c3.dot(arrive) + 2 * c2.dot(c2), 5 * c3.dot(c2), 3 * c3.dot(c3)
        };
        float bestAlpha = 0, bestDistance = (float)a.lengthSqr();
        float end = (float)b.lengthSqr();
        if (end < bestDistance) { bestAlpha = 1; bestDistance = end; }
        for (double root : roots(polynomial, 5)) {
            float distance = (float)c3.scale(root).add(c2).scale(root).add(arrive).scale(root).add(a).lengthSqr();
            if (distance < bestDistance) { bestAlpha = (float)root; bestDistance = distance; }
        }
        return new Result(bestAlpha, bestDistance);
    }

    /** Split at derivative roots so each interval admits at most one crossing. */
    private static List<Double> roots(double[] coefficients, int degree) {
        while (degree > 0 && coefficients[degree] == 0) degree--;
        if (degree == 0) return List.of();
        if (degree == 1) {
            double root = -coefficients[0] / coefficients[1];
            return root > 0 && root < 1 ? List.of(root) : List.of();
        }
        double[] derivative = new double[degree];
        double magnitude = Math.abs(coefficients[0]);
        for (int index = 1; index <= degree; index++) {
            derivative[index - 1] = index * coefficients[index];
            magnitude += Math.abs(coefficients[index]);
        }
        var cuts = new ArrayList<>(roots(derivative, degree - 1));
        cuts.add(1.0);
        var result = new ArrayList<Double>(degree);
        double left = 0, atLeft = coefficients[0], epsilon = magnitude * 1e-13;
        for (double right : cuts) {
            double atRight = evaluate(coefficients, degree, right);
            if (Math.abs(atRight) <= epsilon && right < 1) result.add(right);
            else if (Math.abs(atLeft) > epsilon && Math.signum(atLeft) != Math.signum(atRight)) {
                double lo = left, hi = right;
                for (int iteration = 0; iteration < 48; iteration++) {
                    double mid = (lo + hi) * .5;
                    if (Math.signum(evaluate(coefficients, degree, mid)) == Math.signum(atLeft)) lo = mid;
                    else hi = mid;
                }
                result.add((lo + hi) * .5);
            }
            left = right;
            atLeft = atRight;
        }
        return result;
    }

    private static double evaluate(double[] coefficients, int degree, double value) {
        double result = coefficients[degree];
        for (int index = degree - 1; index >= 0; index--) result = result * value + coefficients[index];
        return result;
    }
}
