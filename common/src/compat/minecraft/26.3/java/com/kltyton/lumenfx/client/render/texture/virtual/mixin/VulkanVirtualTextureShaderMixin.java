package com.kltyton.lumenfx.client.render.texture.virtual.mixin;

import com.kltyton.lumenfx.client.render.texture.virtual.VirtualTextureFeedbackLayout;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import org.lwjgl.util.spvc.Spvc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Matches LumenFX graphics storage buffers in RenderPearl's SPIRV-Cross pipeline layout check. */
@Mixin(PipelineBuilder.class)
abstract class VulkanVirtualTextureShaderMixin {
    @WrapOperation(method = "generateBackendCreateInfo", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/frontend/shaders/SpvUtil;resourceType(Lcom/mojang/renderpearl/api/pipeline/UniformType;)I"))
    private int lumenfx$reflectFeedbackStorageType(UniformType type, Operation<Integer> original,
                                                   @Local BindGroupLayout.UniformDescription desc) {
        if (VirtualTextureFeedbackLayout.isGraphicsStorageBuffer(desc.name())) {
            return Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER;
        }
        return original.call(type);
    }
}
