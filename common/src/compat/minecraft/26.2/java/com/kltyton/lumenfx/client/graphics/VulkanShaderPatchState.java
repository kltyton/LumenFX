package com.kltyton.lumenfx.client.graphics;

/**
 * Thread-scoped state and pure helpers for Minecraft 26.2 Vulkan SPIR-V shader binding fixes.
 *
 * <p>Resolves three upstream MC 26.2 Vulkan {@code GlslCompiler}/{@code IntermediaryShaderModule} bugs:
 * <ol>
 *   <li>Preserves explicit fragment {@code layout(location = N)} (MRT and dual-source blend) outputs
 *       instead of overwriting them in arbitrary SPIRV-Cross reflection order.</li>
 *   <li>Accepts {@code SpvDim3D} ({@code sampler3D}) sampled images in graphics shaders alongside
 *       {@code SpvDim2D} and {@code SpvDimCube}, while still rejecting non-buffer texel buffers.</li>
 * </ol>
 */
public final class VulkanShaderPatchState {
    public static final int SPV_DIM_2D = 1;
    public static final int SPV_DIM_3D = 2;
    public static final int SPV_DIM_CUBE = 3;
    public static final int SPV_DIM_BUFFER = 5;

    private static final ThreadLocal<Boolean> PRESERVE_FRAGMENT_OUTPUT_LOCATIONS =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private VulkanShaderPatchState() {
    }

    public static boolean enterFragmentOutputPreservation() {
        boolean previous = Boolean.TRUE.equals(PRESERVE_FRAGMENT_OUTPUT_LOCATIONS.get());
        PRESERVE_FRAGMENT_OUTPUT_LOCATIONS.set(Boolean.TRUE);
        return previous;
    }

    public static void exitFragmentOutputPreservation(boolean previous) {
        if (previous) {
            PRESERVE_FRAGMENT_OUTPUT_LOCATIONS.set(Boolean.TRUE);
        } else {
            PRESERVE_FRAGMENT_OUTPUT_LOCATIONS.remove();
        }
    }

    public static boolean preservingFragmentOutputLocations() {
        return Boolean.TRUE.equals(PRESERVE_FRAGMENT_OUTPUT_LOCATIONS.get());
    }

    /**
     * Normalizes {@code SpvDim3D} to {@code SpvDim2D} solely for MC 26.2's sampled-image dimension
     * validation check, leaving {@code SpvDimBuffer} and unsupported dimensions untouched.
     */
    public static int normalizeSampledImageDimension(int spvDimension) {
        return spvDimension == SPV_DIM_3D ? SPV_DIM_2D : spvDimension;
    }
}
