package com.kltyton.lumenfx.client.render.compute;

/**
 * Capability contract of one preloaded shader permutation.
 *
 * <p>{@code waveOperations} is the source subgroup/wave permutation. {@code nativeFp16} means
 * real 16-bit arithmetic in the shader.
 * Storage of RGBA16_FLOAT/R11G11B10/RGB10A2 values is a format property and is not native FP16
 * arithmetic.</p>
 */
public record ShaderCapabilities(
        boolean waveOperations,
        boolean nativeFp16,
        boolean storageImageExtendedFormats,
        boolean storageBuffers,
        boolean storageImageWriteWithoutFormat
) {
    public ShaderCapabilities(boolean waveOperations, boolean nativeFp16, boolean storageImageExtendedFormats,
                              boolean storageBuffers) {
        this(waveOperations, nativeFp16, storageImageExtendedFormats, storageBuffers, false);
    }
    public static final ShaderCapabilities NONE = new ShaderCapabilities(false, false, false, false);
}
