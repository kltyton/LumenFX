package com.kltyton.lumenfx.tools.shader;

import java.util.Map;

/** Maps Unreal pixel format tokens (`PF_*`) to host `GpuFormat` and DXC storage image format identifiers. */
final class FormatResolver {
    private static final Map<String, String> GPU_FORMATS = Map.ofEntries(
            Map.entry("PF_FloatR11G11B10", "RG11B10_FLOAT"),
            Map.entry("PF_FloatRGBA", "RGBA16_FLOAT"),
            Map.entry("PF_A2B10G10R10", "RGB10A2_UNORM"),
            Map.entry("PF_R8", "R8_UNORM"),
            Map.entry("PF_R8G8B8A8", "RGBA8_UNORM"),
            Map.entry("PF_R8_UINT", "R8_UINT"),
            Map.entry("PF_R8G8_UINT", "RG8_UINT"),
            Map.entry("PF_R8G8", "RG8_UNORM"),
            Map.entry("PF_R32_SINT", "R32_SINT"),
            Map.entry("PF_R32_UINT", "R32_UINT"),
            Map.entry("PF_R16F", "R16_FLOAT"),
            Map.entry("PF_G16R16F", "RG16_FLOAT"),
            Map.entry("PF_R32_FLOAT", "R32_FLOAT"),
            Map.entry("PF_G32R32F", "RG32_FLOAT"),
            Map.entry("PF_A32B32G32R32F", "RGBA32_FLOAT"),
            Map.entry("PF_G16R16", "RG16_UNORM"),
            Map.entry("PF_A16B16G16R16", "RGBA16_UNORM")
    );

    private static final Map<String, String> DXC_FORMATS = Map.ofEntries(
            Map.entry("PF_FloatR11G11B10", "r11g11b10f"),
            Map.entry("PF_FloatRGBA", "rgba16f"),
            Map.entry("PF_A2B10G10R10", "rgb10a2"),
            Map.entry("PF_R8", "r8"),
            Map.entry("PF_R8G8B8A8", "rgba8"),
            Map.entry("PF_R8_UINT", "r8ui"),
            Map.entry("PF_R8G8_UINT", "rg8ui"),
            Map.entry("PF_R8G8", "rg8"),
            Map.entry("PF_R32_UINT", "r32ui"),
            Map.entry("PF_R16F", "r16f"),
            Map.entry("PF_G16R16F", "rg16f"),
            Map.entry("PF_R32_FLOAT", "r32f"),
            Map.entry("PF_G32R32F", "rg32f"),
            Map.entry("PF_A32B32G32R32F", "rgba32f"),
            Map.entry("PF_G16R16", "rg16"),
            Map.entry("PF_A16B16G16R16", "rgba16"),
            Map.entry("PF_R8G8B8A8_SNORM", "rgba8_snorm"),
            Map.entry("PF_R16G16B16A16_SNORM", "rgba16_snorm"),
            Map.entry("PF_R8_SNORM", "r8_snorm"),
            Map.entry("PF_R8G8_SNORM", "rg8_snorm"),
            Map.entry("PF_R16_SNORM", "r16_snorm"),
            Map.entry("PF_G16R16_SNORM", "rg16_snorm"),
            Map.entry("PF_R32_SINT", "r32i"),
            Map.entry("PF_R8_SINT", "r8i"),
            Map.entry("PF_R8G8_SINT", "rg8i"),
            Map.entry("PF_R16_SINT", "r16i"),
            Map.entry("PF_G16R16_SINT", "rg16i"),
            Map.entry("PF_RGB10A2_UINT", "rgb10a2ui"),
            Map.entry("PF_R16_UINT", "r16ui"),
            Map.entry("PF_R8G8B8A8_UINT", "rgba8ui")
    );

    private FormatResolver() { }

    static String gpuFormatForPf(final String pf) {
        return GPU_FORMATS.get(pfToken(pf));
    }

    static String dxcImageFormatForPf(final String pf) {
        return DXC_FORMATS.get(pfToken(pf));
    }

    private static String pfToken(final String pf) {
        final String stripped = pf.strip();
        final int space = stripped.indexOf(' ');
        return space < 0 ? stripped : stripped.substring(0, space);
    }
}
