package com.kltyton.lumenfx.client.render.scene.capture.mixin;

import com.kltyton.lumenfx.client.render.scene.surface.PhotonOpaqueMaskCapture;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture;
import com.kltyton.lumenfx.client.render.scene.capture.SceneSurfacePass;
import com.kltyton.lumenfx.client.render.scene.capture.SceneSurfacePassAccess;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.WorldShadowPassScope;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Adds material attachments below the host pass's public color contract, retaining its original completion callback. */
@Mixin(CommandEncoder.class)
abstract class CommandEncoderSceneCaptureMixin {
    @ModifyVariable(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPass;",
            at = @At("HEAD"), argsOnly = true)
    private RenderPassDescriptor lumenfx$shadowTarget(RenderPassDescriptor original) { return WorldShadowPassScope.redirect(original); }

    @WrapOperation(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPass;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoderBackend;createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPassBackend;"))
    private RenderPassBackend lumenfx$worldSurfaces(CommandEncoderBackend backend, RenderPassDescriptor descriptor,
                                                    Operation<RenderPassBackend> original) {
        var shadow = WorldShadowPassScope.expand(descriptor);
        if (shadow != null) return lumenfx$configure(original.call(backend, descriptor), shadow.pipelines(), shadow.uniforms());
        var expansion = SceneSurfaceCapture.expand(descriptor);
        if (expansion == null) expansion = PhotonOpaqueMaskCapture.expand(descriptor);
        if (expansion == null) return original.call(backend, descriptor);
        return lumenfx$configure(original.call(backend, expansion.descriptor()), expansion.pipeline(), Map.of());
    }

    @Unique
    private static RenderPassBackend lumenfx$configure(RenderPassBackend created, UnaryOperator<RenderPipeline> pipelines,
                                                       Map<String, GpuBufferSlice> uniforms) {
        if (created instanceof SceneSurfacePassAccess access) {
            access.lumenfx$configureSceneCapture(pipelines, uniforms);
            return created;
        }
        return new SceneSurfacePass(created, pipelines, uniforms);
    }
}
