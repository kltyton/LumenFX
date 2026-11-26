package com.kltyton.lumenfx.loader.fabric;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import com.kltyton.lumenfx.runtime.bridge.Mc2612ResourceBridge;
import com.kltyton.lumenfx.runtime.gl.Mc2612WorldEffects;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

public final class LumenFxFabric2612ClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DirectVfxEngine.INSTANCE.attachBridge(new Mc2612ResourceBridge("fabric"));
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                LumenFx.id("direct_vfx"),
                (ResourceManagerReloadListener) manager -> DirectVfxEngine.INSTANCE.reloadResources());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            DirectVfxEngine.INSTANCE.tickClient();
            com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onClientTick(client.level);
        });
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> DirectVfxEngine.INSTANCE.clearLevel());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(
                context -> Mc2612WorldEffects.render(context.levelState().cameraRenderState));
    }
}
