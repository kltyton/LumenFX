package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.resource.EffectResources;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Feedback;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.StreamingMips;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.Volume;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePlacement;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePlacement.SceneProfile;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.MainPass;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Settings;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Bounds;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Registration;
import com.kltyton.lumenfx.client.render.texture.virtual.AuthoredRuntimeVirtualTextureScene;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureMaterialPass;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureVolume;
import com.kltyton.lumenfx.client.render.texture.virtual.RuntimeVirtualTexturePrograms;
import com.kltyton.lumenfx.client.render.texture.virtual.MaterialVirtualTextureBindings;
import com.kltyton.lumenfx.client.resource.shader.PreparedRuntimeVirtualTextureShaders;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Resource-generation ownership for cooked VT directories and their render-thread residency. */
public final class VirtualTextureResources {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_IO_BATCHES = 4;
    private static final int MAX_SCENE_PAGES = 32;
    private static final Semaphore IO_BUDGET = new Semaphore(MAX_IO_BATCHES);
    private final Map<Identifier, Map<String, VirtualTextureTileSource>> sources;
    private final Map<Identifier, VirtualTextureTileSource> sourcesByResource;
    private final Map<Identifier, Map<String, RuntimeVirtualTextureDefinition>> runtimeDefinitions;
    private final Map<Identifier, Map<String, RuntimeVirtualTexturePlacement>> placements;
    private final Map<Identifier, Map<String, Settings>> primitives;
    private final Map<Identifier, StreamedVirtualTexture> resident = new HashMap<>();
    private final Map<Identifier, Map<String, NiagaraStreamingVirtualTexture>> bindings = new HashMap<>();
    private final Map<ClientLevel, Map<String, NiagaraRuntimeVirtualTexture>> volumes = new IdentityHashMap<>();
    private final Map<ClientLevel, AuthoredRuntimeVirtualTextureScene> scenes = new IdentityHashMap<>();
    private final Set<RuntimeVirtualTextureVolume> hiddenVolumes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final RuntimeVirtualTexturePrograms programs;
    private final MaterialVirtualTextureBindings materialReads = new MaterialVirtualTextureBindings();

    public VirtualTextureResources(Map<Identifier, Map<String, VirtualTextureTileSource>> sources) {
        this(sources, Map.of(), Map.of(), Map.of(), new PreparedRuntimeVirtualTextureShaders(Map.of()));
    }

    public VirtualTextureResources(Map<Identifier, Map<String, VirtualTextureTileSource>> sources,
                                   Map<Identifier, Map<String, RuntimeVirtualTextureDefinition>> runtimeDefinitions,
                                   Map<Identifier, Map<String, RuntimeVirtualTexturePlacement>> placements,
                                   Map<Identifier, Map<String, Settings>> primitives,
                                   PreparedRuntimeVirtualTextureShaders prepared) {
        this.sources = Map.copyOf(sources);
        var byResource = new HashMap<Identifier, VirtualTextureTileSource>();
        for (var paths : sources.values()) for (var source : paths.values()) byResource.put(source.id(), source);
        sourcesByResource = Map.copyOf(byResource);
        this.runtimeDefinitions = Map.copyOf(runtimeDefinitions);
        this.placements = Map.copyOf(placements);
        this.primitives = Map.copyOf(primitives);
        this.programs = new RuntimeVirtualTexturePrograms(prepared);
    }

    public static Map<String, RuntimeVirtualTextureDefinition> prepareRuntime(JsonObject effect) throws IOException {
        JsonObject niagara = EffectResources.jsonObject(effect, "niagara");
        JsonObject metadata = EffectResources.jsonObject(niagara, "runtime_virtual_texture_assets");
        if (metadata == null) return Map.of();
        var values = new TreeMap<String, RuntimeVirtualTextureDefinition>(SourceNames.ASCII_CASE_INSENSITIVE);
        for (var entry : metadata.entrySet()) {
            if (values.putIfAbsent(entry.getKey(), RuntimeVirtualTextureDefinition.read(entry.getValue().getAsJsonObject())) != null)
                throw new IOException("Ambiguous runtime virtual texture object path " + entry.getKey());
        }
        return Collections.unmodifiableMap(values);
    }

    public static Map<String, RuntimeVirtualTexturePlacement> preparePlacements(JsonObject effect) throws IOException {
        JsonObject niagara = EffectResources.jsonObject(effect, "niagara");
        JsonObject metadata = EffectResources.jsonObject(niagara, "runtime_virtual_texture_components");
        if (metadata == null) return Map.of();
        var values = new TreeMap<String, RuntimeVirtualTexturePlacement>(SourceNames.ASCII_CASE_INSENSITIVE);
        for (var entry : metadata.entrySet()) {
            var placement = RuntimeVirtualTexturePlacementJson.read(entry.getValue().getAsJsonObject());
            if (values.putIfAbsent(entry.getKey(), placement) != null)
                throw new IOException("Ambiguous runtime virtual texture component " + entry.getKey());
        }
        return Collections.unmodifiableMap(values);
    }

