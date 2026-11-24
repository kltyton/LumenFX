package com.kltyton.lumenfx.api.client.component;

import java.util.Objects;

/** Component binding values retain source axes, centimetres and scalar storage types. */
public record ComponentValue(Kind kind, long x, long y, long z, long w) {
    public enum Kind {
        FLOAT(1, 4, false), INT(1, 4, false), BOOL(1, 4, false),
        FLOAT2(2, 8, false), FLOAT3(3, 12, false), FLOAT4(4, 16, false),
        LINEAR_COLOR(4, 16, false), QUAT_FLOAT(4, 16, false), POSITION(3, 12, false),
        DOUBLE(1, 8, true), DOUBLE2(2, 16, true), DOUBLE3(3, 24, true), DOUBLE4(4, 32, true),
        QUAT_DOUBLE(4, 32, true), ROTATOR(3, 24, true), BYTE_COLOR(4, 4, false);
        private final int components, bytes;
        private final boolean doubles;
        Kind(int components, int bytes, boolean doubles) { this.components = components; this.bytes = bytes; this.doubles = doubles; }
        public int components() { return components; }
        public int bytes() { return bytes; }
        public boolean doubles() { return doubles; }
    }

    public ComponentValue { Objects.requireNonNull(kind, "Component binding type"); }

    public long word(int component) {
        if (component < 0 || component >= kind.components()) throw new IndexOutOfBoundsException(component);
        return switch (component) { case 0 -> x; case 1 -> y; case 2 -> z; default -> w; };
    }

    public double number(int component) {
        long word = word(component);
        if (kind.doubles()) return Double.longBitsToDouble(word);
        return switch (kind) {
            case INT, BOOL, BYTE_COLOR -> (int)word;
            default -> Float.intBitsToFloat((int)word);
        };
    }

    public static ComponentValue floating(Kind kind, float x, float y, float z, float w) {
        if (kind.doubles() || kind == Kind.INT || kind == Kind.BOOL || kind == Kind.BYTE_COLOR)
            throw new IllegalArgumentException("Component value requires floating-point storage");
        return new ComponentValue(kind, Float.floatToRawIntBits(x), Float.floatToRawIntBits(y), Float.floatToRawIntBits(z), Float.floatToRawIntBits(w));
    }

    public static ComponentValue doubles(Kind kind, double x, double y, double z, double w) {
        if (!kind.doubles()) throw new IllegalArgumentException("Component value requires double storage");
        return new ComponentValue(kind, Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(z), Double.doubleToRawLongBits(w));
    }

    public static ComponentValue integer(int value) { return new ComponentValue(Kind.INT, value, 0, 0, 0); }
    public static ComponentValue bool(boolean value) { return new ComponentValue(Kind.BOOL, value ? -1 : 0, 0, 0, 0); }
    public static ComponentValue color(int red, int green, int blue, int alpha) {
        if ((red | green | blue | alpha) < 0 || red > 255 || green > 255 || blue > 255 || alpha > 255)
            throw new IllegalArgumentException("Component byte color is outside 0-255");
        return new ComponentValue(Kind.BYTE_COLOR, red, green, blue, alpha);
    }
}
