package com.kltyton.lumenfx.client.compat.iris.mixin;

import net.irisshaders.iris.gl.sampler.SamplerBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SamplerBinding.class, remap = false)
public interface IrisSamplerBindingAccessor {
    @Accessor(value = "textureUnit", remap = false)
    int lumenfx$textureUnit();
}
