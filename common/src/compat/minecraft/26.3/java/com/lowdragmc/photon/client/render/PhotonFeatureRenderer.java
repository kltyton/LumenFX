/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.kltyton.lumenfx.client.compat.iris.IrisParticleRenderScope;
import com.mojang.renderpearl.api.commands.RenderPass;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;

/** Photon's feature renderer on MC 26.3 RenderPearl. */
public final class PhotonFeatureRenderer implements FeatureRenderer<PhotonSubmit> {
    public static final FeatureRendererType<PhotonSubmit> TYPE = FeatureRendererType.create("photon:fx");

    @Override
    public void prepareGroup(FeatureFrameContext context, List<PhotonSubmit> submits, boolean strictlyOrdered) {
        if (IrisParticleRenderScope.isShadowPass()) {
            return;
        }
        IrisParticleRenderScope.withParticlePrograms(() -> {
            for (var submit : submits) {
                submit.frame().prepare();
            }
        });
    }

    @Override
    public void executeGroup(FeatureFrameContext context, @Nullable OitStage oitStage, RenderPass pass,
                             int groupIndex, List<PhotonSubmit> submits, boolean strictlyOrdered) {
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
