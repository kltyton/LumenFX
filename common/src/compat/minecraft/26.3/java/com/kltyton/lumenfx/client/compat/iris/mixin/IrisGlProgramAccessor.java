package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.Uniform;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes RenderPearl's bound uniform slot list on {@link GlProgram} for Iris motion capture. */
@Mixin(GlProgram.class)
public interface IrisGlProgramAccessor {
    @Accessor("uniforms")
    List<Uniform> lumenfx$uniforms();
}
