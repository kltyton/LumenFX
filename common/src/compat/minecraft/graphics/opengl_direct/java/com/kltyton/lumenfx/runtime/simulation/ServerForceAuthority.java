package com.kltyton.lumenfx.runtime.simulation;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.runtime.model.EffectModel;
import com.kltyton.lumenfx.runtime.model.ProductionEffectParser;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel.CollisionSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * Authoritative server-side Unity collider-force contract registry shared across OpenGL targets.
 */
public final class ServerForceAuthority {
    public record ForceSpec(double colliderForce, boolean multiplyByAngle, boolean multiplyBySpeed,
                            boolean multiplyBySize, double maxSpeed, double maxSize) { }

    public static final class AuthorizedEffect {
        public final String effectId;
        public final long seed;
        public final Supplier<Vec3> originSupplier;
        public final double maxRange;
        public int remainingTicks;
        public volatile boolean cancelled;

        AuthorizedEffect(String effectId, long seed, Supplier<Vec3> originSupplier,
                         double maxRange, int durationTicks) {
            this.effectId = effectId;
            this.seed = seed;
            this.originSupplier = originSupplier;
            this.maxRange = Math.max(0.5, maxRange);
            this.remainingTicks = Math.max(1, durationTicks);
        }
    }

    private static final Map<String, Map<String, ForceSpec>> SPECS = new ConcurrentHashMap<>();
    private static final List<AuthorizedEffect> ACTIVE = new CopyOnWriteArrayList<>();

    private ServerForceAuthority() { }

    public static void registerSpec(String effectId, String emitterName, ForceSpec spec) {
        SPECS.computeIfAbsent(effectId, k -> new ConcurrentHashMap<>()).put(emitterName, spec);
    }

    public static void registerFromJson(String effectId, JsonObject effectJson) {
        if (effectJson == null) return;
        EffectModel model = ProductionEffectParser.parse(effectId, effectJson, null);
        for (UnityEmitterModel em : model.emitters()) {
            CollisionSpec col = em.collision();
            if (col.enabled() && col.colliderForce() > 0.0) {
                registerSpec(effectId, em.name(), new ForceSpec(
                        col.colliderForce(), col.multiplyByAngle(), col.multiplyBySpeed(),
                        col.multiplyBySize(), col.maxSpeed(), col.maxSize()));
            }
        }
    }

    public static Map<String, ForceSpec> specsFor(String effectId) {
        return SPECS.getOrDefault(effectId, Map.of());
    }

    public static Runnable authorize(String effectId, long seed, Supplier<Vec3> originSupplier,
                                     double maxRange, int durationTicks) {
        AuthorizedEffect entry = new AuthorizedEffect(effectId, seed, originSupplier, maxRange, durationTicks);
        ACTIVE.add(entry);
        return () -> {
            entry.cancelled = true;
            ACTIVE.remove(entry);
        };
    }

    public static int activeAuthorizedCount() { return ACTIVE.size(); }

    public static void tickServer() {
        for (AuthorizedEffect entry : ACTIVE) {
            if (entry.cancelled || --entry.remainingTicks <= 0) {
                ACTIVE.remove(entry);
            }
        }
    }

    public static void clearAll() { ACTIVE.clear(); }
}
