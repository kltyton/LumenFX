package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Copies native moving-block submission identity into the geometry motion history. */
@Mixin(SubmitNodeCollection.class)
abstract class MovingBlockSubmissionMixin {
    @WrapOperation(method = "submitMovingBlock",
            at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/feature/MovingBlockFeatureRenderer$Submit;"))
    private MovingBlockFeatureRenderer.Submit lumenfx$attachMovingBlock(Matrix4fc pose, MovingBlockRenderState state,
            int outlineColor, Operation<MovingBlockFeatureRenderer.Submit> original) {
        MovingBlockFeatureRenderer.Submit submit = original.call(pose, state, outlineColor);
        MotionHistory.INSTANCE.attachMovingBlockSubmit(submit, state, outlineColor);
        return submit;
    }
}
