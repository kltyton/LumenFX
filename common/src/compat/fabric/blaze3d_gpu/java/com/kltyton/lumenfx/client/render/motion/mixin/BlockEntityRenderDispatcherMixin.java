package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.kltyton.lumenfx.client.render.motion.OwnerKey;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stable block-entity owners on Fabric (vanilla 4-argument tryExtractRenderState signature). */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(
            method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
            at = @At("RETURN"))
    private void lumenfx$tagBlockEntity(
            BlockEntity blockEntity,
            float partialTicks,
            ModelFeatureRenderer.CrumblingOverlay breakProgress,
            boolean isGloballyRendered,
            CallbackInfoReturnable<BlockEntityRenderState> cir) {
        MotionHistory.INSTANCE.tagBlockEntityState(blockEntity, cir.getReturnValue());
    }

    @WrapMethod(method = "submit")
    private void lumenfx$scopeBlockEntity(
            BlockEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            Operation<Void> original) {
        OwnerKey owner = MotionHistory.INSTANCE.ownerForBlockState(state);
        MotionHistory.INSTANCE.pushOwner(owner);
        try {
            original.call(state, poseStack, collector, camera);
        } finally {
            MotionHistory.INSTANCE.popOwner();
        }
    }
}
