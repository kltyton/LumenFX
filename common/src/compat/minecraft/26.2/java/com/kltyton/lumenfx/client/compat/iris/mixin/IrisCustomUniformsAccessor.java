package com.kltyton.lumenfx.client.compat.iris.mixin;

import java.util.Map;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CustomUniforms.class, remap = false)
public interface IrisCustomUniformsAccessor {
    @Accessor(value = "locationMap", remap = false)
    Map<Object, ?> lumenfx$locationMap();
}
