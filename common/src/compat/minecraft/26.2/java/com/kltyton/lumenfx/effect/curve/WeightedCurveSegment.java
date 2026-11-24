package com.kltyton.lumenfx.effect.curve;

import com.kltyton.lumenfx.effect.curve.FloatCurve.Key;

/** Unity tangent weights are horizontal fractions of the key interval. */
final class WeightedCurveSegment {
    private final double x1, x2, x3;
    private final double y0, y1, y2, y3;
    private final double i1, i2, i3, i4, i5, i6;

    WeightedCurveSegment(Key a, Key b) {
        double first = a.leaveWeight() == null ? 1d / 3 : a.leaveWeight();
        double last = 1 - (b.arriveWeight() == null ? 1d / 3 : b.arriveWeight());
        x1 = 3 * first;
        x2 = 3 * (last - 2 * first);
        x3 = 1 - x1 - x2;
        double span = b.time() - a.time();
        double left = a.leaveTangent() * span * first;
        double right = b.value() - b.arriveTangent() * span * (1 - last) - a.value();
        y0 = a.value();
        y1 = 3 * left;
        y2 = 3 * (right - 2 * left);
        y3 = b.value() - y0 - y1 - y2;
        i1 = y0 * x1;
        i2 = (2 * y0 * x2 + y1 * x1) / 2;
        i3 = (3 * y0 * x3 + 2 * y1 * x2 + y2 * x1) / 3;
        i4 = (3 * y1 * x3 + 2 * y2 * x2 + y3 * x1) / 4;
        i5 = (3 * y2 * x3 + 2 * y3 * x2) / 5;
        i6 = y3 * x3 / 2;
    }

    double sample(double time) {
        double t = parameter(time);
        return ((y3 * t + y2) * t + y1) * t + y0;
    }

    double primitive(double time) {
        double t = parameter(time);
        return (((((i6 * t + i5) * t + i4) * t + i3) * t + i2) * t + i1) * t;
    }

    private double parameter(double time) {
        if (time <= 0 || time >= 1) return Math.clamp(time, 0, 1);
        double lower = 0, upper = 1;
        // The x coordinates stay in [0,1]; the cubic x(t) is monotone for these authored weights.
        for (int i = 0; i < 48; i++) {
            double t = (lower + upper) * .5;
            double x = ((x3 * t + x2) * t + x1) * t;
            if (x == time) return t;
            if (x < time) lower = t;
            else upper = t;
        }
        return (lower + upper) * .5;
    }
}
