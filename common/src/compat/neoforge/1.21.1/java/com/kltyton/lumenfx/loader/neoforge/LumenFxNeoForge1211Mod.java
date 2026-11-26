package com.kltyton.lumenfx.loader.neoforge;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import com.kltyton.lumenfx.runtime.bridge.McLegacyResourceBridge;
import com.kltyton.lumenfx.runtime.simulation.ServerForceAuthority;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Matrix4f;

@Mod(LumenFx.MOD_ID)
public final class LumenFxNeoForge1211Mod {
    public LumenFxNeoForge1211Mod(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> ServerForceAuthority.tickServer());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientInit.register(modBus);
        }
    }

    private static final class ClientInit {
        static void register(IEventBus modBus) {
            DirectVfxEngine.INSTANCE.attachBridge(new McLegacyResourceBridge("1.21.1", "neoforge"));
            modBus.addListener((RegisterClientReloadListenersEvent event) ->
                    event.registerReloadListener((ResourceManagerReloadListener) manager ->
                            DirectVfxEngine.INSTANCE.reloadResources()));
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
                DirectVfxEngine.INSTANCE.tickClient();
                com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onClientTick(
                        net.minecraft.client.Minecraft.getInstance().level);
            });
            NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
                if (event.getLevel().isClientSide()) DirectVfxEngine.INSTANCE.clearLevel();
            });
            NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
                if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
                Vec3 cam = event.getCamera().getPosition();
                float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
                DirectVfxEngine.INSTANCE.renderLevel(
                        new Matrix4f(event.getProjectionMatrix()),
                        new Matrix4f(event.getModelViewMatrix()),
                        cam.x, cam.y, cam.z, partial);
            });
        }
    }
}
