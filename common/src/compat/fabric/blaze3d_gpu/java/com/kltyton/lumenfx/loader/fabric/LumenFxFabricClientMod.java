package com.kltyton.lumenfx.loader.fabric;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.event.ClientVfxEvents;
import com.kltyton.lumenfx.client.event.WorldFxClientEvents;
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
import com.lowdragmc.photon.integration.PhotonLDLibPlugin;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.particle.v1.ParticleGroupRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

public final class LumenFxFabricClientMod implements ClientModInitializer {
    private static ClientLevel lastClientLevel;

    @Override
    public void onInitializeClient() {
        new PhotonLDLibPlugin().onLoad();
        WorldFxClientEvents.ensureInitialized();
        ClientVfxEvents.initializeShaderpackBridge();

        ParticleGroupRegistry.register(PhotonParticleRenderTypes.FX, PhotonParticleGroup::new);
        FeatureRendererRegistry.register(PhotonFeatureRenderer.TYPE, PhotonFeatureRenderer::new);
        PhotonPipelines.register(RenderPipelines::register);
        DynamicLightRenderer.register(RenderPipelines::register);

        var loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        var effects = LumenFx.id("effects");
        var meshes = LumenFx.id("photon_mesh_cache");
        var assets = LumenFx.id("photon_asset_library");
        var materials = LumenFx.id("photon_material_reload");
        var preloader = LumenFx.id("photon_fx_preload");
        loader.registerReloadListener(effects, new EffectResources());
        loader.addListenerOrdering(ResourceReloaderKeys.Client.TEXTURES, effects);
        loader.addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, effects);
        loader.registerReloadListener(meshes, PhotonMeshCache.INSTANCE);
        loader.registerReloadListener(assets, PhotonAssetLibrary.INSTANCE);
        loader.registerReloadListener(materials, (ResourceManagerReloadListener) manager -> PhotonRenderTypes.onResourceReload());
        loader.registerReloadListener(preloader, (ResourceManagerReloadListener) manager -> FXPreloader.onResourcesReloaded());
        loader.addListenerOrdering(effects, preloader);
        loader.addListenerOrdering(meshes, preloader);
        loader.addListenerOrdering(assets, preloader);
        loader.addListenerOrdering(materials, preloader);
        loader.addListenerOrdering(ResourceReloaderKeys.Client.TEXTURES, preloader);
        loader.addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, materials);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientVfxEvents.onClientTickPost();
            if (client.level == null && lastClientLevel != null) {
                WorldFxClientEvents.onLevelUnload(lastClientLevel);
                lastClientLevel = null;
            }
        });
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            if (lastClientLevel != null && lastClientLevel != level) {
                WorldFxClientEvents.onLevelUnload(lastClientLevel);
            }
            lastClientLevel = level;
        });
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            ClientVfxEvents.onChunkChanged(chunk);
            WorldFxClientEvents.onChunkChanged(level, chunk.getPos());
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            ClientVfxEvents.onChunkChanged(chunk);
            WorldFxClientEvents.onChunkChanged(level, chunk.getPos());
        });
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (client) WorldFxClientEvents.onClientTagsUpdated();
        });

        LevelExtractionEvents.END_EXTRACTION.register(context ->
                ClientVfxEvents.onExtractLevelRenderState(context.level(), context.levelState(), context.deltaTracker()));
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(context ->
                ClientVfxEvents.onAfterOpaqueFeatures(context.levelState()));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context ->
                ClientVfxEvents.onAfterTranslucentParticles(context.levelState()));
        LevelRenderEvents.END_MAIN.register(context ->
                ClientVfxEvents.onAfterLevelFromCurrent(context.levelState()));
    }
}
