package com.kltyton.lumenfx.loader.fabric;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import com.kltyton.lumenfx.runtime.bridge.Mc2612ResourceBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.joml.Matrix4f;

public final class LumenFxFabric2612ClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DirectVfxEngine.INSTANCE.attachBridge(new Mc2612ResourceBridge("fabric"));
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                LumenFx.id("direct_vfx"),
                (ResourceManagerReloadListener) manager -> DirectVfxEngine.INSTANCE.reloadResources());
        ClientTickEvents.END_CLIENT_TICK.register(client -> DirectVfxEngine.INSTANCE.tickClient());
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> DirectVfxEngine.INSTANCE.clearLevel());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            CameraRenderState cam = context.levelState().cameraRenderState;
            if (cam == null || !cam.initialized) return;
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            DirectVfxEngine.INSTANCE.renderLevel(
                    new Matrix4f(cam.projectionMatrix),
                    new Matrix4f(cam.viewRotationMatrix),
                    cam.pos.x, cam.pos.y, cam.pos.z, partial);
        });
    }
}
