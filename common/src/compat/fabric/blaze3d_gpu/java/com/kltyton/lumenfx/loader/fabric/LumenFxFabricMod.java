package com.kltyton.lumenfx.loader.fabric;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.common.collision.ServerCollisionWorldEvents;
import com.kltyton.lumenfx.common.force.ServerColliderForceAuthority;
import com.kltyton.lumenfx.common.force.ServerColliderForceSpecs;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;

public final class LumenFxFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(
                LumenFx.id("server_collider_force_specs"),
                ServerColliderForceSpecs.createReloadListener()
        );
        ServerTickEvents.END_LEVEL_TICK.register(ServerColliderForceAuthority::tick);
        ServerLevelEvents.UNLOAD.register((server, level) -> {
            ServerColliderForceAuthority.unload(level);
            ServerCollisionWorldEvents.onLevelUnload(level);
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) ->
                ServerCollisionWorldEvents.onChunkChanged(level, chunk.getPos()));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
                ServerCollisionWorldEvents.onChunkChanged(level, chunk.getPos()));
    }
}
