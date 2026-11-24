package com.kltyton.lumenfx.common.collision;

import com.lowdragmc.photon.client.collision.CollisionWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Maintains server collision caches over chunk replacement and level shutdown. */
public final class ServerCollisionWorldEvents {
    private ServerCollisionWorldEvents() { }

    public static void onChunkChanged(ServerLevel level, ChunkPos position) {
        CollisionWorld.chunkChanged(level, position.x(), position.z());
    }

    public static void onLevelUnload(ServerLevel level) {
        CollisionWorld.levelUnloaded(level);
    }
}
