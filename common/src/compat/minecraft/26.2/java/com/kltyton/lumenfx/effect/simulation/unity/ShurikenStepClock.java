package com.kltyton.lumenfx.effect.simulation.unity;

/** Equal particle substeps use the scaled frame delta and the Unity project's Float32 clock. */
public final class ShurikenStepClock {
    private final float maximumDelta;
    private double origin;
    private double frameStart;
    private double frameEnd;
    private int stepCount;
    private int selectedStep;
    private float delta;
    private float elapsed;
    private float stepStartAge;
    private double stepStart;
    private double stepEnd;

    public ShurikenStepClock(double maximumDelta) {
        this.maximumDelta = (float)maximumDelta;
    }

    public void reset(double origin, double time) {
        this.origin = origin;
        elapsed = (float)(time - origin);
    }

    public int beginFrame(double start, double end) {
        frameStart = start;
        frameEnd = end;
        selectedStep = 0;
        float frameDelta = (float)(end - start);
        stepCount = (int)Math.ceil(frameDelta / maximumDelta);
        delta = stepCount == 0 ? 0 : frameDelta / stepCount;
        stepStart = stepEnd = start;
        return stepCount;
    }

    public double endOfStep(int index) {
        return index == stepCount ? frameEnd : frameStart + (frameEnd - frameStart) * index / stepCount;
    }

    public void select(int index) {
        if (index == selectedStep) return;
        // A step can be visited in several event slices. It only advances source time once.
        stepStartAge = elapsed;
        elapsed += delta;
        stepStart = index == 1 ? frameStart : endOfStep(index - 1);
        stepEnd = endOfStep(index);
        selectedStep = index;
    }

    public float duration(double from, double to, double born) {
        if (selectedStep == 0 || from < stepStart || to > stepEnd) return (float)(to - from);
        if (from == stepStart && to == stepEnd) return delta;
        if (from == born && from > stepStart) {
            float endAge = to == stepEnd ? elapsed : stepStartAge + (float)(delta * (to - stepStart) / (stepEnd - stepStart));
            float measuredInterval = elapsed - stepStartAge;
            if (measuredInterval <= 0) return (float)(to - from);
            // Birth advance uses the remaining fraction of the source clock interval against the requested delta.
            float remainingFraction = (endAge - (float)(born - origin)) / measuredInterval;
            return Math.clamp(remainingFraction, 0f, 1f) * delta;
        }
        return (float)(delta * (to - from) / (stepEnd - stepStart));
    }
}

