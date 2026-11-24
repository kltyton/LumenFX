package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.sampler.SamplerBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ProgramSamplers.class, remap = false)
public interface IrisProgramSamplersAccessor {
    @Accessor(value = "samplerBindings", remap = false)
    ImmutableList<SamplerBinding> lumenfx$samplerBindings();
}
