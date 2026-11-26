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

/** Retains the 26.3 translucency choice when tracking a moving block's native geometry. */
@Mixin(SubmitNodeCollection.class)
abstract class MovingBlockSubmissionMixin {
    @WrapOperation(method = "submitMovingBlock",
            at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/feature/MovingBlockFeatureRenderer$Submit;"))
    private MovingBlockFeatureRenderer.Submit lumenfx$attachMovingBlock(Matrix4fc pose, MovingBlockRenderState state,
            int outlineColor, boolean forceTranslucent, Operation<MovingBlockFeatureRenderer.Submit> original) {
        MovingBlockFeatureRenderer.Submit submit = original.call(pose, state, outlineColor, forceTranslucent);
        MotionHistory.INSTANCE.attachMovingBlockSubmit(submit, state, outlineColor);
        return submit;
    }
}
