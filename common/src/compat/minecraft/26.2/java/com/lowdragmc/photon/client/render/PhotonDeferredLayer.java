/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import javax.annotation.Nullable;

/** Defers the prepared particle layer until the host has rendered weather and clouds. */
public final class PhotonDeferredLayer {

    @Nullable
    private static PhotonFXLayer pending;

    private PhotonDeferredLayer() {
    }

    /** Park {@code layer} for the end of the level render. */
    public static void park(PhotonFXLayer layer) {
        pending = layer;
    }

    /**
     * Blend the parked layer onto the frame's output target, if there is one. Alpha is left alone: the
     * target is already opaque and its alpha channel is not ours to spend.
     */
    public static void compositePending() {
        var layer = pending;
        pending = null;
        if (layer == null) return;
        // the surface being drawn into rather than the game window — see PhotonRenderOutput
        var output = PhotonRenderOutput.color();
        if (output == null) return;
        layer.compositeTo(output, false);
        StackedDistortion.LAYER.bend(output, PhotonRenderOutput.depth(),
                PhotonRenderOutput.scissor(output.getWidth(0), output.getHeight(0)));
    }

    /** Frame boundary: a layer nobody composited must not survive into the next frame. */
    public static void discardPending() {
        pending = null;
    }
}
