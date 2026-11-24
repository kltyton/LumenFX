package com.kltyton.lumenfx.client.render.hdr.mixin;

import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.joml.Matrix4f;

/** Opens and closes the particle frame around the world render. */
@Mixin(GameRenderer.class)
abstract class WorldRenderCaptureMixin {
    @Shadow @Final private RenderTarget mainRenderTarget;
    @Shadow @Final private FogRenderer fogRenderer;

    @WrapMethod(method = "renderLevel")
    private void lumenfx$capture(DeltaTracker tracker, Operation<Void> original) {
        var capture = WorldHdrCapture.INSTANCE;
        VfxEngine.INSTANCE.ensureRenderResources();
        long previousBoundary = VfxEngine.INSTANCE.beginSceneFrame();
        var previousEffects = com.kltyton.lumenfx.client.event.ClientVfxEvents.beginSceneEffects();
        try {
            capture.begin(mainRenderTarget, VfxEngine.INSTANCE.viewSettings(), fogRenderer.getBuffer(FogRenderer.FogMode.WORLD));
            capture.captureFirstPersonView(((GameRenderer)(Object)this).gameRenderState());
            original.call(tracker);
        } finally {
            try { capture.end(); }
            finally {
                try { com.kltyton.lumenfx.client.event.ClientVfxEvents.endSceneEffects(previousEffects); }
                finally { VfxEngine.INSTANCE.endSceneFrame(previousBoundary); }
            }
        }
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice lumenfx$worldProjection(ProjectionMatrixBuffer buffer, Matrix4f projection,
                                                   Operation<GpuBufferSlice> original) {
        WorldHdrCapture.INSTANCE.prepareProjection(projection, Minecraft.getInstance().isPaused());
        return original.call(buffer, projection);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void lumenfx$closeTargets(CallbackInfo callback) {
        VfxEngine.INSTANCE.releaseRenderResources();
        EffectTextures.clear();
    }
}
