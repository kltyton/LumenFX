package com.kltyton.lumenfx.client.render.compute.format;

import com.mojang.blaze3d.GpuFormat;

/** Floating color formats in the Vulkan storage-without-format list and GL image format table. */
public final class StorageImageFormats {
    private StorageImageFormats() { }

    public static boolean floatingColor(GpuFormat format) {
        return switch (format) {
            case R8_UNORM, RG8_UNORM, RGBA8_UNORM, R8_SNORM, RG8_SNORM, RGBA8_SNORM,
                    R16_UNORM, RG16_UNORM, RGBA16_UNORM, R16_SNORM, RG16_SNORM, RGBA16_SNORM,
                    R16_FLOAT, RG16_FLOAT, RGBA16_FLOAT, R32_FLOAT, RG32_FLOAT, RGBA32_FLOAT,
                    RGB10A2_UNORM, RG11B10_FLOAT -> true;
            default -> false;
        };
    }
}
