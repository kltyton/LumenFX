package com.kltyton.lumenfx.effect.curve;

public record ColorFunction(ScalarFunction red, ScalarFunction green, ScalarFunction blue, ScalarFunction alpha,
                            boolean randomTime, boolean minimumGammaOutput, boolean maximumGammaOutput) {
    public record Color(float red, float green, float blue, float alpha) { }

    public ColorFunction(ScalarFunction red, ScalarFunction green, ScalarFunction blue, ScalarFunction alpha) {
        this(red, green, blue, alpha, false);
    }

    public ColorFunction(ScalarFunction red, ScalarFunction green, ScalarFunction blue, ScalarFunction alpha,
                         boolean randomTime) {
        this(red, green, blue, alpha, randomTime, false, false);
    }

    public Color sample(double time, double random) {
        return new Color((float)sampleChannel(0, time, random, random), (float)sampleChannel(1, time, random, random),
                (float)sampleChannel(2, time, random, random), (float)sampleChannel(3, time, random, random));
    }

    public double sampleChannel(int channel, double time, double channelRandom, double gradientRandom) {
        ScalarFunction curve = switch (channel) {
            case 0 -> red;
            case 1 -> green;
            case 2 -> blue;
            case 3 -> alpha;
            default -> throw new IndexOutOfBoundsException(channel);
        };
        double position = randomTime ? gradientRandom : time;
        double blend = randomTime ? 0 : channelRandom;
        if (channel == 3 || !minimumGammaOutput && !maximumGammaOutput) return curve.sample(position, blend);

        // Evaluate each source ramp in its own output space before applying the MinMaxGradient blend.
        double low = curve.sample(position, 0);
        if (minimumGammaOutput) low = linearToGamma(low);
        if (blend == 0) return low;
        double high = curve.sample(position, 1);
        if (maximumGammaOutput) high = linearToGamma(high);
        return low + (high - low) * blend;
    }

    private static double linearToGamma(double value) {
        if (value <= 0) return 0;
        if (value <= 0.0031308) return 12.92 * value;
        if (value < 1) return 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055;
        return Math.pow(value, 1.0 / 2.2);
    }

    public static ColorFunction white() {
        ScalarFunction one = ScalarFunction.constant(1);
        return new ColorFunction(one, one, one, one);
    }
}
