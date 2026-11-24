package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMotionMixin {
    @WrapMethod(method = "drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;)V")
    private void lumenfx$drawThenCapture(
            StagedVertexBuffer.ExecuteInfo info, Operation<Void> original) {
        original.call(info);
        GeometryMotionRenderer renderer = GeometryMotionRenderer.active();
        if (renderer != null) {
            renderer.draw((PreparedRenderType)(Object)this, info);
        }
    }
}
