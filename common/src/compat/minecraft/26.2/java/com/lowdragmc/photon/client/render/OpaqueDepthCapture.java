/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;

/**
 * A snapshot of the frame's depth taken <b>before</b> the translucent chunk layer, which {@link FXCompositeMode#LATE}
 * tests against so water cannot slice an effect in half. Depth writes of the layer also stay in this copy.
 * <p>
 * Demand-driven: {@link #demand()} is called while preparing the frame, before the snapshot seam of the same frame.
 */
public final class OpaqueDepthCapture {

    /** The layer writes depth, so materials sample a separate copy of this attachment. */
    private static final PhotonTextureCopy SNAPSHOT = new PhotonTextureCopy("Photon opaque depth",
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC);

    private static boolean demanded;
    /** A snapshot only stands in for the depth it was taken of. */
    @Nullable
    private static GpuTexture capturedFrom;

    private OpaqueDepthCapture() {
    }

    public static void demand() {
        demanded = true;
    }

    /** Called at AfterOpaqueFeatures. */
    public static void capture() {
        capturedFrom = null;
        if (!demanded) {
            SNAPSHOT.close();
            return;
        }
        demanded = false;
        var outputDepth = PhotonRenderOutput.depth();
        if (outputDepth != null && SNAPSHOT.copyOf(outputDepth) != null) {
            capturedFrom = outputDepth.texture();
        }
    }

    /** This frame's snapshot of {@code liveDepth}, or null. */
    @Nullable
    public static GpuTextureView view(GpuTextureView liveDepth) {
        return capturedFrom != null && capturedFrom == liveDepth.texture() ? SNAPSHOT.view() : null;
    }

    public static void endFrame() {
        demanded = false;
        capturedFrom = null;
    }

    public static void release() {
        endFrame();
        SNAPSHOT.close();
    }
}
