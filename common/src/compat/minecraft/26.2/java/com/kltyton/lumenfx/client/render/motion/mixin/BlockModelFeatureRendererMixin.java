package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Exact per-submit scope at the native block-model buildGroup getVertexBuilder call. */
@Mixin(BlockModelFeatureRenderer.class)
public abstract class BlockModelFeatureRendererMixin {
    @WrapOperation(
            method = "buildGroup",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/BlockModelFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer lumenfx$blockModelSubmit(
            BlockModelFeatureRenderer instance,
            RenderType renderType,
            Operation<VertexConsumer> original,
            @Local BlockModelFeatureRenderer.Submit submit) {
        boolean active = MotionHistory.INSTANCE.beginSubmit(submit);
        try {
            return original.call(instance, renderType);
        } finally {
            if (active) {
                MotionHistory.INSTANCE.endSubmit();
            }
        }
    }
}
