package com.kltyton.lumenfx.loader.neoforge;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.common.collision.ServerCollisionWorldEvents;
import com.kltyton.lumenfx.common.force.ServerColliderForceAuthority;
import com.kltyton.lumenfx.common.force.ServerColliderForceSpecs;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@Mod(LumenFx.MOD_ID)
public final class LumenFxNeoForgeMod {
    public LumenFxNeoForgeMod() {
        NeoForge.EVENT_BUS.addListener((AddServerReloadListenersEvent event) ->
                event.addListener(LumenFx.id("server_collider_force_specs"), ServerColliderForceSpecs.createReloadListener()));
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                ServerColliderForceAuthority.tick(level);
            }
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                ServerColliderForceAuthority.unload(level);
                ServerCollisionWorldEvents.onLevelUnload(level);
            }
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                ServerCollisionWorldEvents.onChunkChanged(level, event.getChunk().getPos());
            }
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                ServerCollisionWorldEvents.onChunkChanged(level, event.getChunk().getPos());
            }
        });
    }
}
