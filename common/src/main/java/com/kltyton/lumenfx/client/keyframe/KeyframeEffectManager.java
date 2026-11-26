package com.kltyton.lumenfx.client.keyframe;

import com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectParams;
import com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectRequest;
import com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared client-side coordinator binding GeckoLib (4.x / 5.x) and KBR animation particle keyframes
 * to LumenFX effect handles with per-frame 3D locator tracking and lifecycle cleanup.
 */
public final class KeyframeEffectManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("LumenFX/Keyframe");
    private static final long SEED_MIX = 0x9E3779B97F4A7C15L;

    private static volatile @Nullable KeyframeEffectPlaybackAdapter adapter;
    private static final AtomicBoolean AUTO_INTERCEPT = new AtomicBoolean(true);
    private static final AtomicLong SEQUENCE = new AtomicLong(1L);

    private static final List<LumenFxKeyframeBridge.Filter> FILTERS = new CopyOnWriteArrayList<>();
    private static final List<LumenFxKeyframeBridge.SpawnListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** Guarded by LOCK; weak host keys prevent retaining dead animatables if tick cleanup is bypassed. */
    private static final Object LOCK = new Object();
    private static final Map<Object, HostState> HOSTS = new WeakHashMap<>();

    private KeyframeEffectManager() { }

    public static void installAdapter(KeyframeEffectPlaybackAdapter playbackAdapter) {
        adapter = Objects.requireNonNull(playbackAdapter, "playbackAdapter");
    }

    public static @Nullable KeyframeEffectPlaybackAdapter adapter() {
        return adapter;
    }

    public static boolean isAutoInterceptEnabled() {
        return AUTO_INTERCEPT.get();
    }

    public static void setAutoInterceptEnabled(boolean enabled) {
        AUTO_INTERCEPT.set(enabled);
    }

    public static void addFilter(LumenFxKeyframeBridge.Filter filter) {
        FILTERS.add(filter);
    }

    public static void removeFilter(LumenFxKeyframeBridge.Filter filter) {
        FILTERS.remove(filter);
    }

    public static void addSpawnListener(LumenFxKeyframeBridge.SpawnListener listener) {
        LISTENERS.add(listener);
    }

    public static void removeSpawnListener(LumenFxKeyframeBridge.SpawnListener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Evaluates a particle keyframe event.
     *
     * @return {@code true} if LumenFX claimed/handled this keyframe; {@code false} so KBR or other handlers proceed untouched.
     */
    public static boolean handleKeyframe(@Nullable ClientLevel level,
                                         @Nullable Object host,
                                         @Nullable Entity entity,
                                         @Nullable Object controllerKey,
                                         @Nullable String controllerName,
                                         @Nullable String rawEffect,
                                         @Nullable String locator,
                                         @Nullable String preEffectScript,
                                         @Nullable Boolean bindToActor,
                                         double fallbackX, double fallbackY, double fallbackZ,
                                         boolean fromAutoHook) {
        if (fromAutoHook && !AUTO_INTERCEPT.get()) {
            return false;
        }
        KeyframeEffectPlaybackAdapter activeAdapter = adapter;
        if (activeAdapter == null || host == null) {
            return false;
        }

        LumenFxKeyframeBridge.ParsedEffectId parsed = LumenFxKeyframeBridge.parseEffectId(rawEffect);
        if (parsed == null) {
            return false;
        }
        String canonicalId = resolveCanonicalId(activeAdapter, rawEffect, parsed);
        boolean hasLocator = locator != null && !locator.isBlank();
        String normLocator = hasLocator ? locator.trim() : null;
        KeyframeEffectParams params = KeyframeEffectParams.parse(preEffectScript, bindToActor, hasLocator);

        if (params.stop()) {
            boolean stopped = stopMatching(host, controllerKey, parsed.explicitLumenFx() || activeAdapter.hasEffect(canonicalId) ? canonicalId : null, normLocator);
            return stopped || parsed.explicitLumenFx();
        }

        boolean exists = activeAdapter.hasEffect(canonicalId);
        if (!exists) {
            if (parsed.explicitLumenFx()) {
                LOGGER.warn("Explicit LumenFX particle keyframe '{}' not found in loaded effects", canonicalId);
                return true;
            }
            // Unknown to LumenFX -> hand back to KBR Bedrock/SnowStorm/3D particles or mod custom handler.
            return false;
        }

        KeyframeEffectRequest request = new KeyframeEffectRequest(
                host, entity, controllerName == null ? "" : controllerName,
                rawEffect, canonicalId, normLocator, params, parsed.explicitLumenFx());
        for (LumenFxKeyframeBridge.Filter filter : FILTERS) {
            try {
                if (!filter.allow(request) || request.isCancelled()) {
                    return false;
                }
            } catch (RuntimeException error) {
                LOGGER.warn("LumenFX keyframe filter threw an exception", error);
            }
        }
        if (request.isCancelled()) {
            return false;
        }

        if (level == null && activeAdapter.requiresLevel()) {
            return false;
        }

        String finalId = request.resolvedEffectId();
        KeyframeEffectParams finalParams = request.params();
        if (!activeAdapter.hasEffect(finalId)) {
            return parsed.explicitLumenFx();
        }

        if (finalParams.restart()) {
            stopMatching(host, controllerKey, finalId, normLocator);
        }

        double spawnX = fallbackX + finalParams.offsetX();
        double spawnY = fallbackY + finalParams.offsetY();
        double spawnZ = fallbackZ + finalParams.offsetZ();
        float qx = 0.0F, qy = 0.0F, qz = 0.0F, qw = 1.0F;
        boolean usedCachedLocator = false;

        synchronized (LOCK) {
            HostState state = HOSTS.computeIfAbsent(host, k -> new HostState(entity));
            if (entity != null) {
                state.entity = entity;
            }
            if (normLocator != null) {
                LocatorPose cached = state.lastLocatorPoses.get(normLocator);
                if (cached != null) {
                    spawnX = cached.x + finalParams.offsetX();
                    spawnY = cached.y + finalParams.offsetY();
                    spawnZ = cached.z + finalParams.offsetZ();
                    if (finalParams.rotate()) {
                        qx = cached.qx; qy = cached.qy; qz = cached.qz; qw = cached.qw;
                    }
                    usedCachedLocator = true;
                }
            }

            // If a locator is named and we haven't sampled its rendered pose yet, defer one-shot spawn to first locator sample
            // while still registering active tracking so RenderPassInfo/GeoBone hooks immediately listen for this locator.
            if (normLocator != null && !usedCachedLocator) {
                // Avoid duplicate pending bindings for the same slot unless restart was requested
                if (finalParams.follow()) {
                    for (ActiveBinding b : state.bindings) {
                        if (b.matches(controllerKey, finalId, normLocator) && b.isAliveOrPending()) {
                            return true;
                        }
                    }
                }
                ActiveBinding pending = new ActiveBinding(
                        level, controllerKey, finalId, normLocator, finalParams, request, null,
                        computeSeed(host, entity, finalId, normLocator, finalParams));
                state.bindings.add(pending);
                return true;
            }

            if (finalParams.follow() && normLocator != null) {
                for (ActiveBinding b : state.bindings) {
                    if (b.matches(controllerKey, finalId, normLocator) && b.isAliveOrPending()) {
                        // Refresh already-bound locator effect without spawning a duplicate
                        return true;
                    }
                }
            }

            long seed = computeSeed(host, entity, finalId, normLocator, finalParams);
            KeyframeEffectPlaybackAdapter.BoundHandle handle = activeAdapter.spawn(
                    level, finalId, spawnX, spawnY, spawnZ, seed, finalParams, finalParams.follow() && normLocator != null);
            if (handle == null) {
                return true;
            }
            float entityScale = resolveEntityScale(entity) * finalParams.scale();
            double[] actorPos = entity != null ? new double[] { entity.getX(), entity.getY(), entity.getZ() } : null;
            double[] actorVel = computeActorVelocityCmPerSec(entity);
            handle.updatePose(spawnX, spawnY, spawnZ, qx, qy, qz, qw, entityScale, actorPos, actorVel);

            if (finalParams.beamEndLocator() != null) {
                LocatorPose endPose = state.lastLocatorPoses.get(finalParams.beamEndLocator());
                if (endPose != null) {
                    handle.setBeamEnd(endPose.x, endPose.y, endPose.z);
                }
            }

            if (finalParams.follow()) {
                state.bindings.add(new ActiveBinding(
                        level, controllerKey, finalId, normLocator, finalParams, request, handle, seed));
            }
            notifySpawnListeners(request, handle.rawHandle());
        }
        return true;
    }

    /** Returns all locator names currently tracked (either active or pending) for {@code host}. */
    public static Set<String> trackedLocators(@Nullable Object host) {
        if (host == null) return Collections.emptySet();
        synchronized (LOCK) {
            HostState state = HOSTS.get(host);
            if (state == null || state.bindings.isEmpty()) return Collections.emptySet();
            Set<String> names = null;
            for (ActiveBinding binding : state.bindings) {
                if (!binding.isAliveOrPending()) continue;
                if (binding.locator != null) {
                    if (names == null) names = Collections.newSetFromMap(new IdentityHashMap<>());
                    // Use normal HashSet for string equality
                    break;
                }
                if (binding.params.beamEndLocator() != null) {
                    break;
                }
            }
            java.util.HashSet<String> result = new java.util.HashSet<>();
            for (ActiveBinding binding : state.bindings) {
                if (!binding.isAliveOrPending()) continue;
                if (binding.locator != null) result.add(binding.locator);
                if (binding.params.beamEndLocator() != null) result.add(binding.params.beamEndLocator());
            }
            return result;
        }
    }

    public static boolean hasTrackedLocators(@Nullable Object host) {
        if (host == null) return false;
        synchronized (LOCK) {
            HostState state = HOSTS.get(host);
            if (state == null || state.bindings.isEmpty()) return false;
            for (ActiveBinding binding : state.bindings) {
                if (binding.isAliveOrPending() && (binding.locator != null || binding.params.beamEndLocator() != null)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Publishes a rendered 3D locator/bone transform sample for {@code host} during entity rendering.
     */
    public static void onLocatorSample(@Nullable Object host, @Nullable Entity entity, String locatorName,
                                       double worldX, double worldY, double worldZ,
                                       float qx, float qy, float qz, float qw,
                                       @Nullable Vec3 actorPosition) {
        if (host == null || locatorName == null || locatorName.isBlank()) return;
        if (!Double.isFinite(worldX) || !Double.isFinite(worldY) || !Double.isFinite(worldZ)) return;
        KeyframeEffectPlaybackAdapter activeAdapter = adapter;
        if (activeAdapter == null) return;

        List<SpawnNotification> notifications = null;
        synchronized (LOCK) {
            HostState state = HOSTS.computeIfAbsent(host, k -> new HostState(entity));
            if (entity != null) state.entity = entity;
            state.lastLocatorPoses.put(locatorName, new LocatorPose(worldX, worldY, worldZ, qx, qy, qz, qw));

            if (state.bindings.isEmpty()) return;
            Entity resolvedEntity = entity != null ? entity : state.entity;
            float baseScale = resolveEntityScale(resolvedEntity);
            double[] actorPos = actorPosition != null
                    ? new double[] { actorPosition.x, actorPosition.y, actorPosition.z }
                    : (resolvedEntity != null ? new double[] { resolvedEntity.getX(), resolvedEntity.getY(), resolvedEntity.getZ() } : null);
            double[] actorVel = computeActorVelocityCmPerSec(resolvedEntity);

            Iterator<ActiveBinding> it = state.bindings.iterator();
            while (it.hasNext()) {
                ActiveBinding binding = it.next();
                if (binding.closed) {
                    it.remove();
                    continue;
                }
                if (locatorName.equals(binding.locator)) {
                    double px = worldX + binding.params.offsetX();
                    double py = worldY + binding.params.offsetY();
                    double pz = worldZ + binding.params.offsetZ();
                    float rqX = binding.params.rotate() ? qx : 0.0F;
                    float rqY = binding.params.rotate() ? qy : 0.0F;
                    float rqZ = binding.params.rotate() ? qz : 0.0F;
                    float rqW = binding.params.rotate() ? qw : 1.0F;
                    float scale = baseScale * binding.params.scale();

                    if (binding.handle == null) {
                        KeyframeEffectPlaybackAdapter.BoundHandle created = activeAdapter.spawn(
                                binding.level, binding.effectId, px, py, pz, binding.seed,
                                binding.params, binding.params.follow());
                        if (created == null) {
                            binding.closed = true;
                            it.remove();
                            continue;
                        }
                        binding.handle = created;
                        created.updatePose(px, py, pz, rqX, rqY, rqZ, rqW, scale, actorPos, actorVel);
                        if (binding.params.beamEndLocator() != null) {
                            LocatorPose endPose = state.lastLocatorPoses.get(binding.params.beamEndLocator());
                            if (endPose != null) created.setBeamEnd(endPose.x, endPose.y, endPose.z);
                        }
                        if (notifications == null) notifications = new ArrayList<>(2);
                        notifications.add(new SpawnNotification(binding.request, created.rawHandle()));
                        if (!binding.params.follow()) {
                            // One-shot deferred spawn complete; no longer needs tracking
                            it.remove();
                            continue;
                        }
                    } else if (binding.handle.isAlive()) {
                        binding.handle.updatePose(px, py, pz, rqX, rqY, rqZ, rqW, scale, actorPos, actorVel);
                    } else {
                        it.remove();
                        continue;
                    }
                }
                if (locatorName.equals(binding.params.beamEndLocator()) && binding.handle != null && binding.handle.isAlive()) {
                    binding.handle.setBeamEnd(worldX, worldY, worldZ);
                }
            }
        }
        if (notifications != null) {
            for (SpawnNotification n : notifications) {
                notifySpawnListeners(n.request, n.rawHandle);
            }
        }
    }

    /** Updates non-locator actor-following bindings on client tick and prunes removed/world-mismatched hosts. */
    public static void onClientTick(@Nullable ClientLevel activeLevel) {
        synchronized (LOCK) {
            if (HOSTS.isEmpty()) return;
            Iterator<Map.Entry<Object, HostState>> it = HOSTS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Object, HostState> entry = it.next();
                HostState state = entry.getValue();
                Entity entity = state.entity;
                boolean hostInvalid = activeLevel == null
                        || (entity != null && (entity.isRemoved() || entity.level() != activeLevel));
                if (hostInvalid) {
                    state.closeAll();
                    it.remove();
                    continue;
                }
                Iterator<ActiveBinding> bit = state.bindings.iterator();
                while (bit.hasNext()) {
                    ActiveBinding binding = bit.next();
                    if (binding.closed || binding.level != activeLevel) {
                        binding.close();
                        bit.remove();
                        continue;
                    }
                    if (binding.handle != null && !binding.handle.isAlive()) {
                        bit.remove();
                        continue;
                    }
                    // Actor-following bindings without a named locator track the entity root directly
                    if (binding.locator == null && binding.params.follow() && binding.handle != null && entity != null) {
                        double px = entity.getX() + binding.params.offsetX();
                        double py = entity.getY() + binding.params.offsetY();
                        double pz = entity.getZ() + binding.params.offsetZ();
                        float scale = resolveEntityScale(entity) * binding.params.scale();
                        double[] actorPos = new double[] { entity.getX(), entity.getY(), entity.getZ() };
                        double[] actorVel = computeActorVelocityCmPerSec(entity);
                        binding.handle.updatePose(px, py, pz, 0.0F, 0.0F, 0.0F, 1.0F, scale, actorPos, actorVel);
                    }
                }
                if (state.bindings.isEmpty() && state.lastLocatorPoses.size() > 64) {
                    state.lastLocatorPoses.clear();
                }
            }
        }
    }

    public static void stopForHost(@Nullable Object host) {
        if (host == null) return;
        synchronized (LOCK) {
            HostState state = HOSTS.remove(host);
            if (state != null) state.closeAll();
        }
    }

    public static void stopForController(@Nullable Object host, @Nullable Object controllerKey) {
        if (host == null || controllerKey == null) return;
        stopMatching(host, controllerKey, null, null);
    }

    public static boolean stopMatching(@Nullable Object host, @Nullable Object controllerKey,
                                       @Nullable String effectId, @Nullable String locator) {
        if (host == null) return false;
        boolean stoppedAny = false;
        synchronized (LOCK) {
            HostState state = HOSTS.get(host);
            if (state == null) return false;
            Iterator<ActiveBinding> it = state.bindings.iterator();
            while (it.hasNext()) {
                ActiveBinding b = it.next();
                if (controllerKey != null && b.controllerKey != controllerKey) continue;
                if (effectId != null && !effectId.equals(b.effectId)) continue;
                if (locator != null && !locator.equals(b.locator)) continue;
                b.close();
                it.remove();
                stoppedAny = true;
            }
        }
        return stoppedAny;
    }

    public static void onResourceReload() {
        clearAll();
    }

    public static void onLevelUnload() {
        clearAll();
    }

    public static void clearAll() {
        synchronized (LOCK) {
            for (HostState state : HOSTS.values()) {
                state.closeAll();
            }
            HOSTS.clear();
        }
    }

    private static String resolveCanonicalId(KeyframeEffectPlaybackAdapter activeAdapter,
                                             String rawEffect,
                                             LumenFxKeyframeBridge.ParsedEffectId parsed) {
        String candidate = parsed.canonicalId();
        if (activeAdapter.hasEffect(candidate)) {
            return candidate;
        }
        // Identifier-safe cross-namespace syntax: lumenfx:<ns>/<path> -> <ns>:<path>
        if (candidate.startsWith("lumenfx:")) {
            String rest = candidate.substring("lumenfx:".length());
            int slash = rest.indexOf('/');
            if (slash > 0 && slash + 1 < rest.length()) {
                String remapped = rest.substring(0, slash) + ":" + rest.substring(slash + 1);
                if (activeAdapter.hasEffect(remapped)) {
                    return remapped;
                }
            }
        }
        // If raw had no namespace and parsed defaulted to lumenfx:<path>, also check minecraft:<path>
        if (!parsed.explicitLumenFx() && rawEffect.indexOf(':') < 0 && candidate.startsWith("lumenfx:")) {
            String mcFallback = "minecraft:" + candidate.substring("lumenfx:".length());
            if (activeAdapter.hasEffect(mcFallback)) {
                return mcFallback;
            }
        }
        return candidate;
    }

    private static long computeSeed(Object host, @Nullable Entity entity, String effectId,
                                    @Nullable String locator, KeyframeEffectParams params) {
        if (params.seed() != null) return params.seed();
        long base = entity != null ? entity.getUUID().getLeastSignificantBits() : System.identityHashCode(host);
        long locHash = locator != null ? locator.hashCode() : 0L;
        return base ^ (effectId.hashCode() * SEED_MIX) ^ (locHash * 0xBF58476D1CE4E5B9L) ^ SEQUENCE.getAndIncrement();
    }

    private static float resolveEntityScale(@Nullable Entity entity) {
        KeyframeEffectPlaybackAdapter activeAdapter = adapter;
        if (activeAdapter != null) {
            float scale = activeAdapter.entityScale(entity);
            if (Float.isFinite(scale) && scale > 0.0F) {
                return scale;
            }
        }
        return 1.0F;
    }

    private static @Nullable double[] computeActorVelocityCmPerSec(@Nullable Entity entity) {
        if (entity == null) return null;
        Vec3 move = entity.getDeltaMovement();
        double cmPerSecond = 2000.0; // 100 cm/block * 20 ticks/sec
        return new double[] { move.x * cmPerSecond, move.z * cmPerSecond, move.y * cmPerSecond };
    }

    private static void notifySpawnListeners(KeyframeEffectRequest request, Object rawHandle) {
        for (LumenFxKeyframeBridge.SpawnListener listener : LISTENERS) {
            try {
                listener.onSpawn(request, rawHandle);
            } catch (RuntimeException error) {
                LOGGER.warn("LumenFX keyframe spawn listener threw an exception", error);
            }
        }
    }

    private record LocatorPose(double x, double y, double z, float qx, float qy, float qz, float qw) { }

    private record SpawnNotification(KeyframeEffectRequest request, Object rawHandle) { }

    private static final class HostState {
        @Nullable Entity entity;
        final List<ActiveBinding> bindings = new ArrayList<>();
        final Map<String, LocatorPose> lastLocatorPoses = new java.util.HashMap<>();

        HostState(@Nullable Entity entity) {
            this.entity = entity;
        }

        void closeAll() {
            for (ActiveBinding binding : bindings) {
                binding.close();
            }
            bindings.clear();
            lastLocatorPoses.clear();
        }
    }

    private static final class ActiveBinding {
        final ClientLevel level;
        final @Nullable Object controllerKey;
        final String effectId;
        final @Nullable String locator;
        final KeyframeEffectParams params;
        final KeyframeEffectRequest request;
        @Nullable KeyframeEffectPlaybackAdapter.BoundHandle handle;
        final long seed;
        boolean closed;

        ActiveBinding(ClientLevel level, @Nullable Object controllerKey, String effectId,
                      @Nullable String locator, KeyframeEffectParams params, KeyframeEffectRequest request,
                      @Nullable KeyframeEffectPlaybackAdapter.BoundHandle handle, long seed) {
            this.level = level;
            this.controllerKey = controllerKey;
            this.effectId = effectId;
            this.locator = locator;
            this.params = params;
            this.request = request;
            this.handle = handle;
            this.seed = seed;
        }

        boolean matches(@Nullable Object otherController, String otherEffect, @Nullable String otherLocator) {
            return controllerKey == otherController
                    && effectId.equals(otherEffect)
                    && Objects.equals(locator, otherLocator);
        }

        boolean isAliveOrPending() {
            return !closed && (handle == null || handle.isAlive());
        }

        void close() {
            closed = true;
            if (handle != null) {
                try {
                    handle.close();
                } catch (RuntimeException ignored) { }
                handle = null;
            }
        }
    }
}
