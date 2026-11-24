package com.kltyton.lumenfx.loader.fabric;

import com.kltyton.lumenfx.runtime.simulation.ServerForceAuthority;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class LumenFxFabric1201Mod implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> ServerForceAuthority.tickServer());
    }
}
