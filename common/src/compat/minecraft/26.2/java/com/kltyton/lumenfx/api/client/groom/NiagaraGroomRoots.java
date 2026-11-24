package com.kltyton.lumenfx.api.client.groom;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;

/** Root skinning and RBF resources for the active mesh LOD in source GPU word layout. */
public final class NiagaraGroomRoots {
    public enum Column {
        REST_TRIANGLES(4), DEFORMED_TRIANGLES(4), BARYCENTRICS(1), ROOT_TO_TRIANGLE(1),
        REST_SAMPLES(4), DEFORMED_SAMPLES(4), SAMPLE_WEIGHTS(4);

        private final int words;

        Column(int words) { this.words = words; }
    }

    private final Map<Column, int[]> data;
    private final int sampleCount;

    public NiagaraGroomRoots(Map<Column, int[]> source, int sampleCount) {
        if (sampleCount < 0) throw new IllegalArgumentException("Negative groom RBF sample count");
        var columns = new EnumMap<Column, int[]>(Column.class);
        source.forEach((column, values) -> {
            if (values.length % column.words != 0)
                throw new IllegalArgumentException("Misaligned groom root column " + column);
            columns.put(column, values.clone());
        });
        int triangles = length(columns, Column.REST_TRIANGLES);
        if (triangles == 0 || triangles % 3 != 0 || triangles != length(columns, Column.DEFORMED_TRIANGLES)
                || length(columns, Column.BARYCENTRICS) != length(columns, Column.ROOT_TO_TRIANGLE)
                || length(columns, Column.REST_SAMPLES) < sampleCount || length(columns, Column.DEFORMED_SAMPLES) < sampleCount
                || sampleCount > 0 && length(columns, Column.SAMPLE_WEIGHTS) < sampleCount + 4)
            throw new IllegalArgumentException("Inconsistent groom root or RBF columns");
        data = Map.copyOf(columns);
        this.sampleCount = sampleCount;
    }

    public int sampleCount() { return sampleCount; }
    public int roots() { return length(data, Column.ROOT_TO_TRIANGLE); }

    public int bytes(Column column) {
        int[] values = data.get(column);
        return Math.multiplyExact(values == null ? 0 : values.length, Integer.BYTES);
    }

    public void write(Column column, ByteBuffer target) {
        int[] values = data.get(column);
        if (values != null) for (int value : values) target.putInt(value);
    }

    private static int length(Map<Column, int[]> source, Column column) {
        int[] values = source.get(column);
        return values == null ? 0 : values.length / column.words;
    }
}
