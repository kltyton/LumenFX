package com.kltyton.lumenfx.client.simulation.niagara;

import java.util.Arrays;

/** Typed component arrays store floats as raw 32-bit words throughout VM execution. */
public final class VectorDataSet {
    private final int[][][] components;
    private int capacity;
    private int count;

    public VectorDataSet(int floats, int integers, int halves) {
        components = new int[][][] {new int[floats][], new int[integers][], new int[halves][]};
        reserve(4);
    }

    public void reserve(int required) {
        if (required <= capacity) return;
        capacity = Math.max((required + 3) & ~3, Math.max(capacity * 2, 16));
        for (int[][] type : components) for (int i = 0; i < type.length; i++)
            type[i] = type[i] == null ? new int[capacity] : Arrays.copyOf(type[i], capacity);
    }

    public int[] component(int type, int index) { return components[type][index]; }
    public int componentCount(int type) { return components[type].length; }
    public int count() { return count; }
    public void count(int value) { reserve(value + 4); count = value; }
    public float floating(int component, int row) { return Float.intBitsToFloat(components[0][component][row]); }
    public void floating(int component, int row, float value) { components[0][component][row] = Float.floatToRawIntBits(value); }
    public int integer(int component, int row) { return components[1][component][row]; }
    public void integer(int component, int row, int value) { components[1][component][row] = value; }
    public float half(int component, int row) { return Float.float16ToFloat((short)components[2][component][row]); }
    public void half(int component, int row, float value) {
        int bits = Float.floatToRawIntBits(value);
        components[2][component][row] = Float.isNaN(value)
                ? (bits >>> 16 & 0x8000) | 0x7e00
                : Short.toUnsignedInt(Float.floatToFloat16(value));
    }

    public void clear() {
        count = 0;
        for (int[][] type : components) for (int[] component : type) Arrays.fill(component, 0);
    }

    public void copyFrom(VectorDataSet source) {
        if (source == this) return;
        for (int type = 0; type < components.length; type++)
            if (components[type].length != source.components[type].length)
                throw new IllegalArgumentException("Niagara dataset layouts differ");
        reserve(source.count + 4);
        for (int type = 0; type < components.length; type++)
            for (int component = 0; component < components[type].length; component++)
                System.arraycopy(source.components[type][component], 0, components[type][component], 0, source.count);
        count = source.count;
    }

    void copyRowFrom(VectorDataSet source, int sourceRow, int destinationRow) {
        if (sourceRow < 0 || sourceRow >= source.capacity || destinationRow < 0)
            throw new IllegalArgumentException("Invalid Niagara dataset row");
        for (int type = 0; type < components.length; type++)
            if (components[type].length != source.components[type].length)
                throw new IllegalArgumentException("Niagara dataset layouts differ");
        reserve(Math.addExact(destinationRow, 1));
        for (int type = 0; type < components.length; type++)
            for (int component = 0; component < components[type].length; component++)
                components[type][component][destinationRow] = source.components[type][component][sourceRow];
    }
}
