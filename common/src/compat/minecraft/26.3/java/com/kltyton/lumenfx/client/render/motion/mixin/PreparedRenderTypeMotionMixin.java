package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.scene.capture.RenderPassScenePipelineAccess;
import com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Queues motion capture behind the pass that submitted the original geometry. */
@Mixin(PreparedRenderType.class)
abstract class PreparedRenderTypeMotionMixin {
    @Inject(method = "drawFromBuffer", at = @At("RETURN"))
    private void lumenfx$captureClassic(StagedVertexBuffer.ExecuteInfo info, RenderPass pass, CallbackInfo ci) {
        lumenfx$queueMotion(info, pass);
    }

    @Inject(method = "drawFromBufferOit", at = @At("RETURN"))
    private void lumenfx$captureOit(StagedVertexBuffer.ExecuteInfo info, OitStage stage, RenderPass pass, CallbackInfo ci) {
        if (stage == OitStage.DEPTH_BOUNDS) lumenfx$queueMotion(info, pass);
    }

    @Unique
    private void lumenfx$queueMotion(StagedVertexBuffer.ExecuteInfo info, RenderPass pass) {
        GeometryMotionRenderer renderer = GeometryMotionRenderer.active();
        if (renderer != null && pass instanceof RenderPassScenePipelineAccess scenePass) {
            var prepared = (PreparedRenderType) (Object) this;
            scenePass.lumenfx$afterClose(() -> renderer.draw(prepared, info));
        }
    }
}
