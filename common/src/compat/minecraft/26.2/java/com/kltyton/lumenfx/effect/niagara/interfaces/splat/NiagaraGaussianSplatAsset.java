package com.kltyton.lumenfx.effect.niagara.interfaces.splat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.phys.Vec3;

public final class NiagaraGaussianSplatAsset {
    private static final int MAGIC = 0x5347464c;
    private static final int HEADER_BYTES = 44;
    private static final int NATIVE_LAYOUT_BYTES = 28 * Integer.BYTES;
    public static final int MAX_SH_DEGREE = 3;
    public static final NiagaraGaussianSplatAsset EMPTY = new NiagaraGaussianSplatAsset(
            0, 0, 0, Vec3.ZERO, Vec3.ZERO, null, ByteBuffer.allocate(0));

    private final int count;
    private final int degree;
    private final int coordinateSpace;
    private final Vec3 boundsMin;
    private final Vec3 boundsMax;
    private final byte @org.jspecify.annotations.Nullable [] quantization;
    private final ByteBuffer rows;

    private NiagaraGaussianSplatAsset(int count, int degree, int coordinateSpace, Vec3 boundsMin, Vec3 boundsMax,
                                      byte @org.jspecify.annotations.Nullable [] quantization, ByteBuffer rows) {
        this.count = count;
        this.degree = degree;
        this.coordinateSpace = coordinateSpace;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
        this.quantization = quantization;
        this.rows = rows.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    public static NiagaraGaussianSplatAsset read(byte[] file) {
        var data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (data.remaining() < HEADER_BYTES || data.getInt() != MAGIC)
            throw new IllegalArgumentException("Invalid Niagara Gaussian Splat header");
        int version = data.getInt();
        int count = data.getInt();
        int degree = data.getInt();
        int coordinateSpace = data.getInt();
        Vec3 min = readVector(data);
        Vec3 max = readVector(data);
        byte[] quantization = null;
        if (version == 2) {
            if (data.remaining() < NATIVE_LAYOUT_BYTES || data.getInt() < count)
                throw new IllegalArgumentException("Truncated Niagara Gaussian Splat native layout");
            int format = data.getInt();
            if (format < 0 || format > 1) throw new IllegalArgumentException("Unknown Niagara Gaussian Splat native format");
            byte[] metadata = new byte[NATIVE_LAYOUT_BYTES - 8];
            data.get(metadata);
            if (format == 1) quantization = metadata;
        } else if (version != 1) throw new IllegalArgumentException("Unsupported Niagara Gaussian Splat version " + version);
        if (count < 0 || degree < 0 || degree > MAX_SH_DEGREE || coordinateSpace < 0 || coordinateSpace > 2
                || (long) count * stride(degree) != data.remaining())
            throw new IllegalArgumentException("Invalid Niagara Gaussian Splat layout");
        return new NiagaraGaussianSplatAsset(count, degree, coordinateSpace, min, max, quantization, data.slice());
    }

    public int count() { return count; }
    public int degree() { return degree; }
    public int coordinateSpace() { return coordinateSpace; }
    public Vec3 boundsMin() { return boundsMin; }
    public Vec3 boundsMax() { return boundsMax; }
    public @org.jspecify.annotations.Nullable ByteBuffer quantization() {
        return quantization == null ? null : ByteBuffer.wrap(quantization).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }
    public int stride() { return stride(degree); }
    public ByteBuffer rows() { return rows.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }

    public int clampedOffset(int index) {
        return Math.clamp(index, 0, Math.max(count - 1, 0)) * stride();
    }

    public int word(int byteOffset) { return rows.getInt(byteOffset); }
    public float component(int byteOffset) { return rows.getFloat(byteOffset); }

    public static int coefficientCount(int degree) { return (degree + 1) * (degree + 1); }

    public static int coefficientOffset(int coefficient) {
        return coefficient == 0 ? 40 : 56 + (coefficient - 1) * 12;
    }

    private static int stride(int degree) { return 56 + (coefficientCount(degree) - 1) * 12; }

    private static Vec3 readVector(ByteBuffer data) { return new Vec3(data.getFloat(), data.getFloat(), data.getFloat()); }
}
