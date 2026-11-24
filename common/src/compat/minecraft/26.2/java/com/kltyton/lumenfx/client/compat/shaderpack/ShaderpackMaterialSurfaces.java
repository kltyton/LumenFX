package com.kltyton.lumenfx.client.compat.shaderpack;

import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Host RGB/depth, actual view-XY/normal, raw material, and resolved geometric-normal/smoothness/highlight inputs. */
public record ShaderpackMaterialSurfaces(GpuTextureView baseDepth, GpuTextureView geometry,
                                         GpuTextureView material, @Nullable GpuTextureView lightingParameters, RawSpecularEncoding encoding,
                                         int width, int height, @Nullable Matrix4fc materialProjection,
                                         @Nullable String specularSource, @Nullable ColourEncoding surfaceEncoding,
                                         @Nullable GpuTextureView diffuseResponse) {
    public enum RawSpecularEncoding { NONE, SEUS_PBR, LAB_PBR, UNKNOWN }

    public ShaderpackMaterialSurfaces {
        Objects.requireNonNull(baseDepth); Objects.requireNonNull(geometry);
        Objects.requireNonNull(material); Objects.requireNonNull(encoding);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid shaderpack surface extent");
        if (materialProjection != null) materialProjection = new Matrix4f(materialProjection);
    }

    public ShaderpackMaterialSurfaces(GpuTextureView baseDepth, GpuTextureView geometry,
            GpuTextureView material, RawSpecularEncoding encoding, int width, int height) {
        this(baseDepth, geometry, material, null, encoding, width, height, null, null, null);
    }

    public ShaderpackMaterialSurfaces(GpuTextureView baseDepth, GpuTextureView geometry,
            GpuTextureView material, @Nullable GpuTextureView lightingParameters,
            RawSpecularEncoding encoding, int width, int height) {
        this(baseDepth, geometry, material, lightingParameters, encoding, width, height, null, null, null);
    }

    public ShaderpackMaterialSurfaces(GpuTextureView baseDepth, GpuTextureView geometry,
            GpuTextureView material, @Nullable GpuTextureView lightingParameters,
            RawSpecularEncoding encoding, int width, int height, @Nullable Matrix4fc materialProjection) {
        this(baseDepth, geometry, material, lightingParameters, encoding, width, height, materialProjection, null, null);
    }

    public ShaderpackMaterialSurfaces(GpuTextureView baseDepth, GpuTextureView geometry,
            GpuTextureView material, @Nullable GpuTextureView lightingParameters,
            RawSpecularEncoding encoding, int width, int height, @Nullable Matrix4fc materialProjection,
            @Nullable String specularSource) {
        this(baseDepth, geometry, material, lightingParameters, encoding, width, height, materialProjection, specularSource, null);
    }

    public ShaderpackMaterialSurfaces(GpuTextureView baseDepth, GpuTextureView geometry,
            GpuTextureView material, @Nullable GpuTextureView lightingParameters,
            RawSpecularEncoding encoding, int width, int height, @Nullable Matrix4fc materialProjection,
            @Nullable String specularSource, @Nullable ColourEncoding surfaceEncoding) {
        this(baseDepth, geometry, material, lightingParameters, encoding, width, height, materialProjection,
                specularSource, surfaceEncoding, null);
    }

    @Override public @Nullable Matrix4fc materialProjection() {
        return materialProjection == null ? null : new Matrix4f(materialProjection);
    }
}
