package com.kltyton.lumenfx.client.graphics.mixin;

import com.kltyton.lumenfx.client.graphics.RenderPearlCompat;
import com.kltyton.lumenfx.client.graphics.shader.RenderPearlShaderStages;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adapts mod-owned GLSL and carries its source pipeline through frontend compilation. */
@Mixin(PipelineBuilder.class)
abstract class PipelineBuilderMixin {
    @ModifyVariable(method = "generateBackendCreateInfo", at = @At("HEAD"), argsOnly = true)
    private ShaderSource lumenfx$linkShaderStages(ShaderSource source, @Local(argsOnly = true) RenderPipeline pipeline) {
        String namespace = pipeline.getLocation().getNamespace();
        return namespace.equals("lumenfx") || namespace.equals("photon")
                ? RenderPearlShaderStages.adapt(pipeline, source) : source;
    }

    @Inject(method = "generateBackendCreateInfo", at = @At("RETURN"))
    private void lumenfx$rememberSource(CallbackInfoReturnable<BackendRenderPipeline.CreateInfo> callback,
                                       @Local(argsOnly = true) RenderPipeline pipeline) {
        var createInfo = callback.getReturnValue();
        if (createInfo != null) RenderPearlCompat.rememberSource(createInfo, pipeline);
    }
}

