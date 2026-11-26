package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.render.compute.VolumeGpuTexture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.opengl.GlCommandEncoder;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Host GL sampler binding uses GL_TEXTURE_3D for volume views on MC 26.3. */
@Mixin(GlCommandEncoder.class)
abstract class GlSampledTextureVolumeMixin {
    @WrapOperation(method = "setupDraw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_bindTexture(I)V"))
    private void lumenfx$bindSampledVolume(int texture, Operation<Void> original, @Local GlTextureView view) {
        if (view.texture() instanceof VolumeGpuTexture) GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
        else original.call(texture);
    }

    @ModifyArg(method = "setupDraw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_texParameter(III)V"), index = 0)
    private int lumenfx$sampledVolumeMipTarget(int target, @Local GlTextureView view) {
        return view.texture() instanceof VolumeGpuTexture ? GL12C.GL_TEXTURE_3D : target;
    }
}
