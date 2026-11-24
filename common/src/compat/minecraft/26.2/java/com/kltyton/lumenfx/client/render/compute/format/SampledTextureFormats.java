package com.kltyton.lumenfx.client.render.compute.format;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.resource.texture.storage.SampledTextureStorage;

/** Floating sampler inputs accept normalized and floating color formats, never integer or depth aspects. */
public final class SampledTextureFormats {
    private SampledTextureFormats() { }

    public static boolean floatingColor(GpuTexture texture) {
        return texture instanceof SampledTextureStorage storage ? storage.sampledStorage().format().floatingColor()
                : floatingColor(texture.getFormat());
    }

    public static boolean accepts(ShaderBinding.SampledTexture binding, GpuTexture texture) {
        return texture instanceof SampledTextureStorage ? binding.dynamicFloatFormat() && floatingColor(texture)
                : binding.accepts(texture.getFormat());
    }

    public static String describe(GpuTexture texture) {
        return texture instanceof SampledTextureStorage storage ? storage.sampledStorage().toString() : texture.getFormat().name();
    }

    public static boolean floatingColor(GpuFormat format) {
        if (!format.hasColorAspect()) return false;
        return switch (format.componentType()) {
            case UNORM_8, SNORM_8, UNORM_16, SNORM_16, FLOAT_16, FLOAT_32 -> true;
            case OPAQUE_32 -> format == GpuFormat.RGB10A2_UNORM || format == GpuFormat.RG11B10_FLOAT;
            default -> false;
        };
    }
}
