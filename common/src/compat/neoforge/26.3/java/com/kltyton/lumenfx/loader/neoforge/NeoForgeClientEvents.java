package com.kltyton.lumenfx.loader.neoforge;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.event.ClientVfxEvents;
import com.kltyton.lumenfx.client.event.WorldFxClientEvents;
import com.kltyton.lumenfx.client.render.temporal.view.ViewFrameClockEvents;
import com.kltyton.lumenfx.client.resource.EffectResources;
import com.lowdragmc.photon.client.fx.FXPreloader;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMeshCache;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.render.PhotonFeatureRenderer;
import com.lowdragmc.photon.client.render.PhotonParticleGroup;
import com.lowdragmc.photon.client.render.PhotonParticleRenderTypes;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.lowdragmc.photon.client.resource.PhotonAssetLibrary;
import com.lowdragmc.photon.client.resource.PhotonPackSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterFeatureRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleGroupsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.client.resources.VanillaClientListeners;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = LumenFx.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientEvents {
    private NeoForgeClientEvents() { }

    @SubscribeEvent
    public static void soundEngineLoaded(SoundEngineLoadEvent event) {
        ClientVfxEvents.onSoundEngineLoaded();
    }

    @SubscribeEvent
    public static void registerParticleGroup(RegisterParticleGroupsEvent event) {
        event.register(PhotonParticleRenderTypes.FX, PhotonParticleGroup::new);
    }

    @SubscribeEvent
    public static void registerFeatureRenderer(RegisterFeatureRenderersEvent event) {
        event.register(PhotonFeatureRenderer.TYPE, new PhotonFeatureRenderer());
    }

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        PhotonPipelines.register(event::registerPipeline);
        DynamicLightRenderer.register(event::registerPipeline);
    }

    @SubscribeEvent
    public static void registerPackSource(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            event.addRepositorySource(PhotonPackSource.installedPacks());
        }
    }

    @SubscribeEvent
    public static void registerResources(AddClientReloadListenersEvent event) {
        com.lowdragmc.photon.integration.PhotonLDLibPlugin.ensureLoaded();
        ClientVfxEvents.initializeShaderpackBridge();
        var effects = LumenFx.id("effects");
        var meshes = LumenFx.id("photon_mesh_cache");
        var assets = LumenFx.id("photon_asset_library");
        var materials = LumenFx.id("photon_material_reload");
        var preloader = LumenFx.id("photon_fx_preload");
        event.addListener(effects, new EffectResources());
        event.addDependency(VanillaClientListeners.TEXTURES, effects);
        event.addDependency(VanillaClientListeners.SHADERS, effects);
        event.addListener(meshes, PhotonMeshCache.INSTANCE);
        event.addListener(assets, PhotonAssetLibrary.INSTANCE);
        event.addListener(materials, (ResourceManagerReloadListener) manager -> PhotonRenderTypes.onResourceReload());
        event.addListener(preloader, (ResourceManagerReloadListener) manager -> FXPreloader.onResourcesReloaded());
        event.addDependency(effects, preloader);
        event.addDependency(meshes, preloader);
        event.addDependency(assets, preloader);
        event.addDependency(materials, preloader);
        event.addDependency(VanillaClientListeners.TEXTURES, preloader);
        event.addDependency(VanillaClientListeners.SHADERS, materials);
    }

    @SubscribeEvent
    public static void prepareFrame(RenderFrameEvent.Pre event) {
        ViewFrameClockEvents.begin();
        WorldFxClientEvents.onFrameStart();
    }

    @SubscribeEvent
    public static void finishFrame(RenderFrameEvent.Post event) {
        WorldFxClientEvents.onFrameEnd();
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        ClientVfxEvents.onChunkChanged(event.getChunk());
        if (event.getLevel() instanceof ClientLevel level) {
            WorldFxClientEvents.onChunkChanged(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        ClientVfxEvents.onChunkChanged(event.getChunk());
        if (event.getLevel() instanceof ClientLevel level) {
            WorldFxClientEvents.onChunkChanged(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent.ClientPacketReceived event) {
        WorldFxClientEvents.onClientTagsUpdated();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level) {
            WorldFxClientEvents.onLevelUnload(level);
        }
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        ClientVfxEvents.onExtractLevelRenderState(event.getLevel(), event.getRenderState(), event.getDeltaTracker());
    }

    @SubscribeEvent
    public static void afterOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        ClientVfxEvents.onAfterOpaqueFeatures(event.getLevelRenderState());
    }

    @SubscribeEvent
    public static void afterTranslucentParticles(RenderLevelStageEvent.AfterTranslucentParticles event) {
        ClientVfxEvents.onAfterTranslucentParticles(event.getLevelRenderState());
    }

    @SubscribeEvent
    public static void afterLevel(RenderLevelStageEvent.AfterLevel event) {
        ClientVfxEvents.onAfterLevel(event.getLevelRenderState(), event.getModelViewMatrix());
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        ClientVfxEvents.onClientTickPost();
    }
}
