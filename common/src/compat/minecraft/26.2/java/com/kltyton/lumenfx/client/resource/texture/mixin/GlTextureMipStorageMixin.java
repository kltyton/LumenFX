package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlDevice;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Clamps non-square texture mip dimensions to at least 1x1 during GL texture allocation. */
@Mixin(GlDevice.class)
abstract class GlTextureMipStorageMixin {
    @WrapOperation(
            method = "createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_texImage2D(IIIIIIIILjava/nio/ByteBuffer;)V")
    )
    private void lumenfx$clampMipExtent(int target, int level, int internalFormat, int width, int height,
            int border, int format, int type, @Nullable ByteBuffer pixels, Operation<Void> original) {
        original.call(target, level, internalFormat, Math.max(1, width), Math.max(1, height),
                border, format, type, pixels);
    }
}
