package com.kltyton.lumenfx.client.compat.iris;

import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.kltyton.lumenfx.client.compat.shaderpack.HostFrameContext;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackFrame;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackKind;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

public record IrisFrameSnapshot(
        HostFrameContext host,
        String packName,
        ShaderpackKind packKind,
        ColourEncoding colourEncoding,
        IrisSceneColor sceneColor,
        boolean sourceJitterKnown,
        float sourceJitterNdcX,
        float sourceJitterNdcY,
        GpuTexture depth0,
        GpuTexture depth1,
        @Nullable ShaderpackMaterialSurfaces materialSurfaces,
        float cameraExposure,
        float @Nullable [] temporalAAParameters,
        @Nullable Matrix4fc materialProjection
) implements ShaderpackFrame {
    public IrisFrameSnapshot(HostFrameContext host, String packName, ShaderpackKind packKind,
            ColourEncoding colourEncoding, IrisSceneColor sceneColor, boolean sourceJitterKnown,
            float sourceJitterNdcX, float sourceJitterNdcY, GpuTexture depth0, GpuTexture depth1,
            @Nullable ShaderpackMaterialSurfaces materialSurfaces, float cameraExposure,
            float @Nullable [] temporalAAParameters) {
        this(host, packName, packKind, colourEncoding, sceneColor, sourceJitterKnown,
                sourceJitterNdcX, sourceJitterNdcY, depth0, depth1, materialSurfaces, cameraExposure,
                temporalAAParameters, materialSurfaces == null ? null : materialSurfaces.materialProjection());
    }

    public IrisFrameSnapshot(HostFrameContext host, String packName, ShaderpackKind packKind,
            ColourEncoding colourEncoding, IrisSceneColor sceneColor, boolean sourceJitterKnown,
            float sourceJitterNdcX, float sourceJitterNdcY, GpuTexture depth0, GpuTexture depth1,
            @Nullable ShaderpackMaterialSurfaces materialSurfaces, float cameraExposure) {
        this(host, packName, packKind, colourEncoding, sceneColor, sourceJitterKnown,
                sourceJitterNdcX, sourceJitterNdcY, depth0, depth1, materialSurfaces, cameraExposure, null);
    }
    public IrisFrameSnapshot(HostFrameContext host, String packName, ShaderpackKind packKind,
            ColourEncoding colourEncoding, IrisSceneColor sceneColor, boolean sourceJitterKnown,
            float sourceJitterNdcX, float sourceJitterNdcY, GpuTexture depth0, GpuTexture depth1) {
        this(host, packName, packKind, colourEncoding, sceneColor, sourceJitterKnown,
                sourceJitterNdcX, sourceJitterNdcY, depth0, depth1, null, Float.NaN);
    }

    public IrisFrameSnapshot(HostFrameContext host, String packName, ShaderpackKind packKind,
            ColourEncoding colourEncoding, IrisSceneColor sceneColor, boolean sourceJitterKnown,
            float sourceJitterNdcX, float sourceJitterNdcY, GpuTexture depth0, GpuTexture depth1,
            @Nullable ShaderpackMaterialSurfaces materialSurfaces) {
        this(host, packName, packKind, colourEncoding, sceneColor, sourceJitterKnown,
                sourceJitterNdcX, sourceJitterNdcY, depth0, depth1, materialSurfaces, Float.NaN);
    }

    public IrisFrameSnapshot {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(packKind, "packKind");
        Objects.requireNonNull(colourEncoding, "colourEncoding");
        Objects.requireNonNull(sceneColor, "sceneColor");
        Objects.requireNonNull(depth0, "depth0");
        Objects.requireNonNull(depth1, "depth1");
        if (temporalAAParameters != null) {
            temporalAAParameters = temporalAAParameters.clone();
            if (temporalAAParameters.length != 4) throw new IllegalArgumentException("Invalid temporal sample layout");
            for (float value : temporalAAParameters) if (!Float.isFinite(value))
                throw new IllegalArgumentException("Non-finite temporal sample parameter");
            float index = temporalAAParameters[0], count = temporalAAParameters[1];
            if (index != Math.floor(index) || count != Math.floor(count) || index < 0 || count < 1 || index >= count)
                throw new IllegalArgumentException("Invalid temporal sample index or sequence length");
        }
        if (!host.available()) {
            throw new IllegalArgumentException("Unavailable host frame context cannot form a snapshot");
        }
        if (sourceJitterKnown
                && (!Float.isFinite(sourceJitterNdcX) || !Float.isFinite(sourceJitterNdcY))) {
            throw new IllegalArgumentException("Known source jitter must be finite");
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override public float @Nullable [] temporalAAParameters() {
        return temporalAAParameters == null ? null : temporalAAParameters.clone();
    }

    @Override
    public long hostFrameIdentity() {
        return this.host.hostFrameIdentity();
    }

    @Override
    public Object worldIdentity() {
        return this.host.worldIdentity();
    }

    @Override
    public Matrix4fc viewMatrix() {
        return this.host.viewMatrix();
    }

    @Override
    public Matrix4fc projectionMatrix() {
        return this.host.projectionMatrix();
    }

    @Override
    public @Nullable Matrix4fc materialProjection() {
        if (this.materialProjection != null) {
            return new Matrix4f(this.materialProjection);
        }
        return ShaderpackFrame.super.materialProjection();
    }

    @Override
    public double cameraX() {
        return this.host.cameraX();
    }

    @Override
    public double cameraY() {
        return this.host.cameraY();
    }

    @Override
    public double cameraZ() {
        return this.host.cameraZ();
    }

    @Override
    public int viewportWidth() {
        return this.host.viewportWidth();
    }

    @Override
    public int viewportHeight() {
        return this.host.viewportHeight();
    }

    @Override
    public int sceneColorGlTextureId() {
        return this.sceneColor.glTextureId();
    }

    @Override
    public int sceneColorGlTarget() {
        return this.sceneColor.glTarget();
    }

    @Override
    public int sceneColorGlFormat() {
        return this.sceneColor.glInternalFormat();
    }

    @Override
    public String sceneColorFormatName() {
        return this.sceneColor.physicalFormat().name();
    }

    @Override
    public int sceneColorWidth() {
        return this.sceneColor.width();
    }

    @Override
    public int sceneColorHeight() {
        return this.sceneColor.height();
    }

    @Override
    public boolean sceneColorAltSide() {
        return this.sceneColor.altSide();
    }

    @Override
    public boolean sourceJitterKnown() {
        return this.sourceJitterKnown;
    }

    @Override
    public float sourceJitterNdcX() {
        return this.sourceJitterNdcX;
    }

    @Override
    public float sourceJitterNdcY() {
        return this.sourceJitterNdcY;
    }
}
