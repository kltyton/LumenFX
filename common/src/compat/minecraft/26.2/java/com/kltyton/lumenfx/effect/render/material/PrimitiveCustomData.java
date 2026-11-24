package com.kltyton.lumenfx.effect.render.material;

import java.nio.ByteBuffer;

/** Fixed-size component data consumed by scalar and vector primitive material parameters. */
public final class PrimitiveCustomData {
    public static final int FLOATS = 36;
    public static final int BYTES = FLOATS * Float.BYTES;
    public static final PrimitiveCustomData EMPTY = new PrimitiveCustomData(new float[FLOATS]);

    private final float[] values;

    private PrimitiveCustomData(float[] values) {
        this.values = values;
    }

    public PrimitiveCustomData with(int first, float... data) {
        if (first < 0 || first >= FLOATS || data.length == 0) return this;
        int count = Math.min(data.length, FLOATS - first);
        int index = 0;
        while (index < count && Float.floatToRawIntBits(values[first + index]) == Float.floatToRawIntBits(data[index]))
            index++;
        if (index == count) return this;
        float[] next = values.clone();
        System.arraycopy(data, index, next, first + index, count - index);
        return new PrimitiveCustomData(next);
    }

    public void write(ByteBuffer destination) {
        for (float value : values) destination.putFloat(value);
    }
}
