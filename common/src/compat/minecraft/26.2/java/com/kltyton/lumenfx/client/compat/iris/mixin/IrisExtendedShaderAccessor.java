package com.kltyton.lumenfx.client.compat.iris.mixin;

import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ExtendedShader.class, remap = false)
public interface IrisExtendedShaderAccessor {
    @Accessor(value = "samplers", remap = false)
    ProgramSamplers lumenfx$samplers();

    @Accessor(value = "parent", remap = false)
    IrisRenderingPipeline lumenfx$parent();

    @Accessor(value = "writingToBeforeTranslucent", remap = false)
    GlFramebuffer lumenfx$beforeTranslucent();

    @Accessor(value = "writingToAfterTranslucent", remap = false)
    GlFramebuffer lumenfx$afterTranslucent();
}
