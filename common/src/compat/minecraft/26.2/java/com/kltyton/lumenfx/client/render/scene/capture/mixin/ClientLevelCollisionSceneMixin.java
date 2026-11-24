package com.kltyton.lumenfx.client.render.scene.capture.mixin;

import com.kltyton.lumenfx.client.VfxEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidates captured collision shapes after a committed client block-state change. */
@Mixin(ClientLevel.class)
abstract class ClientLevelCollisionSceneMixin {
    @Inject(method = "setBlocksDirty", at = @At("RETURN"))
    private void lumenfx$invalidateDistanceField(BlockPos position, BlockState oldState, BlockState newState, CallbackInfo callback) {
        VfxEngine.INSTANCE.distanceFieldBlockChanged((ClientLevel)(Object)this, position);
    }
}
