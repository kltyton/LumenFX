package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerView;
import com.kltyton.lumenfx.client.resource.texture.storage.SampledTextureStorage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlTextureView;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Host GL texture binding chooses the physical 2D-array or cubemap-array target. */
@Mixin(GlCommandEncoder.class)
abstract class GlSampledTextureArrayMixin {
    @WrapOperation(method = "trySetup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_bindTexture(I)V"))
    private void lumenfx$bindSampledArray(int texture, Operation<Void> original, @Local GlTextureView view) {
        if (view instanceof TextureLayerView layer && layer.samplesImageArray())
            GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, texture);
        else original.call(texture);
    }

    @WrapOperation(method = "trySetup", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL33C;glBindTexture(II)V"))
    private void lumenfx$bindSampledCubeArray(int target, int texture, Operation<Void> original, @Local GlTextureView view) {
        original.call(SampledTextureStorage.isCubeArray(view.texture()) ? GL40C.GL_TEXTURE_CUBE_MAP_ARRAY : target, texture);
    }

    @ModifyArg(method = "trySetup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_texParameter(III)V"), index = 0)
    private int lumenfx$sampledArrayMipTarget(int target, @Local GlTextureView view) {
        if (SampledTextureStorage.isCubeArray(view.texture())) return GL40C.GL_TEXTURE_CUBE_MAP_ARRAY;
        return view instanceof TextureLayerView layer && layer.samplesImageArray() ? GL30C.GL_TEXTURE_2D_ARRAY : target;
    }
}
