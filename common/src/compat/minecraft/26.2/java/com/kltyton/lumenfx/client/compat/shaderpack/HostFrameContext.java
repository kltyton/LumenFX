package com.kltyton.lumenfx.client.compat.shaderpack;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public record HostFrameContext(
        long hostFrameIdentity,
        Object worldIdentity,
        Matrix4fc viewMatrix,
        Matrix4fc projectionMatrix,
        double cameraX,
        double cameraY,
        double cameraZ,
        int viewportWidth,
        int viewportHeight,
        boolean available
) {
    public HostFrameContext {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }
        if (available) {
            if (hostFrameIdentity < 0L) {
                throw new IllegalArgumentException("Available host frame context requires a frame identity");
            }
            if (worldIdentity == null) {
                throw new IllegalArgumentException("Available host frame context requires a world identity");
            }
            if (viewMatrix == null || projectionMatrix == null) {
                throw new IllegalArgumentException("Available host frame context requires camera matrices");
            }
            if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)) {
                throw new IllegalArgumentException("Available host frame context requires a finite camera position");
            }
            viewMatrix = new Matrix4f(viewMatrix);
            projectionMatrix = new Matrix4f(projectionMatrix);
        } else {
            worldIdentity = null;
            viewMatrix = null;
            projectionMatrix = null;
            cameraX = Double.NaN;
            cameraY = Double.NaN;
            cameraZ = Double.NaN;
        }
    }

    @Override
    public Matrix4fc viewMatrix() {
        return this.viewMatrix == null ? null : new Matrix4f(this.viewMatrix);
    }

    @Override
    public Matrix4fc projectionMatrix() {
        return this.projectionMatrix == null ? null : new Matrix4f(this.projectionMatrix);
    }

    public static HostFrameContext unavailable(final int viewportWidth, final int viewportHeight) {
        return new HostFrameContext(
                -1L,
                null,
                null,
                null,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                viewportWidth,
                viewportHeight,
                false
        );
    }
}
