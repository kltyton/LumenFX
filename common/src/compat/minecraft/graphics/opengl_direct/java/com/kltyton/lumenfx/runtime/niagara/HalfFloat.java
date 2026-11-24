package com.kltyton.lumenfx.runtime.niagara;

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
        int exp = ((bits >>> 23) & 0xFF) - 112;
        int mant = bits & 0x007FFFFF;
        if (exp <= 0) {
            if (exp < -10) return (short) sign;
            mant = (mant | 0x00800000) >> (1 - exp);
            return (short) (sign | ((mant + 0x00001000) >> 13));
        }
        if (exp == 143) {
            if (mant == 0) return (short) (sign | 0x7C00);
            return (short) (sign | 0x7C00 | (mant >>> 13) | (mant != 0 ? 1 : 0));
        }
        if (exp > 30) return (short) (sign | 0x7C00);
        return (short) (sign | (exp << 10) | ((mant + 0x00001000) >> 13));
    }
}
