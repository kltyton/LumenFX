package com.kltyton.lumenfx.common.force;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.effect.simulation.UnityEffectSimulation;
import com.kltyton.lumenfx.platform.LumenPackPlatform;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;

/** Resolves gameplay-authored definitions from server-selected data and installed mod assets. */
final class ServerUnityAssets {
    private static final FileToIdConverter FILES = FileToIdConverter.json("lumenfx/effects");
    private static final Map<Identifier, JsonObject> registered = new ConcurrentHashMap<>();
    private static final Map<Identifier, UnityEffectSimulation.Definition> prepared = new ConcurrentHashMap<>();
    private static final Map<ResourceManager, Map<Identifier, UnityEffectSimulation.Definition>> byGeneration = new WeakHashMap<>();

    private ServerUnityAssets() { }

    static synchronized void register(Identifier id, JsonObject root) {
        registered.put(id, root.deepCopy());
        prepared.remove(id);
        for (var cache : byGeneration.values()) cache.remove(id);
    }

    static synchronized void register(Identifier id, UnityEffectSimulation.Definition definition) {
        prepared.put(id, definition);
        registered.remove(id);
        for (var cache : byGeneration.values()) cache.remove(id);
    }

    static synchronized void clear() {
        registered.clear();
        prepared.clear();
        byGeneration.clear();
    }

    static synchronized UnityEffectSimulation.Definition resolve(ResourceManager data, Identifier id) {
        var explicit = prepared.get(id);
        if (explicit != null) return explicit;
        var cache = byGeneration.computeIfAbsent(data, ignored -> new HashMap<>());
        return cache.computeIfAbsent(id, ignored -> withModAssets(assets -> {
            ResourceProvider inputs = key -> data.getResource(key).or(() -> assets.getResource(key));
            var json = registered.get(id);
            if (json == null) {
                Identifier path = FILES.idToFile(id);
                try (var reader = inputs.getResourceOrThrow(path).openAsReader()) {
                    json = JsonParser.parseReader(reader).getAsJsonObject();
                } catch (IOException failure) {
                    throw new UncheckedIOException("Cannot load trusted Unity collision effect " + id, failure);
                }
            }
            return UnityEffectSimulation.read(json, inputs);
        }));
    }

    private static <T> T withModAssets(Function<ResourceProvider, T> action) {
        // This pack view only reads installed server mod files. Player resource packs never feed simulation.
        List<PackResources> packs = LumenPackPlatform.openInstalledModAssetPacks();
        try (var assets = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs)) {
            return action.apply(assets);
        }
    }
}
