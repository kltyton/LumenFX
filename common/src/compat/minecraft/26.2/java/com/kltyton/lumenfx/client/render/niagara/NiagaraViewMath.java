package com.kltyton.lumenfx.client.render.niagara;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3dc;

/**
 * Converts Minecraft column-vector matrices to UE shader-visible row-vector matrices.
 *
 * <p>MC 26.2 Projection is RH view -Z forward with reversed Z. UE view space is +Z
     * forward. Canonical ViewToClip is {@code h*C*P_base*inverse(S)*Q}; full world-to-clip
     * is {@code h*C*K_MC*inverse(S)}, with {@code h=s} for perspective and {@code h=1}
     * for orthographic projection. Clip-Z is remapped only when the device is not
 * zero-to-one. World axes and pre-view translation stay in the MC basis.</p>
 */
public final class NiagaraViewMath {
    private static final float FUDGE = 1.0e-13f;

    private NiagaraViewMath() {
    }

    static Matrix4f transpose(final Matrix4fc columnVector) {
        return new Matrix4f(columnVector).transpose();
    }

    static Matrix4f fromRowMajor(final float[] values) {
        if (values.length != 16)
            throw new IllegalArgumentException("Projection matrix must have 16 elements");
        final float[] columnMajor = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                columnMajor[column * 4 + row] = values[row * 4 + column];
            }
        }
        return new Matrix4f().set(columnMajor);
    }

    static float at(final Matrix4fc matrix, final int row, final int column) {
        return switch (row * 4 + column) {
            case 0 -> matrix.m00();
            case 1 -> matrix.m10();
            case 2 -> matrix.m20();
            case 3 -> matrix.m30();
            case 4 -> matrix.m01();
            case 5 -> matrix.m11();
            case 6 -> matrix.m21();
            case 7 -> matrix.m31();
            case 8 -> matrix.m02();
            case 9 -> matrix.m12();
            case 10 -> matrix.m22();
            case 11 -> matrix.m32();
            case 12 -> matrix.m03();
            case 13 -> matrix.m13();
            case 14 -> matrix.m23();
            case 15 -> matrix.m33();
            default -> throw new IllegalArgumentException("matrix index");
        };
    }

    /** Canonical UE ViewToClip with projection-specific homogeneous normalization. */
    public static Matrix4f canonicalViewToClipRowVector(final Matrix4fc baseProjectionMc,
                                                 final float metresToCentimetres,
                                                 final boolean zZeroToOne) {
        final Matrix4f result = clipZColumn(zZeroToOne)
                .mul(new Matrix4f(baseProjectionMc))
                .mul(inverseS(metresToCentimetres))
                .mul(qFlip());
        scaleAll(result, isPerspective(baseProjectionMc) ? metresToCentimetres : 1.0f);
        return result.transpose(new Matrix4f());
    }

    /** Full UE world-to-clip from MC full K: s*C*K_MC*inv(S). */
    public static Matrix4f fullViewProjectionRowVectorCm(final Matrix4fc fullViewProjectionMc,
                                                  final float metresToCentimetres,
                                                  final boolean zZeroToOne) {
        return fullViewProjectionRowVectorCm(fullViewProjectionMc, metresToCentimetres, zZeroToOne, true);
    }

    public static Matrix4f fullViewProjectionRowVectorCm(final Matrix4fc fullViewProjectionMc,
                                                  final float metresToCentimetres,
                                                  final boolean zZeroToOne,
                                                  final boolean perspective) {
        final Matrix4f result = clipZColumn(zZeroToOne)
                .mul(new Matrix4f(fullViewProjectionMc))
                .mul(inverseS(metresToCentimetres));
        scaleAll(result, perspective ? metresToCentimetres : 1.0f);
        return result.transpose(new Matrix4f());
    }

    public static float[] inverseDeviceZToWorldZTransform(final Matrix4fc baseProjectionMc,
                                                   final float metresToCentimetres,
                                                   final boolean zZeroToOne) {
        if (isPerspective(baseProjectionMc)) {
            final float alpha = zZeroToOne ? 1.0f : 0.5f;
            final float beta = zZeroToOne ? 0.0f : 0.5f;
            final float a = beta - alpha * baseProjectionMc.m22();
            final float b = metresToCentimetres * alpha * baseProjectionMc.m32();
            if (b == 0.0f)
                throw new IllegalArgumentException("Perspective base projection has zero depth translation");
            return new float[]{0.0f, 0.0f, 1.0f / b, a / b - FUDGE};
        }
        final float alpha = zZeroToOne ? 1.0f : 0.5f;
        final float beta = zZeroToOne ? 0.0f : 0.5f;
        final float depthMul = -alpha * baseProjectionMc.m22() / metresToCentimetres;
        final float depthAdd = alpha * baseProjectionMc.m32() + beta;
        return new float[]{1.0f / depthMul, -depthAdd / depthMul + 1.0f, 0.0f, 1.0f};
    }

    public static boolean isPerspective(final Matrix4fc projection) {
        return projection.m33() < 1.0f;
    }

    public static Matrix4f screenToTranslatedWorld(final Matrix4f fullViewProjectionRowCm,
                                            final Matrix4fc baseProjectionMc,
                                            final float metresToCentimetres,
                                            final boolean zZeroToOne) {
        final Matrix4f kColumn = transpose(fullViewProjectionRowCm);
        final Matrix4f h = hMatrix(baseProjectionMc, metresToCentimetres, zZeroToOne);
        return kColumn.invert(new Matrix4f()).mul(h).transpose(new Matrix4f());
    }

    private static Matrix4f hMatrix(final Matrix4fc baseProjectionMc,
                                    final float metresToCentimetres,
                                    final boolean zZeroToOne) {
        final float alpha = zZeroToOne ? 1.0f : 0.5f;
        final float beta = zZeroToOne ? 0.0f : 0.5f;
        final boolean perspective = isPerspective(baseProjectionMc);
        final float a = perspective ? beta - alpha * baseProjectionMc.m22()
                : -alpha * baseProjectionMc.m22() / metresToCentimetres;
        final float b = perspective ? metresToCentimetres * alpha * baseProjectionMc.m32()
                : alpha * baseProjectionMc.m32() + beta;
        return fromRowMajor(new float[]{
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, a, b,
                0.0f, 0.0f, perspective ? 1.0f : 0.0f, perspective ? 0.0f : 1.0f
        });
    }

    public static Matrix4f clipToPrevClip(
            final Matrix4fc currentUnjitteredViewProjectionMc,
            final Matrix4fc previousUnjitteredViewProjectionMc,
            final Vector3dc currentPreViewTranslation,
            final Vector3dc previousPreViewTranslation,
            final boolean zZeroToOne
    ) {
        final float dx = (float) (previousPreViewTranslation.x() - currentPreViewTranslation.x());
        final float dy = (float) (previousPreViewTranslation.y() - currentPreViewTranslation.y());
        final float dz = (float) (previousPreViewTranslation.z() - currentPreViewTranslation.z());
        final Matrix4f translation = new Matrix4f().translation(dx, dy, dz);
        final Matrix4f mc = new Matrix4f(previousUnjitteredViewProjectionMc)
                .mul(translation)
                .mul(new Matrix4f(currentUnjitteredViewProjectionMc).invert(new Matrix4f()));
        return conjugateClip(mc, zZeroToOne);
    }

    private static Matrix4f conjugateClip(final Matrix4fc columnVectorMcClip,
                                          final boolean zZeroToOne) {
        final Matrix4f c = clipZColumn(zZeroToOne);
        final Matrix4f ue = c.mul(new Matrix4f(columnVectorMcClip))
                .mul(c.invert(new Matrix4f()));
        return ue.transpose(new Matrix4f());
    }

    /** C = Y * D, where Y flips clip Y and D optionally remaps non-zero-to-one clip Z. */
    private static Matrix4f clipZColumn(final boolean zZeroToOne) {
        final Matrix4f c = new Matrix4f().identity().m11(-1.0f);
        if (!zZeroToOne) {
            c.m22(0.5f).m32(0.5f);
        }
        return c;
    }

    private static Matrix4f qFlip() {
        return new Matrix4f().identity().m11(-1.0f).m22(-1.0f);
    }

    private static Matrix4f inverseS(final float metresToCentimetres) {
        final float inv = 1.0f / metresToCentimetres;
        return new Matrix4f().identity().m00(inv).m11(inv).m22(inv);
    }

    private static void scaleAll(final Matrix4f matrix, final float scale) {
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                setAt(matrix, row, column, at(matrix, row, column) * scale);
            }
        }
    }

    private static void setAt(final Matrix4f matrix, final int row, final int column, final float value) {
        switch (row * 4 + column) {
            case 0 -> matrix.m00(value);
            case 1 -> matrix.m10(value);
            case 2 -> matrix.m20(value);
            case 3 -> matrix.m30(value);
            case 4 -> matrix.m01(value);
            case 5 -> matrix.m11(value);
            case 6 -> matrix.m21(value);
            case 7 -> matrix.m31(value);
            case 8 -> matrix.m02(value);
            case 9 -> matrix.m12(value);
            case 10 -> matrix.m22(value);
            case 11 -> matrix.m32(value);
            case 12 -> matrix.m03(value);
            case 13 -> matrix.m13(value);
            case 14 -> matrix.m23(value);
            case 15 -> matrix.m33(value);
            default -> throw new IllegalArgumentException("matrix index");
        }
    }
}
