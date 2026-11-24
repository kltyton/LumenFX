package com.kltyton.lumenfx.client.compat.iris.mixin;

import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(IrisRenderingPipeline.class)
public interface IrisRenderingPipelineAccessor {
    @Accessor("renderTargets")
    RenderTargets lumenfx$renderTargets();

    @Accessor("pack")
    ShaderPack lumenfx$pack();

    @Accessor("compositeRenderer")
    CompositeRenderer lumenfx$compositeRenderer();
}
