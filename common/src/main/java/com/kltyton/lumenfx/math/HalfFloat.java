package com.kltyton.lumenfx.math;

/** Pure Java 17 IEEE-754 binary16 (half-precision float) conversion helpers. */
public final class HalfFloat {
    private HalfFloat() { }

    public static float toFloat(short half) {
        int h = half & 0xFFFF;
        int sign = (h & 0x8000) << 16;
        int exp = (h >>> 10) & 0x1F;
        int mant = h & 0x03FF;
        if (exp == 0) {
            if (mant == 0) return Float.intBitsToFloat(sign);
            while ((mant & 0x0400) == 0) {
                mant <<= 1;
                exp--;
            }
            exp++;
            mant &= ~0x0400;
            return Float.intBitsToFloat(sign | ((exp + 112) << 23) | (mant << 13));
        }
        if (exp == 31) {
            return Float.intBitsToFloat(sign | 0x7F800000 | (mant << 13));
        }
        return Float.intBitsToFloat(sign | ((exp + 112) << 23) | (mant << 13));
    }

    public static short fromFloat(float value) {
        int bits = Float.floatToRawIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int exp = ((bits >>> 23) & 0xFF) - 127;
        int mant = bits & 0x007FFFFF;
        if (exp == 128) {
            int payload = mant == 0 ? 0 : Math.max(1, mant >>> 13);
            return (short) (sign | 0x7C00 | payload);
        }
        if (exp > 15) return (short) (sign | 0x7C00);
        if (exp < -25) return (short) sign;
        if (exp < -14) return (short) (sign | roundEven(mant | 0x00800000, -exp - 1));
        return (short) (sign | (((exp + 15) << 10) + roundEven(mant, 13)));
    }

    private static int roundEven(int value, int shift) {
        return (value + (1 << (shift - 1)) - 1 + ((value >>> shift) & 1)) >>> shift;
    }
}
