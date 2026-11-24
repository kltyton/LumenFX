/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;

/** One stage of a view's Photon work; both submits of a view share its frame. */
public record PhotonSubmit(PhotonWorldRenderState.Frame frame, PhotonStage stage) implements SubmitNode {

    @Override
    public FeatureRendererType<PhotonSubmit> featureType() {
        return PhotonFeatureRenderer.TYPE;
    }
}
