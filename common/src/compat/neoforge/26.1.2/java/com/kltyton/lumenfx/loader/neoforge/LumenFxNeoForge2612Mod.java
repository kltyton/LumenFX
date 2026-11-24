package com.kltyton.lumenfx.loader.neoforge;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import com.kltyton.lumenfx.runtime.bridge.Mc2612ResourceBridge;
import com.kltyton.lumenfx.runtime.simulation.ServerForceAuthority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Matrix4f;

@Mod(LumenFx.MOD_ID)
public final class LumenFxNeoForge2612Mod {
    public LumenFxNeoForge2612Mod(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> ServerForceAuthority.tickServer());
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientInit.register(modBus);
        }
    }

    private static final class ClientInit {
        static void register(IEventBus modBus) {
            DirectVfxEngine.INSTANCE.attachBridge(new Mc2612ResourceBridge("neoforge"));
            modBus.addListener((AddClientReloadListenersEvent event) ->
                    event.addListener(LumenFx.id("direct_vfx"),
                            (ResourceManagerReloadListener) manager -> DirectVfxEngine.INSTANCE.reloadResources()));
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> DirectVfxEngine.INSTANCE.tickClient());
            NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
                if (event.getLevel().isClientSide()) DirectVfxEngine.INSTANCE.clearLevel();
            });
            NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentParticles event) -> {
                CameraRenderState cam = event.getLevelRenderState().cameraRenderState;
                if (cam == null || !cam.initialized) return;
                float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
                DirectVfxEngine.INSTANCE.renderLevel(
                        new Matrix4f(cam.projectionMatrix),
                        new Matrix4f(event.getModelViewMatrix()),
                        cam.pos.x, cam.pos.y, cam.pos.z, partial);
            });
        }
    }
}
