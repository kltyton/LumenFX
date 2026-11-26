package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMotionMixin {
    @WrapMethod(method = "drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;Lcom/mojang/renderpearl/api/commands/RenderPass;)V")
    private void lumenfx$drawThenCapture(
            StagedVertexBuffer.ExecuteInfo info, RenderPass pass, Operation<Void> original) {
        original.call(info, pass);
        GeometryMotionRenderer renderer = GeometryMotionRenderer.active();
        if (renderer != null) {
            renderer.draw((PreparedRenderType) (Object) this, info);
        }
    }

    @WrapMethod(method = "drawFromBufferOit(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;)V")
    private void lumenfx$drawOitThenCapture(
            StagedVertexBuffer.ExecuteInfo info, OitStage stage, RenderPass pass, Operation<Void> original) {
        original.call(info, stage, pass);
        GeometryMotionRenderer renderer = GeometryMotionRenderer.active();
        if (renderer != null) {
            renderer.draw((PreparedRenderType) (Object) this, info);
        }
    }
}
