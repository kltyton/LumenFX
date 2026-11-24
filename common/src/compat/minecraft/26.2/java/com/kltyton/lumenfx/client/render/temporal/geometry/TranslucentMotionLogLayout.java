package com.kltyton.lumenfx.client.render.temporal.geometry;

import com.kltyton.lumenfx.effect.render.MaterialDepth;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;

/**
 * Shared layout constants for the ordered per-pixel translucent motion replay log SSBO.
 * Uses OpenGL SSBO slot {@value #GL_SLOT} so it never collides with {@code LfxVirtualTextureFeedback} (slot 0).
 */
public final class TranslucentMotionLogLayout {
    public static final String NAME = "LfxTranslucentMotionLog";
    public static final String DRAW_UNIFORM = "LfxMotionLogDraw";
    public static final String DRAW_INFO = "LfxMotionLogDrawInfo";
    public static final String STEP_BASE_UNIFORM = "LfxMotionStepBase";
    public static final String STEP_BASE_INFO = "LfxMotionStepBaseInfo";
    public static final String STEP_INDIRECT_UNIFORM = "LfxMotionStepIndirect";
    public static final int GL_SLOT = 1;
    public static final int HEADER_UINTS = 8;
    public static final int NODE_UINTS = 12;

    public static final int COMPARE_ALWAYS = 0;
    public static final int COMPARE_LESS = 1;
    public static final int COMPARE_EQUAL = 2;
    public static final int COMPARE_LESS_EQUAL = 3;
    public static final int COMPARE_GREATER = 4;
    public static final int COMPARE_NOT_EQUAL = 5;
    public static final int COMPARE_GREATER_EQUAL = 6;
    public static final int COMPARE_NEVER = 7;

    public static final int FLAG_WRITES_COLOR_DEPTH = 1 << 4;
    public static final int FLAG_VELOCITY_ELIGIBLE = 1 << 5;
    public static final int FLAG_VELOCITY_FROM_DEPTH = 1 << 6;

    private TranslucentMotionLogLayout() { }

    public static void addStorage(BindGroupLayout.Builder layout) {
        layout.withUniform(NAME, UniformType.UNIFORM_BUFFER);
    }

    public static void addCaptureBindings(BindGroupLayout.Builder layout) {
        layout.withUniform(DRAW_UNIFORM, UniformType.UNIFORM_BUFFER);
        addStorage(layout);
    }

    public static int compareCode(MaterialDepth.Compare compare) {
        return switch (compare) {
            case DISABLED, ALWAYS -> COMPARE_ALWAYS;
            case LESS -> COMPARE_LESS;
            case EQUAL -> COMPARE_EQUAL;
            case LESS_EQUAL -> COMPARE_LESS_EQUAL;
            case GREATER -> COMPARE_GREATER;
            case NOT_EQUAL -> COMPARE_NOT_EQUAL;
            case GREATER_EQUAL -> COMPARE_GREATER_EQUAL;
            case NEVER -> COMPARE_NEVER;
        };
    }

    public static int packDrawFlags(MaterialDepth.Compare compare, boolean writesColorDepth,
                                    boolean batchWritesVelocity, boolean velocityFromDepth) {
        return (compareCode(compare) & 0xF)
                | (writesColorDepth ? FLAG_WRITES_COLOR_DEPTH : 0)
                | (batchWritesVelocity ? FLAG_VELOCITY_ELIGIBLE : 0)
                | (velocityFromDepth ? FLAG_VELOCITY_FROM_DEPTH : 0);
    }
}
