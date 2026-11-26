package com.kltyton.lumenfx.client.render.scene.capture.mixin;

import com.kltyton.lumenfx.client.graphics.LumenRenderPassDescriptor;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.WorldShadowPassScope;
import com.kltyton.lumenfx.client.render.scene.capture.SceneSurfacePassAccess;
import com.kltyton.lumenfx.client.render.scene.capture.RenderPassScenePipelineAccess;
import com.kltyton.lumenfx.client.render.scene.surface.PhotonOpaqueMaskCapture;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds material attachments below the host pass's public color contract on MC 26.3 RenderPearl. */
@Mixin(FrontendCommandEncoder.class)
abstract class CommandEncoderSceneCaptureMixin {
    @Unique private @Nullable UnaryOperator<RenderPipeline> lumenfx$pendingPipelines;
    @Unique private Map<String, GpuBufferSlice> lumenfx$pendingUniforms = Map.of();
    @Unique private @Nullable List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> lumenfx$pendingExpandedAttachments;

    @ModifyVariable(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
            at = @At("HEAD"), argsOnly = true)
    private RenderPassDescriptor lumenfx$shadowTarget(RenderPassDescriptor original) {
        LumenRenderPassDescriptor wrapped = LumenRenderPassDescriptor.fromRenderPearl(original);
        LumenRenderPassDescriptor redirected = WorldShadowPassScope.redirect(wrapped);
        return redirected == wrapped ? original : redirected.toRenderPearl();
    }

    @WrapOperation(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/backend/api/RenderPassBackend;"))
    private RenderPassBackend lumenfx$worldSurfaces(CommandEncoderBackend backend, RenderPassDescriptor descriptor,
                                                    Operation<RenderPassBackend> original) {
        lumenfx$pendingPipelines = null;
        lumenfx$pendingUniforms = Map.of();
        lumenfx$pendingExpandedAttachments = null;
        LumenRenderPassDescriptor wrapped = LumenRenderPassDescriptor.fromRenderPearl(descriptor);
        var shadow = WorldShadowPassScope.expand(wrapped);
        if (shadow != null) {
            lumenfx$pendingPipelines = shadow.pipelines();
            lumenfx$pendingUniforms = shadow.uniforms();
            return original.call(backend, descriptor);
        }
        var expansion = SceneSurfaceCapture.expand(wrapped);
        if (expansion == null) expansion = PhotonOpaqueMaskCapture.expand(wrapped);
        if (expansion == null) return original.call(backend, descriptor);
        RenderPassDescriptor expandedNative = expansion.descriptor().toRenderPearl();
        lumenfx$pendingPipelines = expansion.pipeline();
        lumenfx$pendingExpandedAttachments = expandedNative.colorAttachments();
        return original.call(backend, expandedNative);
    }

    @Inject(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
            at = @At("RETURN"))
    private void lumenfx$attachSceneCapture(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
        UnaryOperator<RenderPipeline> pipelines = lumenfx$pendingPipelines;
        Map<String, GpuBufferSlice> uniforms = lumenfx$pendingUniforms;
        var expandedAttachments = lumenfx$pendingExpandedAttachments;
        lumenfx$pendingPipelines = null;
        lumenfx$pendingUniforms = Map.of();
        lumenfx$pendingExpandedAttachments = null;
        if (pipelines == null && uniforms.isEmpty() && expandedAttachments == null) return;
        RenderPass pass = cir.getReturnValue();
        if (pass instanceof SceneSurfacePassAccess access) {
            access.lumenfx$configureSceneCapture(pipelines, uniforms);
            if (expandedAttachments != null && pass instanceof RenderPassScenePipelineAccess expandedAccess) {
                expandedAccess.lumenfx$setExpandedColorAttachments(expandedAttachments);
            }
        }
    }
}
