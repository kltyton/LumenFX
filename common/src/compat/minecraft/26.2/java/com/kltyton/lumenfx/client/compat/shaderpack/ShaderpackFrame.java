package com.kltyton.lumenfx.client.compat.shaderpack;

import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

public interface ShaderpackFrame {
    boolean available();

    long hostFrameIdentity();

    Object worldIdentity();

    Matrix4fc viewMatrix();

    Matrix4fc projectionMatrix();

    double cameraX();

    double cameraY();

    double cameraZ();

    int viewportWidth();

    int viewportHeight();

    String packName();

    ShaderpackKind packKind();

    ColourEncoding colourEncoding();

    int sceneColorGlTextureId();

    int sceneColorGlTarget();

    int sceneColorGlFormat();

    String sceneColorFormatName();

    int sceneColorWidth();

    int sceneColorHeight();

    boolean sceneColorAltSide();

    boolean sourceJitterKnown();

    float sourceJitterNdcX();

    float sourceJitterNdcY();

    GpuTexture depth0();

    GpuTexture depth1();

    default @Nullable ShaderpackMaterialSurfaces materialSurfaces() { return null; }

    default @Nullable Matrix4fc materialProjection() {
        ShaderpackMaterialSurfaces surfaces = materialSurfaces();
        return surfaces == null ? null : surfaces.materialProjection();
    }

    default float cameraExposure() { return Float.NaN; }

    /** Temporal sample index, sequence length and XY offsets in source pixel space. */
    default @Nullable float[] temporalAAParameters() { return null; }
}
