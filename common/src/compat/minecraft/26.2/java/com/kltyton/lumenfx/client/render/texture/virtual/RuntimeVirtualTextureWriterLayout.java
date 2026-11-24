package com.kltyton.lumenfx.client.render.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer.Request;
import com.kltyton.lumenfx.client.render.texture.NativeColorFormats;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.Optional;
import org.joml.Vector4fc;

/** Material raster layout and per-page constants used by the source RVT writer shader. */
public final class RuntimeVirtualTextureWriterLayout {
    public static final String UNIFORM = "LfxRuntimeVirtualTexturePage";
    private static final BindGroupLayout PAGE_LAYOUT = BindGroupLayout.builder().withUniform(UNIFORM, UniformType.UNIFORM_BUFFER).build();
    private static final BlendFunction OPACITY = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA,
            BlendFactor.ZERO, BlendFactor.ONE);
    private static final BlendFunction HEIGHT = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX,
            BlendFactor.ONE, BlendFactor.ONE, BlendOp.ADD);
    private static final int RED = 1, GREEN = 2, BLUE = 4, ALPHA = 8, RGBA = RED | GREEN | BLUE | ALPHA;
    private static final int BASE = 1, NORMAL = 2, ROUGHNESS = 4, SPECULAR = 8, MASK = 16;
    private static final double TILE_SIZE_CM = 2097152.0;

    private RuntimeVirtualTextureWriterLayout() { }

    public static int rasterLayers(Material material) {
        return switch (material) {
            case Mask4, BaseColor_Normal_Roughness -> 2;
            case BaseColor_Normal_Specular, BaseColor_Normal_Specular_YCoCg, BaseColor_Normal_Specular_Mask_YCoCg -> 3;
            default -> 1;
        };
    }

    public static boolean srgb(Material material, int layer) {
        return layer == 0 && switch (material) {
            case BaseColor, BaseColor_Normal_Specular, BaseColor_Normal_Specular_YCoCg, BaseColor_Normal_Specular_Mask_YCoCg -> true;
            default -> false;
        };
    }

    public static GpuFormat format(Material material) {
        return switch (material) {
            case WorldHeight, Displacement -> GpuFormat.R16_UNORM;
            default -> GpuFormat.RGBA8_UNORM;
        };
    }

    /** Adds the page binding and the source target blend/attribute masks to the primitive's vertex factory. */
    public static RenderPipeline pipeline(RenderPipeline.Builder builder, Material material, MaterialDefinition writer) {
        int attributes = writer.runtimeVirtualTextureOutputs();
        if (attributes <= 0 || (attributes & ~255) != 0)
            throw new IllegalArgumentException("Invalid runtime virtual texture writer attribute mask " + attributes);
        int layers = rasterLayers(material);
        var formats = new NativeTextureStorage[layers];
        builder.withFragmentShader(writer.fragmentShader()).withCull(writer.cull())
                .withBindGroupLayout(PAGE_LAYOUT).withDepthStencilState(Optional.empty())
                .withShaderDefine("LFX_RVT_LAYOUT", material.ordinal()).withShaderDefine("LFX_RVT_RASTER_LAYERS", layers);
        if (!writer.virtualTextureReads().isEmpty()) {
            var virtualTextures = BindGroupLayout.builder();
            MaterialVirtualTextureBindings.layout(virtualTextures, writer.virtualTextureReads(), writer.virtualTextureFeedback());
            builder.withBindGroupLayout(virtualTextures.build());
        }
        for (int layer = 0; layer < layers; layer++) {
            builder.withColorTargetState(layer, new ColorTargetState(Optional.of(material == Material.WorldHeight ? HEIGHT : OPACITY),
                    format(material), writeMask(material, attributes, layer)));
            if (srgb(material, layer)) formats[layer] = new NativeTextureStorage(TextureStorageFormat.BGRA8, true);
        }
        var pipeline = builder.build();
        NativeColorFormats.register(pipeline, formats);
        return pipeline;
    }

    private static int writeMask(Material material, int attributes, int layer) {
        return switch (material) {
            case BaseColor -> RGBA;
            case Mask4 -> layer == 0 ? RGBA : RED;
            case WorldHeight, Displacement -> RED;
            case BaseColor_Normal_Roughness -> layer == 0 ? channels(attributes, BASE, RGBA)
                    : channels(attributes, NORMAL, RED | BLUE | ALPHA) | channels(attributes, ROUGHNESS, GREEN | ALPHA);
            case BaseColor_Normal_Specular, BaseColor_Normal_Specular_YCoCg, BaseColor_Normal_Specular_Mask_YCoCg -> switch (layer) {
                case 0 -> channels(attributes, BASE, RGBA);
                case 1 -> channels(attributes, NORMAL, RED | GREEN | ALPHA) | channels(attributes, MASK, BLUE | ALPHA);
                default -> channels(attributes, SPECULAR, RED | ALPHA) | channels(attributes, ROUGHNESS, GREEN | ALPHA)
                        | channels(attributes, NORMAL, BLUE | ALPHA);
            };
        };
    }

    private static int channels(int attributes, int bit, int channels) { return (attributes & bit) != 0 ? channels : 0; }

    public static GpuBufferSlice upload(CommandEncoder encoder, Request request, Vector4fc fixedColor, Vector4fc debugMip) {
        var page = request.view();
        var volume = request.volume();
        double height = volume.minHeight() * 100.0;
        double heightTile = Math.floor(height / TILE_SIZE_CM);
        var custom = request.definition().customMaterialData();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(80,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat(page.mip()).putFloat(page.maxMip())
                    .putFloat((float)(page.derivativeU() * 100.0)).putFloat((float)(page.derivativeV() * 100.0));
            data.putFloat((float)heightTile).putFloat((float)(height - heightTile * TILE_SIZE_CM))
                    .putFloat((float)(1.0 / Math.max(1.0, volume.heightScale() * 100.0))).putFloat(1f / page.pixelSize());
            data.putFloat(fixedColor.x()).putFloat(fixedColor.y()).putFloat(fixedColor.z()).putFloat(fixedColor.w());
            data.putFloat(debugMip.x()).putFloat(debugMip.y()).putFloat(debugMip.z()).putFloat(debugMip.w());
            data.putFloat(custom.x()).putFloat(custom.y()).putFloat(custom.z()).putFloat(custom.w());
            return mapped.slice();
        }
    }
}
