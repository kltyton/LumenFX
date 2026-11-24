package com.kltyton.lumenfx.loader.fabric;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import com.kltyton.lumenfx.runtime.bridge.McLegacyResourceBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class LumenFxFabric1201ClientMod implements ClientModInitializer {
    private static ClientLevel lastLevel;

    @Override
    public void onInitializeClient() {
        DirectVfxEngine.INSTANCE.attachBridge(new McLegacyResourceBridge("1.20.1", "fabric"));
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override public ResourceLocation getFabricId() { return LumenFx.id("direct_vfx"); }
                    @Override public void onResourceManagerReload(ResourceManager manager) {
                        DirectVfxEngine.INSTANCE.reloadResources();
                    }
                });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != lastLevel) {
                if (lastLevel != null) DirectVfxEngine.INSTANCE.clearLevel();
                lastLevel = client.level;
            }
            if (client.level != null) DirectVfxEngine.INSTANCE.tickClient();
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            Vec3 cam = context.camera().getPosition();
            Matrix4f mv = new Matrix4f(context.matrixStack().last().pose());
            Matrix4f proj = new Matrix4f(context.projectionMatrix());
            DirectVfxEngine.INSTANCE.renderLevel(proj, mv, cam.x, cam.y, cam.z, context.tickDelta());
        });
    }
}
