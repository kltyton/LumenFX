package com.kltyton.lumenfx.common.collision.mixin;

import com.lowdragmc.photon.client.collision.CollisionWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Invalidates the cached section when an actual server-side block mutation is installed. */
@Mixin(LevelChunk.class)
abstract class ParticleCollisionChunkStateMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void lumenfx$invalidateServerParticleCollision(BlockPos position, BlockState state, int flags,
                                                          CallbackInfoReturnable<BlockState> callback) {
        var chunk = (LevelChunk)(Object)this;
        var level = chunk.getLevel();
        var oldState = callback.getReturnValue();
        if (!level.isClientSide() && oldState != null && oldState != state) {
            CollisionWorld.blockChanged(level, position.getX(), position.getY(), position.getZ());
        }
    }
}
