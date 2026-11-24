package com.lowdragmc.photon.client.gameobject.emitter.data;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Fractional emission for source formats that specify the exact time of each particle birth. */
public final class ContinuousEmission {
    @FunctionalInterface
    public interface RateIntegral {
        double between(double normalizedStart, double normalizedEnd);
    }

    private final double duration;
    private final boolean looping;
    private final RateIntegral rate;
    private final double constantRate;
    private final boolean distanceSharesTimeRemainder;
    private double timeRemainder;
    private double distanceRemainder;
    private double rateDistanceRemainder;

    public ContinuousEmission(double duration, boolean looping, RateIntegral rate) {
        this(duration, looping, rate, Double.NaN);
    }

    public ContinuousEmission(double duration, boolean looping, RateIntegral rate, double constantRate) {
        this(duration, looping, rate, constantRate, false);
    }

    public ContinuousEmission(double duration, boolean looping, RateIntegral rate, double constantRate,
                              boolean distanceSharesTimeRemainder) {
        this.duration = duration;
        this.looping = looping;
        this.rate = rate;
        this.constantRate = Math.max(0, constantRate);
        this.distanceSharesTimeRemainder = distanceSharesTimeRemainder;
    }

    public void reset() {
        timeRemainder = 0;
        resetDistance();
    }

    public void resetDistance() {
        distanceRemainder = 0;
        rateDistanceRemainder = 0;
    }

    /** Emits relative to the supplied origin while the caller still has particle capacity. */
    public void emitTime(double origin, double start, double end, BooleanSupplier hasCapacity, DoubleConsumer birth) {
        if (!looping) end = Math.min(end, duration);
        if (end <= start) return;
        long cycle = (long) Math.floor(start / duration);
        double position = start;
        while (position < end) {
            double cycleStart = cycle * duration;
            double nextBoundary = (cycle + 1.0) * duration;
            if (nextBoundary <= position) {
                if (cycle == Long.MAX_VALUE || (cycle + 2.0) * duration <= position) return;
                cycle++;
                continue;
            }
            double limit = Math.min(end, nextBoundary);
            double low = (position - cycleStart) / duration;
            double high = (limit - cycleStart) / duration;
            boolean varying = Double.isNaN(constantRate);
            double integrated = varying ? Math.max(0, rate.between(low, high) * duration)
                    : constantRate * (limit - position);
            double accumulated = timeRemainder + integrated;
            double whole = Math.floor(accumulated);
            int count = (int) Math.min(whole, Integer.MAX_VALUE);
            for (int index = 0; index < count && hasCapacity.getAsBoolean(); index++) {
                double threshold = index + 1 - timeRemainder;
                double elapsed = varying ? cycleStart + crossing(low, high, threshold, origin + cycleStart) * duration
                        : position + threshold / constantRate;
                birth.accept(origin + Math.min(limit, elapsed));
            }
            timeRemainder = accumulated - whole;
            position = limit;
            cycle++;
        }
    }

    /** Emits by distance and gives the caller the fractional position along the current motion segment. */
    public void emitDistance(double distance, double spacing, BooleanSupplier hasCapacity, DoubleConsumer birth) {
        if (distance <= 0) return;
        double remainder = distanceSharesTimeRemainder ? timeRemainder * spacing : distanceRemainder;
        double accumulated = remainder + distance;
        int count = (int) Math.min(Integer.MAX_VALUE, Math.floor(accumulated / spacing));
        for (int index = 0; index < count && hasCapacity.getAsBoolean(); index++) {
            birth.accept((spacing - remainder + index * spacing) / distance);
        }
        double unusedDistance = accumulated % spacing;
        if (distanceSharesTimeRemainder) timeRemainder = unusedDistance / spacing;
        else distanceRemainder = unusedDistance;
    }

    /** Emits by distance using a time-varying or random-range particles-per-unit rate along [start, end]. */
    public void emitDistance(double distance, double start, double end, RateIntegral distanceRate,
                             BooleanSupplier hasCapacity, DoubleConsumer birth) {
        if (distance <= 0 || end <= start) return;
        double totalSpan = end - start;
        double activeEnd = looping ? end : Math.min(end, duration);
        if (activeEnd <= start) return;
        long cycle = (long) Math.floor(start / duration);
        double position = start;
        while (position < activeEnd) {
            double cycleStart = cycle * duration;
            double nextBoundary = (cycle + 1.0) * duration;
            if (nextBoundary <= position) {
                if (cycle == Long.MAX_VALUE || (cycle + 2.0) * duration <= position) return;
                cycle++;
                continue;
            }
            double limit = Math.min(activeEnd, nextBoundary);
            double low = (position - cycleStart) / duration;
            double high = (limit - cycleStart) / duration;
            double phaseSpan = high - low;
            if (phaseSpan > 0) {
                double integrated = Math.max(0, distanceRate.between(low, high));
                double segmentDistance = distance * ((limit - position) / totalSpan);
                double expected = segmentDistance * (integrated / phaseSpan);
                double remainder = distanceSharesTimeRemainder ? timeRemainder : rateDistanceRemainder;
                double accumulated = remainder + expected;
                double whole = Math.floor(accumulated);
                int count = (int) Math.min(whole, Integer.MAX_VALUE);
                for (int index = 0; index < count && hasCapacity.getAsBoolean(); index++) {
                    double threshold = index + 1 - remainder;
                    double phase = distanceCrossing(low, high, integrated, expected, threshold, distanceRate);
                    double elapsed = cycleStart + phase * duration;
                    birth.accept(Math.clamp((elapsed - start) / totalSpan, 0, 1));
                }
                if (distanceSharesTimeRemainder) timeRemainder = accumulated - whole;
                else rateDistanceRemainder = accumulated - whole;
            }
            position = limit;
            cycle++;
        }
    }

    private static double distanceCrossing(double start, double end, double totalIntegral, double totalParticles,
                                           double threshold, RateIntegral distanceRate) {
        if (totalParticles <= 0 || totalIntegral <= 0) return end;
        double low = start;
        double high = end;
        double targetIntegral = (threshold / totalParticles) * totalIntegral;
        for (int iteration = 0; iteration < 18; iteration++) {
            double middle = (low + high) * .5;
            if (distanceRate.between(start, middle) < targetIntegral) low = middle;
            else high = middle;
        }
        return (low + high) * .5;
    }

    private double crossing(double start, double end, double threshold, double origin) {
        double low = start;
        double high = end;
        for (int iteration = 0; iteration < 52; iteration++) {
            double middle = (low + high) * .5;
            if (middle == low || middle == high || origin + low * duration == origin + high * duration) break;
            if (rate.between(start, middle) * duration < threshold) low = middle;
            else high = middle;
        }
        return (low + high) * .5;
    }
}
