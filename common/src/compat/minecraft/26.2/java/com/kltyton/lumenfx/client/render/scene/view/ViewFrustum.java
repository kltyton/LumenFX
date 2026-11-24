package com.kltyton.lumenfx.client.render.scene.view;

import org.joml.Matrix4dc;

/** Camera-relative sphere planes share the host projection's actual device-depth convention. */
public final class ViewFrustum {
    private final double[] planes = new double[24], lengths = new double[6];
    public ViewFrustum(Matrix4dc worldToClip, boolean zeroToOne) {
        plane(worldToClip, 0, 3, 0, 1); plane(worldToClip, 1, 3, 0, -1);
        plane(worldToClip, 2, 3, 1, 1); plane(worldToClip, 3, 3, 1, -1);
        plane(worldToClip, 4, zeroToOne ? -1 : 3, 2, 1); plane(worldToClip, 5, 3, 2, -1);
    }
    private void plane(Matrix4dc matrix, int index, int rowA, int rowB, int sign) {
        int offset = index * 4;
        for (int i = 0; i < 4; i++) planes[offset + i] = (rowA < 0 ? 0 : matrix.get(i, rowA)) + sign * matrix.get(i, rowB);
        lengths[index] = Math.sqrt(planes[offset] * planes[offset] + planes[offset + 1] * planes[offset + 1] + planes[offset + 2] * planes[offset + 2]);
    }
    public boolean intersectsSphere(double x, double y, double z, double radius) {
        for (int i = 0; i < 6; i++) {
            int offset = i * 4;
            if (planes[offset] * x + planes[offset + 1] * y + planes[offset + 2] * z + planes[offset + 3] < -radius * lengths[i]) return false;
        }
        return true;
    }
}
