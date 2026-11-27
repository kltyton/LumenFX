package com.kltyton.lumenfx.client.keyframe;

import com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectParams;
import com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectRequest;
import com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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

    /** Source authority for 3D locator samples; lower-priority fallback samples never overwrite rendered model poses. */
    public enum LocatorSource {
        FALLBACK(0),
        ANIMATION_BONE(1),
        RENDERED_MODEL(2);

        final int priority;

        LocatorSource(int priority) {
            this.priority = priority;
        }
    }

    /** Guarded by LOCK; weak host keys prevent retaining dead animatables if tick cleanup is bypassed. */
    private static final Object LOCK = new Object();
    private static final Map<Object, HostState> HOSTS = new WeakHashMap<>();

    /**
     * Deterministic per-dispatch opposite-polarity pairing table (guarded by LOCK).
     * Pairs a single keyframe's manual {@link LumenFxKeyframeBridge#trigger} invocation with its KBR auto-hook
     * counterpart (in either callback order and regardless of callback duration), keyed by host identity,
     * controller identity, canonical effect ID, locator, stop flag, and optional event token.
     * Consumed on match and invalidated on client tick, host/controller stop, or clearAll.
     */
    private static final int MAX_PENDING_PAIRS = 64;
    private static final List<PendingDispatchPair> PENDING_PAIRS = new ArrayList<>();
    private static long clientTickEpoch = 0L;

    /**
     * Thread-local stack of active keyframe dispatch scopes opened by GeckoLib 4/5 mixins so public
     * {@link LumenFxKeyframeBridge#trigger} calls inside a custom handler inherit the exact same event token as
     * KBR's automatic hook for that single keyframe, while standalone manual calls outside any dispatch keep a null token.
     */
    private static final ThreadLocal<ArrayDeque<DispatchFrame>> DISPATCH_SCOPES = new ThreadLocal<>();

    private static final class DispatchFrame {
        final @Nullable Object owner;
        final Object token;

        DispatchFrame(@Nullable Object owner, Object token) {
            this.owner = owner;
            this.token = token;
        }
    }

    /** Enters a new per-event dispatch scope with a fresh unique token and returns that token. */
    public static Object enterDispatchScope(@Nullable Object owner) {
        Object token = new Object();
        enterDispatchScope(owner, token);
        return token;
    }

    /** Enters a new per-event dispatch scope bound to {@code token} (LIFO stack supporting nested/re-entrant dispatches). */
    public static void enterDispatchScope(@Nullable Object owner, Object token) {
        Objects.requireNonNull(token, "token");
        ArrayDeque<DispatchFrame> stack = DISPATCH_SCOPES.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            DISPATCH_SCOPES.set(stack);
        }
        stack.push(new DispatchFrame(owner, token));
    }

    /** Exits the active dispatch scope matching {@code token} and prunes any unconsumed pairs for that token. */
    public static void exitDispatchScope(@Nullable Object token) {
        if (token == null) return;
        ArrayDeque<DispatchFrame> stack = DISPATCH_SCOPES.get();
        if (stack != null && !stack.isEmpty()) {
            Iterator<DispatchFrame> it = stack.iterator();
            while (it.hasNext()) {
                DispatchFrame frame = it.next();
                if (frame.token == token) {
                    it.remove();
                    break;
                }
            }
            if (stack.isEmpty()) {
                DISPATCH_SCOPES.remove();
            }
        }
        prunePairsForToken(token);
    }

    /** Exits the top dispatch scope owned by {@code owner} (safe no-op if already exited). */
    public static void exitDispatchScopeForOwner(@Nullable Object owner) {
        if (owner == null) return;
        ArrayDeque<DispatchFrame> stack = DISPATCH_SCOPES.get();
        if (stack == null || stack.isEmpty()) return;
        if (stack.peek().owner == owner) {
            DispatchFrame popped = stack.pop();
            if (stack.isEmpty()) {
                DISPATCH_SCOPES.remove();
            }
            prunePairsForToken(popped.token);
        }
    }

    /** Returns the active keyframe dispatch token on the current thread, or {@code null} outside any dispatch scope. */
    public static @Nullable Object currentDispatchToken() {
        ArrayDeque<DispatchFrame> stack = DISPATCH_SCOPES.get();
        return (stack != null && !stack.isEmpty()) ? stack.peek().token : null;
    }

    private static void prunePairsForToken(Object token) {
        synchronized (LOCK) {
            PENDING_PAIRS.removeIf(entry -> {
                if (entry.hostRef.get() == null) return true;
                if (!entry.hasEventToken) return false;
                Object entryToken = entry.eventTokenRef.get();
                return entryToken == null || entryToken == token;
            });
        }
    }

    private static final class PendingDispatchPair {
        final long threadId;
        final long tickEpoch;
        final WeakReference<Object> hostRef;
        final @Nullable WeakReference<Entity> entityRef;
        final boolean hasController;
        final @Nullable WeakReference<Object> controllerRef;
        final String canonicalId;
        final @Nullable String locator;
        final boolean stopRequest;
        final boolean fromAutoHook;
        final boolean hasEventToken;
        final @Nullable WeakReference<Object> eventTokenRef;

        PendingDispatchPair(long threadId, long tickEpoch,
                            Object host, @Nullable Entity entity, @Nullable Object controllerKey,
                            String canonicalId, @Nullable String locator,
                            boolean stopRequest, boolean fromAutoHook, @Nullable Object eventToken) {
            this.threadId = threadId;
            this.tickEpoch = tickEpoch;
            this.hostRef = new WeakReference<>(host);
            this.entityRef = entity != null ? new WeakReference<>(entity) : null;
            this.hasController = controllerKey != null;
            this.controllerRef = controllerKey != null ? new WeakReference<>(controllerKey) : null;
            this.canonicalId = canonicalId;
            this.locator = locator;
            this.stopRequest = stopRequest;
            this.fromAutoHook = fromAutoHook;
            this.hasEventToken = eventToken != null;
            this.eventTokenRef = eventToken != null ? new WeakReference<>(eventToken) : null;
        }
    }

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
     * Returns {@code true} if {@code rawEffect} (optionally with {@code preEffectScript}) would be claimed
     * by LumenFX under current auto-intercept settings (either explicitly prefixed with {@code lumenfx:} /
     * {@code lumenfx/} or matching a currently loaded LumenFX effect).
     */
    public static boolean isManagedEffect(@Nullable String rawEffect, @Nullable String preEffectScript) {
        if (!AUTO_INTERCEPT.get()) {
            return false;
        }
        KeyframeEffectPlaybackAdapter activeAdapter = adapter;
        if (activeAdapter == null) {
            return false;
        }
        LumenFxKeyframeBridge.ParsedEffectId parsed = LumenFxKeyframeBridge.parseEffectId(rawEffect);
        if (parsed == null) {
            return false;
        }
        if (parsed.explicitLumenFx()) {
            return true;
        }
        String canonicalId = resolveCanonicalId(activeAdapter, rawEffect, parsed);
        if (activeAdapter.hasEffect(canonicalId)) {
            return true;
        }
        KeyframeEffectParams params = KeyframeEffectParams.parse(preEffectScript, null, false);
        return params.stop() && hasActiveEffectId(canonicalId);
    }

    private static boolean consumeMatchingOppositePair(Object host,
                                                       @Nullable Entity entity,
                                                       @Nullable Object controllerKey,
                                                       String canonicalId,
                                                       @Nullable String locator,
                                                       boolean stopRequest,
                                                       boolean fromAutoHook,
                                                       @Nullable Object eventToken) {
        long currentThreadId = Thread.currentThread().getId();
        synchronized (LOCK) {
            if (PENDING_PAIRS.isEmpty()) {
                return false;
            }
            Iterator<PendingDispatchPair> it = PENDING_PAIRS.iterator();
            while (it.hasNext()) {
                PendingDispatchPair entry = it.next();
                Object entryHost = entry.hostRef.get();
                if (entryHost == null || entry.tickEpoch != clientTickEpoch) {
                    it.remove();
                    continue;
                }
                if (entry.hasController && entry.controllerRef.get() == null) {
                    it.remove();
                    continue;
                }
                if (entry.threadId != currentThreadId || entry.fromAutoHook == fromAutoHook) {
                    continue;
                }
                Entity entryEntity = entry.entityRef != null ? entry.entityRef.get() : null;
                boolean sameHost = (entryHost == host) || (entity != null && entryEntity == entity);
                if (!sameHost) {
                    continue;
                }
                Object entryController = entry.hasController ? entry.controllerRef.get() : null;
                if (entryController != controllerKey) {
                    continue;
                }
                if (entry.stopRequest != stopRequest
                        || !canonicalId.equals(entry.canonicalId)
                        || !Objects.equals(locator, entry.locator)) {
                    continue;
                }
                boolean incomingHasToken = eventToken != null;
                if (entry.hasEventToken != incomingHasToken) {
                    continue;
                }
                if (incomingHasToken) {
                    Object entryToken = entry.eventTokenRef.get();
                    if (entryToken != eventToken) {
                        continue;
                    }
                }
                it.remove();
                return true;
            }
            return false;
        }
    }

    private static void recordPendingPair(Object host,
                                          @Nullable Entity entity,
                                          @Nullable Object controllerKey,
                                          String canonicalId,
                                          @Nullable String locator,
                                          boolean stopRequest,
                                          boolean fromAutoHook,
                                          @Nullable Object eventToken) {
        long currentThreadId = Thread.currentThread().getId();
        synchronized (LOCK) {
            if (PENDING_PAIRS.size() >= MAX_PENDING_PAIRS) {
                PENDING_PAIRS.remove(0);
            }
            PENDING_PAIRS.add(new PendingDispatchPair(
                    currentThreadId, clientTickEpoch,
                    host, entity, controllerKey,
                    canonicalId, locator,
                    stopRequest, fromAutoHook, eventToken));
        }
    }

    private static void prunePairsForHost(Object host) {
        PENDING_PAIRS.removeIf(entry -> {
            Object entryHost = entry.hostRef.get();
            return entryHost == null || entryHost == host;
        });
    }

    private static void prunePairsForController(@Nullable Object host, Object controllerKey) {
        PENDING_PAIRS.removeIf(entry -> {
            Object entryHost = entry.hostRef.get();
            if (entryHost == null) return true;
            if (host != null && entryHost != host) return false;
            return entry.hasController && entry.controllerRef.get() == controllerKey;
        });
    }

    private static boolean hasActiveEffectId(String canonicalId) {
        synchronized (LOCK) {
            for (HostState state : HOSTS.values()) {
                for (ActiveBinding binding : state.bindings) {
                    if (binding.isAliveOrPending() && canonicalId.equals(binding.effectId)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        return handleKeyframe(level, host, entity, controllerKey, controllerName,
                rawEffect, locator, preEffectScript, bindToActor,
                fallbackX, fallbackY, fallbackZ, fromAutoHook, null);
    }

    /**
     * Evaluates a particle keyframe event with an optional dispatch/event token for deterministic coexistence dedup.
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
                                         boolean fromAutoHook,
                                         @Nullable Object eventToken) {
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
        Object effectiveToken = eventToken != null ? eventToken : currentDispatchToken();

        if (consumeMatchingOppositePair(host, entity, controllerKey, canonicalId, normLocator, params.stop(), fromAutoHook, effectiveToken)) {
            return true;
        }

        if (params.stop()) {
            boolean stopped = stopMatching(host, controllerKey, parsed.explicitLumenFx() || activeAdapter.hasEffect(canonicalId) ? canonicalId : null, normLocator);
            boolean handledStop = stopped || parsed.explicitLumenFx();
            if (handledStop) {
                recordPendingPair(host, entity, controllerKey, canonicalId, normLocator, true, fromAutoHook, effectiveToken);
            }
            return handledStop;
        }

        boolean exists = activeAdapter.hasEffect(canonicalId);
        if (!exists) {
            if (parsed.explicitLumenFx()) {
                LOGGER.warn("Explicit LumenFX particle keyframe '{}' not found in loaded effects", canonicalId);
                recordPendingPair(host, entity, controllerKey, canonicalId, normLocator, false, fromAutoHook, effectiveToken);
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
            if (parsed.explicitLumenFx()) {
                recordPendingPair(host, entity, controllerKey, canonicalId, normLocator, false, fromAutoHook, effectiveToken);
                return true;
            }
            return false;
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
                            recordPendingPair(host, entity, controllerKey, canonicalId, normLocator, false, fromAutoHook, effectiveToken);
                            return true;
                        }
                    }
                }
                ActiveBinding pending = new ActiveBinding(
                        level, controllerKey, finalId, normLocator, finalParams, request, null,
                        computeSeed(host, entity, finalId, normLocator, finalParams));
                state.bindings.add(pending);
                recordPendingPair(host, entity, controllerKey, canonicalId, normLocator, false, fromAutoHook, effectiveToken);
                return true;
            }

            if (finalParams.follow() && normLocator != null) {
                for (ActiveBinding b : state.bindings) {
                    if (b.matches(controllerKey, finalId, normLocator) && b.isAliveOrPending()) {
                        // Refresh already-bound locator effect without spawning a duplicate
                        recordPendingPair(host, entity, controllerKey, canonicalId, normLocator, false, fromAutoHook, effectiveToken);
                        return true;
                    }
                }
            }

            long seed = computeSeed(host, entity, finalId, normLocator, finalParams);
            KeyframeEffectPlaybackAdapter.BoundHandle handle = activeAdapter.spawn(
                    level, finalId, spawnX, spawnY, spawnZ, seed, finalParams, finalParams.follow() && normLocator != null);
            if (handle == null) {
                recordPendingPair(host, entity, controllerKey, canonicalId, normLocator, false, fromAutoHook, effectiveToken);
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
            recordPendingPair(host, entity, controllerKey, canonicalId, normLocator, false, fromAutoHook, effectiveToken);
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
     * Publishes an authoritative rendered 3D locator/bone transform sample for {@code host} during entity rendering.
     */
    public static void onLocatorSample(@Nullable Object host, @Nullable Entity entity, String locatorName,
                                       double worldX, double worldY, double worldZ,
                                       float qx, float qy, float qz, float qw,
                                       @Nullable Vec3 actorPosition) {
        onLocatorSample(host, entity, locatorName, worldX, worldY, worldZ, qx, qy, qz, qw,
                actorPosition, LocatorSource.RENDERED_MODEL);
    }

    /**
     * Publishes a 3D locator/bone transform sample for {@code host} tagged with its {@link LocatorSource} authority.
     * Lower-priority sources (e.g., procedural humanoid bone or actor-anchor fallbacks) never overwrite an existing
     * higher-priority sample (e.g., an authoritative GeckoLib {@code GeoLocator}/{@code GeoBone} render pose).
     */
    public static void onLocatorSample(@Nullable Object host, @Nullable Entity entity, String locatorName,
                                       double worldX, double worldY, double worldZ,
                                       float qx, float qy, float qz, float qw,
                                       @Nullable Vec3 actorPosition,
                                       LocatorSource source) {
        if (host == null || locatorName == null || locatorName.isBlank()) return;
        if (!Double.isFinite(worldX) || !Double.isFinite(worldY) || !Double.isFinite(worldZ)) return;
        LocatorSource resolvedSource = source != null ? source : LocatorSource.RENDERED_MODEL;
        KeyframeEffectPlaybackAdapter activeAdapter = adapter;
        if (activeAdapter == null) return;

        List<SpawnNotification> notifications = null;
        synchronized (LOCK) {
            HostState state = HOSTS.computeIfAbsent(host, k -> new HostState(entity));
            if (entity != null) state.entity = entity;
            LocatorPose existing = state.lastLocatorPoses.get(locatorName);
            if (existing != null && existing.source.priority > resolvedSource.priority) {
                return;
            }
            state.lastLocatorPoses.put(locatorName,
                    new LocatorPose(worldX, worldY, worldZ, qx, qy, qz, qw, resolvedSource));

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
            clientTickEpoch++;
            PENDING_PAIRS.clear();
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
            prunePairsForHost(host);
            HostState state = HOSTS.remove(host);
            if (state != null) state.closeAll();
        }
    }

    public static void stopForController(@Nullable Object host, @Nullable Object controllerKey) {
        if (host == null || controllerKey == null) return;
        synchronized (LOCK) {
            prunePairsForController(host, controllerKey);
        }
        stopMatching(host, controllerKey, null, null);
    }

    /** Stops all active or pending keyframe effects associated with {@code controllerKey} across any host. */
    public static void stopForControllerKey(@Nullable Object controllerKey) {
        if (controllerKey == null) return;
        synchronized (LOCK) {
            prunePairsForController(null, controllerKey);
            for (HostState state : HOSTS.values()) {
                Iterator<ActiveBinding> it = state.bindings.iterator();
                while (it.hasNext()) {
                    ActiveBinding b = it.next();
                    if (b.controllerKey == controllerKey) {
                        b.close();
                        it.remove();
                    }
                }
            }
        }
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
        DISPATCH_SCOPES.remove();
        synchronized (LOCK) {
            clientTickEpoch++;
            PENDING_PAIRS.clear();
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

    private record LocatorPose(double x, double y, double z, float qx, float qy, float qz, float qw,
                               LocatorSource source) { }

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
