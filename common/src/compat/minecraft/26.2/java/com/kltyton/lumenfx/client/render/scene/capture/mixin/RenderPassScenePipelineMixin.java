package com.kltyton.lumenfx.client.render.scene.capture.mixin;

import com.kltyton.lumenfx.client.render.light.shadow.geometry.WorldShadowPassScope;
import com.kltyton.lumenfx.client.render.scene.capture.CapturePipelineFormats;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Selects the private scene or shadow pipeline before the host validates its color attachments. */
@Mixin(RenderPass.class)
abstract class RenderPassScenePipelineMixin {
    @Shadow @Final private List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colorAttachments;
    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private RenderPipeline lumenfx$scenePipeline(RenderPipeline original) {
        var shadow = WorldShadowPassScope.pipeline(original, colorAttachments);
        return CapturePipelineFormats.adapt(shadow, colorAttachments);
    }
}
