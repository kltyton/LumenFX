package com.kltyton.lumenfx.client.playback.mixin;

import com.lowdragmc.photon.client.collision.CollisionWorld;
import com.lowdragmc.photon.client.light.DynamicLightManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidates particle collision and voxel lighting when a client block changes. */
@Mixin(ClientLevel.class)
abstract class ParticleCollisionBlockChangeMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void lumenfx$invalidateParticleCollision(BlockPos position, BlockState oldState, BlockState newState,
                                                     CallbackInfo callback) {
        var level = (ClientLevel) (Object) this;
        CollisionWorld.blockChanged(level, position.getX(), position.getY(), position.getZ());
        DynamicLightManager.blockChanged(level, position.getX(), position.getY(), position.getZ());
    }
}