    public static Map<String, Settings> preparePrimitives(JsonObject effect) throws IOException {
        JsonObject niagara = EffectResources.jsonObject(effect, "niagara");
        JsonObject metadata = EffectResources.jsonObject(niagara, "runtime_virtual_texture_primitives");
        if (metadata == null) return Map.of();
        var values = new TreeMap<String, Settings>(SourceNames.ASCII_CASE_INSENSITIVE);
        for (var entry : metadata.entrySet()) {
            var data = entry.getValue().getAsJsonObject();
            var targets = new HashSet<String>();
            for (var target : data.getAsJsonArray("targets")) targets.add(target.getAsString());
            var settings = new Settings(targets, MainPass.valueOf(data.get("main_pass").getAsString()),
                    data.get("lod_bias").getAsInt(), data.get("skip_mips").getAsInt(),
                    data.get("minimum_coverage").getAsInt(), data.get("priority").getAsInt());
            if (values.putIfAbsent(entry.getKey(), settings) != null)
                throw new IOException("Ambiguous RVT primitive " + entry.getKey());
        }
        return Collections.unmodifiableMap(values);
    }

    public static Map<String, VirtualTextureTileSource> prepare(ResourceManager resources, JsonObject effect,
                                                               Map<Identifier, VirtualTextureTileSource> shared) throws IOException {
        JsonObject niagara = EffectResources.jsonObject(effect, "niagara");
        JsonObject metadata = EffectResources.jsonObject(niagara, "virtual_texture_assets");
        if (metadata == null) return Map.of();
        var values = new TreeMap<String, VirtualTextureTileSource>(SourceNames.ASCII_CASE_INSENSITIVE);
        for (var entry : metadata.entrySet()) {
            var id = Identifier.parse(entry.getValue().getAsString());
            VirtualTextureTileSource source = shared.get(id);
            if (source == null) {
                source = VirtualTextureTileSource.read(resources, id);
                shared.put(id, source);
            }
            if (values.putIfAbsent(entry.getKey(), source) != null)
                throw new IOException("Ambiguous virtual texture object path " + entry.getKey());
        }
        return Collections.unmodifiableMap(values);
    }

    public Map<String, NiagaraStreamingVirtualTexture> bindings(Identifier effect) {
        var paths = sources.get(effect);
        if (paths == null) return Map.of();
        return bindings.computeIfAbsent(effect, ignored -> {
            var values = new TreeMap<String, NiagaraStreamingVirtualTexture>(SourceNames.ASCII_CASE_INSENSITIVE);
            paths.forEach((path, source) -> values.put(path, streamed(source.id())));
            return Collections.unmodifiableMap(values);
        });
    }

    public boolean request(Identifier effect, String path, Feedback feedback) {
        var texture = bindings(effect).get(path);
        if (texture == null) return false;
        texture.request(feedback);
        return true;
    }

    public Map<String, NiagaraRuntimeVirtualTexture> runtimeBindings(ClientLevel level) {
        return Collections.unmodifiableMap(worldVolumes(level));
    }

    public MaterialVirtualTextureBindings materialReads() { return materialReads; }

    public void beginMaterialReads(ClientLevel level, int temporalIndex, int width, int height) {
        var placed = volumes.getOrDefault(level, Map.of());
        materialReads.begin(placed::get, this::streamed, temporalIndex, width, height);
    }

    private StreamedVirtualTexture streamed(Identifier id) {
        var source = sourcesByResource.get(id);
        if (source == null) throw new IllegalArgumentException("Unknown streamed virtual texture resource " + id);
        return resident.computeIfAbsent(id, ignored -> new StreamedVirtualTexture(source, IO_BUDGET));
    }

    public RuntimeVirtualTexturePlacement placement(Identifier effect, String component) {
        var placement = placements.getOrDefault(effect, Map.of()).get(component.replace(':', '.'));
        if (placement == null) throw new IllegalArgumentException("Unknown runtime virtual texture component " + component + " for " + effect);
        return placement;
    }

    public @Nullable RuntimeVirtualTextureVolume placeAuthored(ClientLevel level, Identifier effect, String component,
                                                               SceneProfile profile, int tileSizeBias, int tileCountBias,
                                                               RuntimeVirtualTexturePageRenderer renderer) {
        var placement = placement(effect, component);
        if (!placement.enabled(profile)) return null;
        var streaming = profile.streamedMips() ? placement.streaming() : null;
        RuntimeVirtualTextureVolume volume;
        try {
            volume = place(level, effect, placement.source(), placement.volume(), tileSizeBias, tileCountBias,
                    placement.componentBias(profile), renderer, streaming);
        } catch (RuntimeVirtualTextureLowMips.IncompatibleLayout mismatch) {
            LOGGER.warn("RVT component {} has incompatible baked mips and will render live pages: {}", component, mismatch.getMessage());
            volume = place(level, effect, placement.source(), placement.volume(), tileSizeBias, tileCountBias,
                    placement.componentBias(profile), renderer);
        }
        if (placement.hidePrimitives()) hiddenVolumes.add(volume);
        return volume;
    }

