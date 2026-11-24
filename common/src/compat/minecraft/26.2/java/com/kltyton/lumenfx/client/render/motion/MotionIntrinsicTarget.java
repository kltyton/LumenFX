package com.kltyton.lumenfx.client.render.motion;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Exposes delegated intrinsic vertex recording for optional Sodium batch writers. */
public interface MotionIntrinsicTarget {
    VertexConsumer lumenfx$delegate();

    void lumenfx$recordIntrinsicVertex(float x, float y, float z);
}
