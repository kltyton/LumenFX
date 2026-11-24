package com.kltyton.lumenfx.client.render.motion.mixin;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Minimal localized accessors for the native Draw fields required for payload metadata. */
@Mixin(StagedVertexBuffer.Draw.class)
public interface StagedVertexBufferDrawAccessor {
    @Accessor("primitiveTopology")
    PrimitiveTopology lumenfx$primitiveTopology();

    @Accessor("format")
    VertexFormat lumenfx$format();

    @Accessor("vertexCount")
    int lumenfx$vertexCount();
}
