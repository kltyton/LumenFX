package com.kltyton.lumenfx.client.render.scene.view;

import org.joml.Matrix4fc;

/** Source view LOD distance factor derived from the captured horizontal field of view. */
public final class SourceViewLod {
    private SourceViewLod() { }
    public static float factor(Matrix4fc projection) {
        return (float)(2 * Math.atan(1.0 / Math.abs(projection.m00())) / (Math.PI / 2));
    }
}
