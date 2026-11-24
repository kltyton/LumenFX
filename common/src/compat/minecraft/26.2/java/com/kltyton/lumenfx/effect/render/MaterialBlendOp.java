package com.kltyton.lumenfx.effect.render;

import com.mojang.blaze3d.platform.BlendOp;

/** Hardware blend operation applied to particle colour targets. */
public enum MaterialBlendOp {
    ADD(BlendOp.ADD),
    SUBTRACT(BlendOp.SUBTRACT),
    REVERSE_SUBTRACT(BlendOp.REVERSE_SUBTRACT),
    MIN(BlendOp.MIN),
    MAX(BlendOp.MAX);

    private final BlendOp gpu;

    MaterialBlendOp(BlendOp gpu) {
        this.gpu = gpu;
    }

    public BlendOp gpu() {
        return gpu;
    }
}
