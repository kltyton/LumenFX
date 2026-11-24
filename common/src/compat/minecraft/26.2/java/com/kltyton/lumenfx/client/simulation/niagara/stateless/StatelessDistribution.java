package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.kltyton.lumenfx.math.SourceFloatMath;

/** The three source words address either immutable static data or the current renderer binding buffer. */
public record StatelessDistribution(int header, int timeBiasBits, int timeScaleBits) {
    static final class Lookup { int a, b; float alpha; }
    public boolean valid() { return timeScaleBits != 0; }
    public int lookupMode() { return header >>> 26 & 3; }
    public boolean random() { return lookupMode() == 3; }
    private boolean linear() { return (header & 1 << 30) != 0; }
    private boolean wrap() { return (header & 1 << 29) != 0; }
    private int last() { return header >>> 17 & 511; }

    public void sample(StatelessParticleSimulation context, int row, int seedOffset, float time, float[] output, int width) {
        checkWidth(output, width);
        Lookup lookup = context.distributionLookup;
        if (random()) {
            randomLookup(context.randomFloat(row, seedOffset), lookup);
            if (linear()) {
                context.randomFloats(row, seedOffset, output, width);
                float firstRandom = output[0];
                for (int component = 0; component < width; component++) {
                    float alpha = (header & 1 << 28) != 0 ? firstRandom : output[component];
                    float a = value(context, lookup.a, component, width, false);
                    float b = value(context, lookup.b, component, width, false);
                    output[component] = a + (b - a) * alpha;
                }
            } else read(context, lookup, output, width);
        } else {
            timeLookup(time, lookup);
            interpolate(context, lookup, output, width, false);
        }
    }

    public void samplePair(StatelessParticleSimulation context, int row, int seedOffset, float timeA, float timeB,
                           float[] a, float[] b, int width) {
        checkWidth(a, width); checkWidth(b, width);
        sample(context, row, seedOffset, timeA, a, width);
        if (random()) System.arraycopy(a, 0, b, 0, width);
        else sample(context, row, seedOffset, timeB, b, width);
    }

    public void curve(StatelessParticleSimulation context, float time, float[] output, int width) {
        checkWidth(output, width);
        Lookup lookup = context.distributionLookup;
        timeLookup(time, lookup);
        interpolate(context, lookup, output, width, true);
    }

    private void interpolate(StatelessParticleSimulation context, Lookup lookup, float[] output, int width, boolean forceStatic) {
        for (int component = 0; component < width; component++) {
            float a = value(context, lookup.a, component, width, forceStatic);
            float b = value(context, lookup.b, component, width, forceStatic);
            output[component] = a + (b - a) * lookup.alpha;
        }
    }
    private void read(StatelessParticleSimulation context, Lookup lookup, float[] output, int width) {
        for (int component = 0; component < width; component++) {
            float a = value(context, lookup.a, component, width, false);
            value(context, lookup.b, component, width, false);
            output[component] = a;
        }
    }
    private float value(StatelessParticleSimulation context, int index, int component, int width, boolean forceStatic) {
        int offset = (header & 0x1ffff) + index * width + component;
        return forceStatic || (header & 1 << 31) != 0 ? context.staticFloat(offset) : context.dynamicFloat(offset);
    }

    private void randomLookup(float random, Lookup result) {
        float index = random * last();
        result.a = linear() ? (int)(long)index : SourceFloatMath.roundToInt(index);
        result.b = result.a + 1;
        result.alpha = linear() ? SourceFloatMath.fractional(index) : 0f;
        if (Integer.compareUnsigned(result.b, last()) > 0) result.b = wrap() ? 0 : last();
    }
    private void timeLookup(float time, Lookup result) {
        float value = SourceFloatMath.max((time - Float.intBitsToFloat(timeBiasBits)) * Float.intBitsToFloat(timeScaleBits), 0f);
        result.a = linear() ? (int)(long)value : SourceFloatMath.roundToInt(value);
        result.b = result.a + 1;
        if (wrap()) {
            result.a = Integer.remainderUnsigned(result.a, last() + 1);
            // The source computes B before wrapping A; it does not calculate wrapped-A + 1 here.
            if (Integer.compareUnsigned(result.b, last()) > 0) result.b = 0;
        } else {
            if (Integer.compareUnsigned(result.a, last()) > 0) result.a = last();
            if (Integer.compareUnsigned(result.b, last()) > 0) result.b = last();
        }
        result.alpha = linear() ? SourceFloatMath.fractional(value) : 0f;
    }
    private static void checkWidth(float[] values, int width) {
        if (width < 1 || width > 4 || values.length < width) throw new IllegalArgumentException("Invalid stateless distribution width");
    }
}
