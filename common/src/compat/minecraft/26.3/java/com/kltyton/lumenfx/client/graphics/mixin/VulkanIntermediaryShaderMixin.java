package com.kltyton.lumenfx.client.graphics.mixin;

/**
 * Unused on MC 26.3: RenderPearl replaces {@code IntermediaryShaderModule} with SPIRV-Cross
 * reflection in {@code SPIRVModule}/{@code PipelineBuilder}. Kept as an empty class placeholder
 * only to override the 26.2 file path cleanly; not registered in 26.3's mixin JSON.
 */
final class VulkanIntermediaryShaderMixin {
    private VulkanIntermediaryShaderMixin() {}
}
