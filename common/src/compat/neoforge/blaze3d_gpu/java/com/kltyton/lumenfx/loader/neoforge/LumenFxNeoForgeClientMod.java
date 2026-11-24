package com.kltyton.lumenfx.loader.neoforge;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.event.WorldFxClientEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(value = LumenFx.MOD_ID, dist = Dist.CLIENT)
public final class LumenFxNeoForgeClientMod {
    public LumenFxNeoForgeClientMod(ModContainer container) {
        WorldFxClientEvents.ensureInitialized();
        container.registerConfig(ModConfig.Type.CLIENT, PhotonNeoForgeConfig.CONFIG_SPEC);
    }
}
