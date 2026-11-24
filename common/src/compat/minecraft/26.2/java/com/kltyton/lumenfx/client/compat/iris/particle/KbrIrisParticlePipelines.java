package com.kltyton.lumenfx.client.compat.iris.particle;

import com.kltyton.lumenfx.client.compat.kbr.KbrCompat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

/** Associates KBR's registered quad pipelines with the shader pack's particle passes. */
public final class KbrIrisParticlePipelines {
    enum Layer { OPAQUE, CUTOUT, TRANSLUCENT }

    private static boolean registered;

    private KbrIrisParticlePipelines() { }

    public static void register(IrisRenderingPipeline iris) {
        if (registered) return;
        if (KbrCompat.isPresent()) {
            for (RenderPipeline pipeline : RenderPipelines.getStaticPipelines()) {
                Layer layer = layer(pipeline);
                if (layer != null && IrisPipelines.getPipeline(iris, pipeline) == null) {
                    RenderPipeline vanilla = layer == Layer.TRANSLUCENT
                            ? RenderPipelines.TRANSLUCENT_PARTICLE : RenderPipelines.OPAQUE_PARTICLE;
                    IrisPipelines.copyPipeline(vanilla, pipeline);
                }
            }
        }
        registered = true;
    }

    static @Nullable Layer layer(RenderPipeline pipeline) {
        var id = pipeline.getLocation();
        if (!KbrCompat.MOD_ID.equals(id.getNamespace())) return null;
        return switch (id.getPath()) {
            case "pipeline/opaque_particle" -> Layer.OPAQUE;
            case "pipeline/alpha_particle" -> Layer.CUTOUT;
            case "pipeline/blend_particle", "pipeline/additive_particle" -> Layer.TRANSLUCENT;
            default -> null;
        };
    }
}
