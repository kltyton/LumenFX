/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.fx;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.fixer.PhotonFXProjectDataFixer;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Loads and caches {@link FX} definitions from {@code assets/<ns>/fx/<path>.fx} resources (resource
 * packs and mod jars).
 * <p>
 * The id passed to {@link #getFX} omits the {@code fx/} prefix and {@code .fx} suffix:
 * {@code photon:example} → {@code assets/photon/fx/example.fx}.
 */
@ParametersAreNonnullByDefault
public class FXHelper {
    // concurrent: sub-emitter spawns may query the cache while other threads do (never mutate mid-load;
    // loadFX does not re-enter getFX, so computeIfAbsent cannot recurse)
    private final static Map<Identifier, FX> CACHE = new ConcurrentHashMap<>();
    private static volatile Map<Identifier, Resource> available = Map.of();
    private static final AtomicInteger generation = new AtomicInteger();
    private static final FileToIdConverter EFFECT_FILES = new FileToIdConverter("fx", FX.SUFFIX);
    public static Map<Identifier, Resource> index(ResourceManager resources) {
        return EFFECT_FILES.listMatchingResources(resources).entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(entry -> EFFECT_FILES.fileToId(entry.getKey()), Map.Entry::getValue));
    }

    /** Apply the Photon index after the owner publishes the matching effect resource generation. */
    public static void publishIndex(Map<Identifier, Resource> definitions) {
        CACHE.clear();
        FXInstances.clear();
        available = Map.copyOf(definitions);
        generation.incrementAndGet();
    }

    /**
     * Forget these effects, e.g. after the pack providing them was rewritten, so the next play loads them
     * again. Other loaded effects stay cached; preloads under way start
     * over, so none of them publishes what it read before, and kept effects are preloaded again in the
     * background.
     */
    public static void invalidate(Collection<Identifier> fxLocations) {
        // and every effect that places one of them as an FX instance, at any depth
        FXInstances.invalidate(fxLocations).forEach(CACHE::remove);
        fxLocations.forEach(CACHE::remove);
        generation.incrementAndGet();
        FXPreloader.onInvalidated();
    }

    /** The resource an fx id is read from: {@code photon:example} → {@code photon:fx/example.fx}. */
    public static Identifier fileLocation(Identifier fxLocation) {
        return EFFECT_FILES.idToFile(fxLocation);
    }

    /** Bumped whenever loaded effects are forgotten. */
    public static int generation() {
        return generation.get();
    }

    /**
     * Preload an effect in the background, so its first play does not stall on loading. Warms the shader
     * variants it draws with, its textures and the effects its sub-emitters spawn: file reads and image
     * decoding run on worker threads, GL work runs on the render thread a few milliseconds per frame.
     * <p>
     * Callable from any thread. The future completes on the render thread with the cached definition, or null
     * if the effect cannot be loaded. Never {@code join()} it on the render thread; use {@link #preloadNow} there.
     */
    public static CompletableFuture<FX> preload(Identifier fxLocation) {
        return FXPreloader.preload(fxLocation);
    }

    /** A world play request belongs to the generation that accepted its handle. */
    public static CompletableFuture<FX> preloadForPlayback(Identifier fxLocation, int generation) {
        return FXPreloader.preload(fxLocation, generation).thenApply(result -> result.stale() ? null : result.effect());
    }

    /** {@link #preload(Identifier)} for several effects; completes once all of them are warm. */
    public static CompletableFuture<Void> preload(Collection<Identifier> fxLocations) {
        return FXPreloader.preload(fxLocations);
    }

    /**
     * {@link #preload(Identifier)} done synchronously: everything is warm when it returns. Render thread
     * only, e.g. behind a loading screen of your own.
     *
     * @return the cached definition, or null if the effect cannot be loaded
     */
    @Nullable
    public static FX preloadNow(Identifier fxLocation) {
        return FXPreloader.preloadNow(fxLocation);
    }

    /**
     * Keep effects warm for the whole session. A resource reload drops every loaded effect, so these are
     * preloaded again during each reload, behind the loading screen. Once the game has loaded, newly kept
     * effects are also preloaded right away in the background. Callable from any thread, including mod
     * construction and setup.
     */
    public static void keepPreloaded(Identifier... fxLocations) {
        FXPreloader.keep(List.of(fxLocations));
    }

    /** {@link #keepPreloaded(Identifier...)} for a collection. */
    public static void keepPreloaded(Collection<Identifier> fxLocations) {
        FXPreloader.keep(fxLocations);
    }

    /** The cached definition of an effect, or null if it has not been loaded. Never loads it. */
    @Nullable
    public static FX getCachedFX(Identifier fxLocation) {
        return CACHE.get(fxLocation);
    }

    @Nullable
    public static FX getFX(Identifier fxLocation) {
        return getFX(fxLocation, true);
    }

    /** Effect discovery uses the index published by the resource reload. */
    public static boolean contains(Identifier id) {
        return available.containsKey(id);
    }


    @Nullable
    public static FX getFX(Identifier fxLocation, boolean useCache) {
        return useCache ? CACHE.computeIfAbsent(fxLocation, location -> loadFX(fxLocation)) : loadFX(fxLocation);
    }

    @Nullable
    private static FX loadFX(Identifier fxLocation) {
        var tag = readTag(fxLocation);
        return tag == null ? null : deserialize(fxLocation, tag);
    }

    @Nullable
    public static CompoundTag readTag(Identifier fxLocation) {
        var resource = available.get(fxLocation);
        if (resource == null) {
            Photon.LOGGER.warn("Effect {} is absent from the current resource packs", fxLocation);
            return null;
        }
        try (var inputStream = resource.open()) {
            var tag = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
            tag = applyVersionFixes(tag);
            if (tag.getCompound("resources").isPresent()) {
                // short-lived dev format that embedded assets in the .fx — superseded by .fxpack
                Photon.LOGGER.warn("fx {} carries an embedded 'resources' section, which is no longer "
                        + "supported — re-export it (assets travel in .fxpack files now)", fxLocation);
            }
            return tag;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to load fx {} from {}", fxLocation, resource.sourcePackId(), e);
            return null;
        }
    }

    /** Render thread only: a custom shader material compiles while it deserializes. */
    @Nullable
    static FX publish(Identifier fxLocation, CompoundTag tag) {
        var cached = CACHE.get(fxLocation);
        if (cached != null) return cached;
        var fx = deserialize(fxLocation, tag);
        if (fx == null) return null;
        var existing = CACHE.putIfAbsent(fxLocation, fx);
        return existing != null ? existing : fx;
    }

    @Nullable
    private static FX deserialize(Identifier fxLocation, CompoundTag tag) {
        try {
            var fx = new FX();
            fx.setFxLocation(fxLocation);
            fx.deserializeNBT(Platform.getFrozenRegistry(), tag);
            return fx;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to load fx {}", fxLocation, e);
            return null;
        }
    }

    /**
     * Run the project datafixer on a versioned {@code .fx} root tag. Files without a {@code version}
     * (everything exported before versioning) pass through untouched: they span all historical
     * versions and the deserializers' inline legacy fallbacks already handle them — running the
     * V1→current fixers on data of unknown true version would risk double-migration.
     */
    public static CompoundTag applyVersionFixes(CompoundTag tag) {
        if (tag.getInt("version").isEmpty()) {
            return tag;
        }
        int version = tag.getIntOr("version", 0);
        if (version >= FX.FORMAT_VERSION) {
            return tag;
        }
        // the fixers navigate the project "data" shape {fx: {fxData: ...}} — wrap, fix, unwrap
        var wrapped = new CompoundTag();
        wrapped.put("fx", tag);
        var fixed = PhotonFXProjectDataFixer.INSTANCE.applyFixes(version, FX.FORMAT_VERSION, wrapped).getCompoundOrEmpty("fx");
        fixed.putInt("version", FX.FORMAT_VERSION);
        return fixed;
    }

}
