package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.google.common.collect.ImmutableSet;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer$Pass")
public interface CompositePassAccessor {
    @Accessor("stageReadsFromAlt")
    @Nullable ImmutableSet<Integer> lumenfx$stageReadsFromAlt();

    @Accessor("name")
    String lumenfx$name();

    @Accessor("drawBuffers")
    int @Nullable [] lumenfx$drawBuffers();
}
