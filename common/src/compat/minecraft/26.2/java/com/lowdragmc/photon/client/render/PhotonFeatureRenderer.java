/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.kltyton.lumenfx.client.compat.iris.IrisParticleRenderScope;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;

import java.util.List;

/**
 * Photon's feature renderer, registered with the world dispatcher. The opaque
 * stage executes in {@code SOLID}, the translucent one in {@code AFTER_TERRAIN} after vanilla's particles.
 */
public final class PhotonFeatureRenderer implements FeatureRenderer<PhotonSubmit> {

    public static final FeatureRendererType<PhotonSubmit> TYPE = FeatureRendererType.create("photon:fx");

    @Override
    public void prepareGroup(FeatureFrameContext context, List<PhotonSubmit> submits, boolean strictlyOrdered) {
        if (IrisParticleRenderScope.isShadowPass()) {
            return;
        }
        // custom pipelines compile during the bake; keep the pack from substituting them
        IrisParticleRenderScope.withParticlePrograms(() -> {
            for (var submit : submits) {
                submit.frame().prepare();
            }
        });
    }

    @Override
    public void executeGroup(FeatureFrameContext context, int groupIndex, List<PhotonSubmit> submits,
                             boolean strictlyOrdered) {
        if (IrisParticleRenderScope.isShadowPass()) {
            return;
        }
        IrisParticleRenderScope.withParticlePrograms(() -> {
            for (var submit : submits) {
                submit.frame().execute(submit.stage());
            }
        });
    }
}
