package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.Uniform;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlProgram.class)
public interface IrisGlProgramAccessor {
    @Accessor("uniformsByName")
    Map<String, Uniform> lumenfx$uniformsByName();
}
