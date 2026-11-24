package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.kltyton.lumenfx.math.SourceRandomStream;

/** Loop and spawn values preserve the source's different random-consumption rules for literals and bindings. */
public final class StatelessScalarRange {
    private StatelessScalarRange() { }

    public record Floating(float minimum, float maximum, boolean bound, int parameterOffset) {
        public float evaluate(SourceRandomStream random, int[] parameters, float defaultValue) {
            return bound ? parameterOffset >= 0 ? Float.intBitsToFloat(parameters[parameterOffset]) : defaultValue
                    : random.rangeFloat(minimum, maximum);
        }
    }
    public record Integer(int minimum, int maximum, boolean bound, int parameterOffset) {
        public int evaluate(SourceRandomStream random, int[] parameters, int defaultValue) {
            return bound ? parameterOffset >= 0 ? parameters[parameterOffset] : defaultValue
                    : random.rangeInt(minimum, maximum);
        }
    }
}
