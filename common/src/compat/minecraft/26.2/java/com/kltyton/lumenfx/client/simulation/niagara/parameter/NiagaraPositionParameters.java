package com.kltyton.lumenfx.client.simulation.niagara.parameter;

import com.kltyton.lumenfx.api.client.EffectHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Public world positions retain doubles until the source simulation tile is known. */
public final class NiagaraPositionParameters {
    public static final double TILE_SIZE = 2097152;
    private NiagaraPositionParameters() { }
    public static double tile(double sourceCentimeters) { return (float)Math.floor(sourceCentimeters / TILE_SIZE + .5); }
    public static double origin(double sourceCentimeters) { return tile(sourceCentimeters) * TILE_SIZE; }
    public static float component(long worldBits, double origin) { return (float)(Double.longBitsToDouble(worldBits) - origin); }
    public static void write(EffectHandle.ParameterValue value, ByteBuffer target, int offset, double x, double y, double z) {
        requirePosition(value);
        long[] words = value.words();
        target.putFloat(offset, component(words[0], x)); target.putFloat(offset + 4, component(words[1], y)); target.putFloat(offset + 8, component(words[2], z));
    }
    public static void read(EffectHandle.ParameterValue value, double x, double y, double z, float[] target) {
        requirePosition(value);
        if (target.length != 3) throw new IllegalArgumentException("Position destination must have three components");
        long[] words = value.words();
        target[0] = component(words[0], x); target[1] = component(words[1], y); target[2] = component(words[2], z);
    }
    public static void rebase(byte[] values, int offset, double x, double y, double z) {
        ByteBuffer target = ByteBuffer.wrap(values).order(ByteOrder.LITTLE_ENDIAN);
        target.putFloat(offset, target.getFloat(offset) + (float)x);
        target.putFloat(offset + 4, target.getFloat(offset + 4) + (float)y);
        target.putFloat(offset + 8, target.getFloat(offset + 8) + (float)z);
    }
    private static void requirePosition(EffectHandle.ParameterValue value) {
        if (value.type() != EffectHandle.ParameterType.POSITION) throw new IllegalArgumentException("Parameter is not a source world position");
    }
}
