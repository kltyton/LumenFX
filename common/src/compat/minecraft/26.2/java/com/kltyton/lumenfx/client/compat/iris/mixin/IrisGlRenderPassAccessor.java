package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the actual compiled program selected by Iris for the current native draw. */
@Mixin(GlRenderPass.class)
public interface IrisGlRenderPassAccessor {
    @Accessor("pipeline") GlRenderPipeline lumenfx$pipeline();
}
