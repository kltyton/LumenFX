/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Abstract base for the shader-driven materials (texture / sprite / custom). On 26.1 the render
 * seam is a {@code RenderType} built from a {@link com.mojang.blaze3d.pipeline.RenderPipeline} +
 * std140 uniforms: standard materials go through the shared value cache in
 * {@link MaterialRenderTypes}; {@code CustomShaderMaterial} owns its own RenderTypes.
 */
@ParametersAreNonnullByDefault
public abstract class ShaderInstanceMaterial implements IMaterial {
}