    public @Nullable RuntimeVirtualTextureVolume placeAuthored(ClientLevel level, Identifier effect, String component,
                                                               SceneProfile profile, int tileSizeBias, int tileCountBias) {
        var placement = placement(effect, component);
        if (!placement.enabled(profile)) return null;
        return placeAuthored(level, effect, component, profile, tileSizeBias, tileCountBias,
                programs.renderer(scene(level).materialPass(placement.source())));
    }

    public Registration registerPrimitive(ClientLevel level, Identifier effect, String component,
                                          RuntimeVirtualTexturePrimitive geometry, Bounds bounds) {
        var settings = primitives.getOrDefault(effect, Map.of()).get(component.replace(':', '.'));
        if (settings == null) throw new IllegalArgumentException("Unknown RVT primitive " + component + " for " + effect);
        return scene(level).register(settings, geometry, bounds);
    }

    private AuthoredRuntimeVirtualTextureScene scene(ClientLevel level) {
        return scenes.computeIfAbsent(level, world -> new AuthoredRuntimeVirtualTextureScene(world, (target, bounds) -> {
            var producer = volumes.getOrDefault(world, Map.of()).get(target);
            if (producer instanceof RuntimeVirtualTextureProducer texture) texture.invalidateWorld(bounds);
        }, () -> volumes.getOrDefault(world, Map.of()).values().stream().anyMatch(hiddenVolumes::contains)));
    }

    public RuntimeVirtualTextureVolume place(ClientLevel level, Identifier effect, String path, Volume volume,
                                             int tileSizeBias, int tileCountBias, int componentBias,
                                             RuntimeVirtualTexturePageRenderer renderer) {
        return place(level, effect, path, volume, tileSizeBias, tileCountBias, componentBias, renderer, null);
    }

    public RuntimeVirtualTextureVolume place(ClientLevel level, Identifier effect, String path, Volume volume,
                                             int tileSizeBias, int tileCountBias, int componentBias,
                                             RuntimeVirtualTexturePageRenderer renderer, @Nullable StreamingMips streaming) {
        var definition = runtimeDefinitions.getOrDefault(effect, Map.of()).get(path);
        if (definition == null) throw new IllegalArgumentException("Unknown runtime virtual texture " + path + " for " + effect);
        var baked = streaming == null ? null : sources.getOrDefault(effect, Map.of()).get(streaming.source());
        if (streaming != null && baked == null)
            throw new IllegalArgumentException("Unknown cooked RVT streaming texture " + streaming.source() + " for " + effect);
        var placed = worldVolumes(level);
        var texture = new RuntimeVirtualTextureProducer(path, level, definition, volume,
                definition.layout(volume, tileSizeBias, tileCountBias, componentBias), renderer,
                baked, streaming != null && streaming.only(), IO_BUDGET,
                closing -> {
                    placed.remove(path, closing);
                    hiddenVolumes.remove(closing);
                });
        var previous = (RuntimeVirtualTextureProducer)placed.put(path, texture);
        if (previous != null) previous.close();
        return texture;
    }

    public RuntimeVirtualTextureVolume place(ClientLevel level, Identifier effect, String path, Volume volume,
                                             int tileSizeBias, int tileCountBias, int componentBias,
                                             RuntimeVirtualTextureMaterialPass material) {
        return place(level, effect, path, volume, tileSizeBias, tileCountBias, componentBias, programs.renderer(material));
    }

    public void renderScenePages(ClientLevel level) {
        var placed = volumes.get(level);
        if (placed == null || placed.isEmpty()) return;
        int remaining = MAX_SCENE_PAGES;
        var producers = placed.values().stream().map(RuntimeVirtualTextureProducer.class::cast)
                .sorted(Comparator.comparing(RuntimeVirtualTextureProducer::priority).reversed()).toList();
        for (var producer : producers) remaining -= producer.render(remaining);
    }

    public void clearWorld(ClientLevel level) {
        materialReads.invalidateWorld();
        clearStreaming();
        closeVolumes(volumes.remove(level));
        var scene = scenes.remove(level);
        if (scene != null) scene.close();
    }

    public boolean hasResidency() { return !resident.isEmpty() || !volumes.isEmpty() || !scenes.isEmpty(); }

    public void clearResidency() {
        clearStreaming();
        for (var world : volumes.values()) closeVolumes(world);
        volumes.clear();
        scenes.values().forEach(AuthoredRuntimeVirtualTextureScene::close);
        scenes.clear();
        programs.close();
        materialReads.close();
    }

    private void clearStreaming() {
        resident.values().forEach(StreamedVirtualTexture::close);
        resident.clear();
        bindings.clear();
    }

    private Map<String, NiagaraRuntimeVirtualTexture> worldVolumes(ClientLevel level) {
        return volumes.computeIfAbsent(level, ignored -> new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE));
    }

    private static void closeVolumes(Map<String, NiagaraRuntimeVirtualTexture> world) {
        if (world == null) return;
        for (var texture : List.copyOf(world.values())) ((RuntimeVirtualTextureProducer)texture).close();
    }
}
