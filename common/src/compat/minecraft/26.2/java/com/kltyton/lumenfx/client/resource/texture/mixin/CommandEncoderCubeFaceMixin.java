package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerViews;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Attachment layer validation uses the selected view; all other host attachment checks remain active. */
@Mixin(CommandEncoder.class)
abstract class CommandEncoderCubeFaceMixin {
    @WrapOperation(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPass;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/textures/GpuTexture;getDepthOrLayers()I"))
    private int lumenfx$attachmentLayerCount(GpuTexture texture, Operation<Integer> original,
                                            @Local(argsOnly = true) RenderPassDescriptor descriptor) {
        return TextureLayerViews.attachmentLayers(texture, descriptor, original.call(texture));
    }
}
