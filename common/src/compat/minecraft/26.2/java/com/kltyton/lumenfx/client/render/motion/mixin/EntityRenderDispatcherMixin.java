package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.kltyton.lumenfx.client.render.motion.OwnerKey;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures the stable entity owner at extraction time and scopes it around vanilla submission.
 *
 * <p>The owner tag lives on the render state (NeoForge render data) so it survives the gap between
 * {@code extractEntity} and {@code submit}.  The submission scope uses try/finally, so the original
 * callback, its exceptions and its side effects happen exactly once and the owner stack is always
 * balanced.</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void lumenfx$tagMotionOwner(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        MotionHistory.INSTANCE.tagRenderState(entity, cir.getReturnValue());
    }

    @WrapMethod(method = "submit")
    private void lumenfx$scopeMotionOwner(
            EntityRenderState renderState,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            Operation<Void> original) {
        OwnerKey owner = MotionHistory.INSTANCE.ownerForState(renderState);
        MotionHistory.INSTANCE.pushOwner(owner);
        try {
            original.call(renderState, camera, x, y, z, poseStack, submitNodeCollector);
        } finally {
            MotionHistory.INSTANCE.popOwner();
        }
    }
}
