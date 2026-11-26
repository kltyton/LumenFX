package com.kltyton.lumenfx.runtime.niagara;

import java.util.Arrays;

/** Planar typed component arrays storing floats, ints, and halves as 32-bit words for VectorVM execution. */
public final class DirectVectorDataSet {
    private final int[][][] components;
    private int capacity;
    private int count;

    public DirectVectorDataSet(int floats, int integers, int halves) {
        this.components = new int[][][]{
                new int[Math.max(0, floats)][],
                new int[Math.max(0, integers)][],
                new int[Math.max(0, halves)][]
        };
        reserve(4);
    }

    public void reserve(int required) {
        if (required <= capacity) return;
        capacity = Math.max((required + 3) & ~3, Math.max(capacity * 2, 16));
        for (int[][] type : components) {
            for (int i = 0; i < type.length; i++) {
                type[i] = type[i] == null ? new int[capacity] : Arrays.copyOf(type[i], capacity);
            }
        }
    }

    public int[] component(int type, int index) { return components[type][index]; }
    public int componentCount(int type) { return components[type].length; }
    public int count() { return count; }
    public void count(int value) { reserve(value + 4); count = value; }

    public float floating(int component, int row) {
        return Float.intBitsToFloat(components[0][component][row]);
    }

    public void floating(int component, int row, float value) {
        components[0][component][row] = Float.floatToRawIntBits(value);
    }

    public int integer(int component, int row) {
        return components[1][component][row];
    }

    public void integer(int component, int row, int value) {
        components[1][component][row] = value;
    }

    public float half(int component, int row) {
        return HalfFloat.toFloat((short) components[2][component][row]);
    }

    public void half(int component, int row, float value) {
        components[2][component][row] = Short.toUnsignedInt(HalfFloat.fromFloat(value));
    }

    public void clear() {
        count = 0;
        for (int[][] type : components) {
            for (int[] comp : type) Arrays.fill(comp, 0);
        }
    }

    public void copyFrom(DirectVectorDataSet source) {
        if (source == this) return;
        reserve(source.count + 4);
        for (int t = 0; t < components.length; t++) {
            int n = Math.min(components[t].length, source.components[t].length);
            for (int c = 0; c < n; c++) {
                System.arraycopy(source.components[t][c], 0, components[t][c], 0, source.count);
            }
        }
        count = source.count;
    }

    /** O(1) swap of planar component backing arrays and metadata with another same-layout dataset. */
    public void swapWith(DirectVectorDataSet other) {
        if (other == this) return;
        for (int t = 0; t < components.length; t++) {
            int[][] tmp = components[t];
            components[t] = other.components[t];
            other.components[t] = tmp;
        }
        int tmpCap = capacity;
        capacity = other.capacity;
        other.capacity = tmpCap;
        int tmpCount = count;
        count = other.count;
        other.count = tmpCount;
    }
}
