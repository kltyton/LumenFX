package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Exact per-submit scope at the native prepareModel getVertexBuilder call. */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererMixin {
    @WrapOperation(
            method = "prepareModel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer lumenfx$modelSubmit(
            ModelFeatureRenderer instance,
            RenderType renderType,
            Operation<VertexConsumer> original,
            @Local(argsOnly = true) ModelFeatureRenderer.Submit<?> submit) {
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
