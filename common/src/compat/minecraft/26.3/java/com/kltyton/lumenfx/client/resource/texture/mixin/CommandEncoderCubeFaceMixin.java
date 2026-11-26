package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.kltyton.lumenfx.client.graphics.LumenRenderPassDescriptor;
import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerViews;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Attachment layer validation uses the selected view on MC 26.3 RenderPearl; all other checks remain active. */
@Mixin(FrontendCommandEncoder.class)
abstract class CommandEncoderCubeFaceMixin {
    @WrapOperation(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/textures/GpuTexture;getDepthOrLayers()I"))
    private int lumenfx$attachmentLayerCount(GpuTexture texture, Operation<Integer> original,
                                             @Local(argsOnly = true) RenderPassDescriptor descriptor) {
        return TextureLayerViews.attachmentLayers(texture, LumenRenderPassDescriptor.fromRenderPearl(descriptor), original.call(texture));
    }
}
