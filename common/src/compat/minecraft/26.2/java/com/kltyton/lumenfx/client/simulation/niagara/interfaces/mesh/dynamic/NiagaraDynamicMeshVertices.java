package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.dynamic;

import org.joml.Vector3f;

/** Packs and reads the source vertex streams in buffer-word order. */
final class NiagaraDynamicMeshVertices {
    static final int FULL_VERTEX_WORDS = 18;
    static final int WHITE = Float.floatToRawIntBits(1);

    record Layout(int count, int uvChannels, int tangentOffset, int uvOffset, int colorOffset, int words) {
        static Layout create(int count, int channels, boolean tangents, boolean colors) {
            if (count < 0 || channels < 0) throw new IllegalArgumentException("Invalid Niagara dynamic mesh vertex capacity");
            int positionWords = Math.multiplyExact(count, 3);
            int uvOffset = Math.addExact(positionWords, tangents ? Math.multiplyExact(count, 2) : 0);
            int colorOffset = Math.addExact(uvOffset, Math.multiplyExact(Math.multiplyExact(count, channels), 2));
            int words = Math.addExact(colorOffset, colors ? count : 0);
            return new Layout(count, channels, tangents ? positionWords : -1, channels > 0 ? uvOffset : -1,
                    colors ? colorOffset : -1, words);
        }
    }

    private NiagaraDynamicMeshVertices() { }

    static void readPosition(int[] data, int vertex, int[] result, int output) {
        System.arraycopy(data, vertex * 3, result, output, 3);
    }
    static void writePosition(int[] data, int vertex, int[] input, int from) {
        System.arraycopy(input, from, data, vertex * 3, 3);
    }

    static void readTangent(Layout layout, int[] data, int vertex, int[] result, int output) {
        int offset = layout.tangentOffset() + vertex * 2;
        int packedX = data[offset], packedZ = data[offset + 1];
        var x = unpack(packedX);
        var z = unpack(packedZ);
        float sign = unpackComponent(packedZ >>> 24);
        var y = new Vector3f(z).cross(x).mul(sign);
        x.set(y).cross(z).mul(sign);
        outputVector(x, result, output);
        outputVector(y, result, output + 3);
        outputVector(z, result, output + 6);
    }

    static void writeTangent(Layout layout, int[] data, int vertex, int[] input, int from) {
        int offset = layout.tangentOffset() + vertex * 2;
        var x = inputVector(input, from);
        var y = inputVector(input, from + 3);
        var z = inputVector(input, from + 6);
        int sign = new Vector3f(x).cross(z).dot(y) < 0 ? 0x80000000 : 0x7f000000;
        data[offset] = sign | pack(x);
        data[offset + 1] = sign | pack(z);
    }

    static void readUv(Layout layout, int[] data, int vertex, int channel, int[] output, int target) {
        int offset = layout.uvOffset() + (vertex + channel) * 2;
        System.arraycopy(data, offset, output, target, 2);
    }
    static void writeUv(Layout layout, int[] data, int vertex, int channel, int[] input, int from) {
        int offset = layout.uvOffset() + (vertex + channel) * 2;
        System.arraycopy(input, from, data, offset, 2);
    }

    static void readColor(Layout layout, int[] data, int vertex, int[] output, int target) {
        int bgra = data[layout.colorOffset() + vertex];
        output[target] = bits(srgb(bgra >>> 16 & 255));
        output[target + 1] = bits(srgb(bgra >>> 8 & 255));
        output[target + 2] = bits(srgb(bgra & 255));
        output[target + 3] = bits((bgra >>> 24) / 255.0f);
    }
    static void writeColor(Layout layout, int[] data, int vertex, int[] input, int from) {
        data[layout.colorOffset() + vertex] = channel(input[from + 2]) | channel(input[from + 1]) << 8
                | channel(input[from]) << 16 | channel(input[from + 3]) << 24;
    }

    static void readVertex(Layout layout, int[] data, int vertex, int[] result, int target) {
        readPosition(data, vertex, result, target);
        if (layout.tangentOffset() >= 0) readTangent(layout, data, vertex, result, target + 3);
        if (layout.uvOffset() >= 0) readUv(layout, data, vertex, 0, result, target + 12);
        if (layout.colorOffset() >= 0) readColor(layout, data, vertex, result, target + 14);
    }
    static void writeVertex(Layout layout, int[] data, int vertex, int[] input, int from) {
        writePosition(data, vertex, input, from);
        if (layout.tangentOffset() >= 0) writeTangent(layout, data, vertex, input, from + 3);
        if (layout.uvOffset() >= 0) writeUv(layout, data, vertex, 0, input, from + 12);
        if (layout.colorOffset() >= 0) writeColor(layout, data, vertex, input, from + 14);
    }

    private static int pack(Vector3f value) {
        return ((int)(value.x * 127.499f) & 255) | (((int)(value.y * 127.499f) & 255) << 8)
                | (((int)(value.z * 127.499f) & 255) << 16);
    }
    private static Vector3f unpack(int packed) {
        return new Vector3f(unpackComponent(packed), unpackComponent(packed >>> 8), unpackComponent(packed >>> 16));
    }
    private static float unpackComponent(int value) { return (value & 255) / 127.5f - 1; }
    private static Vector3f inputVector(int[] data, int start) {
        return new Vector3f(number(data[start]), number(data[start + 1]), number(data[start + 2]));
    }
    private static void outputVector(Vector3f value, int[] output, int start) {
        output[start] = bits(value.x); output[start + 1] = bits(value.y); output[start + 2] = bits(value.z);
    }
    private static int channel(int word) { return (int)(Math.clamp(number(word), 0, 1) * 255.999f); }
    private static float srgb(int channel) {
        double value = channel / 255.0;
        return (float)(value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4));
    }
    private static float number(int word) { return Float.intBitsToFloat(word); }
    private static int bits(float value) { return Float.floatToRawIntBits(value); }
}
