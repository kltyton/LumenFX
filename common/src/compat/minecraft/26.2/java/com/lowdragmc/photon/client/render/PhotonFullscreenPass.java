/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.kltyton.lumenfx.LumenFx;
import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * One NDC-covering triangle for particle-layer composition and attachment copies.
 */
public final class PhotonFullscreenPass {

    private PhotonFullscreenPass() {
    }

    /** A pipeline builder over the fullscreen triangle — samplers and state are the caller's. */
    public static RenderPipeline.Builder builder(Identifier fragmentShader) {
        return RenderPipeline.builder()
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withFragmentShader(fragmentShader);
    }

    public static ColorTargetState opaqueTarget(GpuFormat format) {
        return new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL);
    }

    /** {@code bind} must bind everything the pipeline declares; no default uniforms are bound. */
    public static void draw(String label, RenderPipeline pipeline, GpuTextureView target,
                            Consumer<RenderPass> bind) {
        draw(label, pipeline, target, null, bind);
    }

    public static void draw(String label, RenderPipeline pipeline, GpuTextureView target,
                            @Nullable ScissorState scissor, Consumer<RenderPass> bind) {
        draw(label, pipeline, target, null, scissor, bind);
    }

    /** With a depth attachment, for a pipeline whose state tests or writes depth (Vulkan requires the pair). */
    public static void draw(String label, RenderPipeline pipeline, GpuTextureView target,
                            @Nullable GpuTextureView depth, @Nullable ScissorState scissor,
                            Consumer<RenderPass> bind) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> label, target, Optional.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            if (scissor != null && scissor.enabled()) {
                pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
            }
            bind.accept(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    // ---- format-converting copies ---------------------------------------------------------------

    private static final Map<GpuFormat, RenderPipeline> COPY_PIPELINES = new ConcurrentHashMap<>();

    private static RenderPipeline copyPipeline(GpuFormat format) {
        return COPY_PIPELINES.computeIfAbsent(format, f -> builder(Photon.id("core/photon_copy"))
                .withLocation(Photon.id("pipeline/copy_" + f.name().toLowerCase(Locale.ROOT)))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("InSampler").build())
                .withColorTargetState(opaqueTarget(f))
                .build());
    }

    public static void precompileCopy(GpuFormat format) {
        RenderSystem.getDevice().precompilePipeline(copyPipeline(format));
    }

    /** A same-size copy across formats (e.g. RGBA8 ↔ RGBA16F), which {@code copyTextureToTexture} cannot do. */
    public static void copy(String label, GpuTextureView source, GpuTextureView destination,
                            @Nullable ScissorState scissor) {
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        draw(label, copyPipeline(destination.texture().getFormat()), destination, scissor,
                pass -> pass.bindTexture("InSampler", source, sampler));
    }
}
