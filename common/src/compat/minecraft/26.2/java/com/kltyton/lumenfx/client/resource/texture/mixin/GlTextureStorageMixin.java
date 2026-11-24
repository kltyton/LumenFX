package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureCreation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.opengl.GlDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native color attachments retain the host allocator with their explicit sRGB internal format. */
@Mixin(GlDevice.class)
abstract class GlTextureStorageMixin {
    @ModifyExpressionValue(method = "createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlConst;toGlInternalId(Lcom/mojang/blaze3d/GpuFormat;)I"))
    private int lumenfx$physicalColorFormat(int original) { return NativeTextureCreation.glFormat(original); }
}
