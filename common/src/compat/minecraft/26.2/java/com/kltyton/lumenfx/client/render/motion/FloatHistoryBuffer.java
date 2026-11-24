package com.kltyton.lumenfx.client.render.motion;

import java.util.Arrays;

/**
 * Growable, pooled FP32 storage for one captured vertex stream.
 *
 * <p>{@link #count} counts floats (three floats per emitted vertex), not vertices.  The backing array
 * is replaced only when a stream grows; a snapshot therefore keeps a stable array reference even if
 * the history buffer is grown in a later frame.</p>
 */
final class FloatHistoryBuffer {
    private static final int MIN_CAPACITY = 96;

    float[] data;
    long[] vertexIds;
    int[] clipTexels;
    int count;
    long frame = -1L;

    FloatHistoryBuffer() {
        this(MIN_CAPACITY);
    }

    FloatHistoryBuffer(int capacity) {
        this.data = new float[Math.max(3, capacity)];
        this.vertexIds = new long[this.data.length / 3];
        this.clipTexels = new int[this.data.length / 3];
    }

    void reset(int initialCapacity) {
        this.count = 0;
        this.frame = -1L;
        if (this.data.length < initialCapacity) {
            this.data = new float[initialCapacity];
            this.vertexIds = new long[initialCapacity / 3];
            this.clipTexels = new int[initialCapacity / 3];
        }
    }

    void add(float x, float y, float z, long vertexId) {
        int needed = this.count + 3;
        if (needed > this.data.length) {
            int grown = this.data.length + (this.data.length >> 1);
            if (grown < needed) {
                grown = needed;
            }
            this.data = Arrays.copyOf(this.data, grown);
            this.vertexIds = Arrays.copyOf(this.vertexIds, grown / 3);
            this.clipTexels = Arrays.copyOf(this.clipTexels, grown / 3);
        }
        float[] target = this.data;
        int index = this.count;
        target[index] = x;
        target[index + 1] = y;
        target[index + 2] = z;
        this.vertexIds[index / 3] = vertexId;
        this.clipTexels[index / 3] = 0;
        this.count = needed;
    }
}
