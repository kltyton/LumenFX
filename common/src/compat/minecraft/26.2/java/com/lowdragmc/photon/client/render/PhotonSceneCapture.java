/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;

/**
 * Pre-FX copies of the current target's colour/depth for {@code SamplerScene*}, taken
 * on demand per stage. Needs {@code COPY_SRC} on the source; colour falls back to a sampling draw, depth has no
 * fallback. Outside a render pass only.
 */
public final class PhotonSceneCapture {

    private static final PhotonTextureCopy COLOR = new PhotonTextureCopy("Photon scene capture");
    private static final PhotonTextureCopy DEPTH = new PhotonTextureCopy("Photon scene depth capture");

    private PhotonSceneCapture() {
    }

    /** Null when the target cannot be copied. */
    @Nullable
    public static GpuTextureView captureColor(GpuTextureView target) {
        return COLOR.copyOf(target);
    }

    /** Null when the target cannot be copied. */
    @Nullable
    public static GpuTextureView captureDepth(GpuTextureView target) {
        return DEPTH.copyOf(target);
    }

    // ---- the 26.1 depth convention, for legacy custom shaders ----------------------------------------

    @Nullable
    private static GpuTexture legacyDepth;
    @Nullable
    private static GpuTextureView legacyDepthView;
    @Nullable
    private static RenderPipeline legacyDepthPipeline;

    /** {@code 1 - depth} in R32F for legacy shaders; pairs with {@code PhotonEngineUniforms.legacySlice()}. */
    public static GpuTextureView legacyDepth(GpuTextureView depthCapture) {
        var source = depthCapture.texture();
        var w = source.getWidth(0);
        var h = source.getHeight(0);
        if (legacyDepth == null || legacyDepth.getWidth(0) != w || legacyDepth.getHeight(0) != h) {
            if (legacyDepthView != null) legacyDepthView.close();
            if (legacyDepth != null) legacyDepth.close();
            var device = RenderSystem.getDevice();
            legacyDepth = device.createTexture(() -> "Photon legacy scene depth",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.R32_FLOAT,
                    w, h, 1, 1);
            legacyDepthView = device.createTextureView(legacyDepth);
        }
        if (legacyDepthPipeline == null) {
            legacyDepthPipeline = PhotonFullscreenPass.builder(Photon.id("core/photon_legacy_depth"))
                    .withLocation(Photon.id("pipeline/legacy_depth"))
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withSampler("InSampler").build())
                    .withColorTargetState(PhotonFullscreenPass.opaqueTarget(GpuFormat.R32_FLOAT))
                    .build();
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        PhotonFullscreenPass.draw("Photon legacy scene depth", legacyDepthPipeline, legacyDepthView, null,
                pass -> pass.bindTexture("InSampler", depthCapture, nearest));
        return legacyDepthView;
    }

    public static GpuSampler sampler() {
        return RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
    }
}
