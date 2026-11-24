package com.kltyton.lumenfx.client.render.motion.shader;

import com.mojang.blaze3d.GpuFormat;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class MotionShaderLayout {
    public static final String ATLAS_BLOCK = "lfxMotionShader_Atlas";
    public static final String ATLAS_DIMS = "lfxMotionShader_AtlasDims";
    public static final String ATLAS_BASE = "lfxMotionShader_AtlasBase";
    public static final int ATLAS_DIMS_OFFSET = 0;
    public static final int ATLAS_BASE_OFFSET = 16;
    public static final int ATLAS_DIMS_COMPONENTS = 2;
    public static final int ATLAS_BASE_COMPONENTS = 3;
    public static final int ATLAS_PACKET_INTS = 8;
    public static final int ATLAS_PACKET_BYTES = 32;

    public static final String VERTEX_MAP = "lfxMotionShader_VertexMap";
    public static final String PREVIOUS_CLIP_ATLAS = "lfxMotionShader_PreviousClipAtlas";
    public static final GpuFormat POINT_CLIP_FORMAT = GpuFormat.RGBA32_FLOAT;
    public static final GpuFormat PREVIOUS_CLIP_ATLAS_FORMAT = GpuFormat.RGBA32_FLOAT;
    public static final GpuFormat MOTION_CLIP_FORMAT = GpuFormat.RGBA32_FLOAT;
    public static final GpuFormat MOTION_DEPTH_FORMAT = GpuFormat.RG32_FLOAT;
    public static final GpuFormat MOTION_OFFSET_FORMAT = GpuFormat.RGBA16_UNORM;
    public static final GpuFormat VERTEX_MAP_FORMAT = GpuFormat.R32_UINT;

    private MotionShaderLayout() {
    }

    public static void writeAtlasPacket(
            ByteBuffer buffer,
            int width,
            int height,
            int firstVertex,
            int firstAtlasTexel,
            int firstMapTexel) {
        Objects.requireNonNull(buffer, "buffer");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("atlas width and height must be positive, got " + width + "x" + height);
        }
        if (buffer.remaining() < ATLAS_PACKET_BYTES) {
            throw new IllegalArgumentException(
                    "atlas packet requires " + ATLAS_PACKET_BYTES + " bytes, remaining " + buffer.remaining());
        }
        buffer.putInt(width).putInt(height).putInt(0).putInt(0);
        buffer.putInt(firstVertex).putInt(firstAtlasTexel).putInt(firstMapTexel).putInt(0);
    }
}
