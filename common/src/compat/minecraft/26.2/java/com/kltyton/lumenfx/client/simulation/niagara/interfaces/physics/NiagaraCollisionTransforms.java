package com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics;

import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Source row-major 3×4 transform and inverse layout used by collision shader buffers. */
final class NiagaraCollisionTransforms {
    static final int WORDS = 12;
    private NiagaraCollisionTransforms() { }

    static void write(Matrix4dc source, int offset, float[] world, float[] inverse) {
        var matrix = new Matrix4f();
        for (int column = 0; column < 4; column++)
            for (int row = 0; row < 4; row++) matrix.set(column, row, (float)source.get(column, row));
        copy(matrix, offset, world);
        if (matrix.determinant() == 0f) matrix.identity();
        else matrix.invert();
        copy(matrix, offset, inverse);
    }

    private static void copy(Matrix4fc matrix, int offset, float[] destination) {
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 4; column++) destination[offset++] = matrix.get(column, row);
    }
}
