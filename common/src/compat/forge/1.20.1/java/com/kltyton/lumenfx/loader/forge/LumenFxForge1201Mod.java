package com.kltyton.lumenfx.loader.forge;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import com.kltyton.lumenfx.runtime.bridge.McLegacyResourceBridge;
import com.kltyton.lumenfx.runtime.simulation.ServerForceAuthority;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.joml.Matrix4f;

@Mod(LumenFx.MOD_ID)
public final class LumenFxForge1201Mod {
    @SuppressWarnings("removal")
    public LumenFxForge1201Mod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) ServerForceAuthority.tickServer();
        });
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientInit.register(modBus);
        }
    }

    private static final class ClientInit {
        static void register(IEventBus modBus) {
            DirectVfxEngine.INSTANCE.attachBridge(new McLegacyResourceBridge("1.20.1", "forge"));
            modBus.addListener((RegisterClientReloadListenersEvent event) ->
                    event.registerReloadListener((ResourceManagerReloadListener) manager ->
                            DirectVfxEngine.INSTANCE.reloadResources()));
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
                if (event.phase == TickEvent.Phase.END) {
                    DirectVfxEngine.INSTANCE.tickClient();
                    com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onClientTick(
                            net.minecraft.client.Minecraft.getInstance().level);
                }
            });
            MinecraftForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
                if (event.getLevel().isClientSide()) DirectVfxEngine.INSTANCE.clearLevel();
            });
            MinecraftForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
                if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
                Vec3 cam = event.getCamera().getPosition();
                DirectVfxEngine.INSTANCE.renderLevel(
                        new Matrix4f(event.getProjectionMatrix()),
                        new Matrix4f(event.getPoseStack().last().pose()),
                        cam.x, cam.y, cam.z, event.getPartialTick());
            });
        }
    }
}
