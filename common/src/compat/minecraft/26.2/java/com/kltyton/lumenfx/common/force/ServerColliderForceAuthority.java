package com.kltyton.lumenfx.common.force;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.ForceImpact;
import com.kltyton.lumenfx.api.server.LumenFxServerEffects.CollisionInput;
import com.kltyton.lumenfx.effect.simulation.UnityEffectSimulation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Applies forces only from the server's own authored particle simulation and collision queries. */
public final class ServerColliderForceAuthority {
    public static final double TICK_SECONDS = 0.05;
    private static final AtomicLong NEXT_INSTANCE_ID = new AtomicLong();

    private record Key(long instanceId, Identifier effect, long seed) { }
    private record Instance(Key key, UnityEffectSimulation simulation, Supplier<@Nullable CollisionInput> input,
                            double maxRangeSquared, long expiry) { }
    private record TargetImpulse(Entity entity, Vec3 force) { }
    private record BudgetKey(Key instance, String emitter, int entity) { }

    private static final class LevelState {
        final Map<Key, Instance> instances = new LinkedHashMap<>();
        final List<ForceImpact> hits = new ArrayList<>();
        final Map<Integer, TargetImpulse> pending = new LinkedHashMap<>();
        final Map<BudgetKey, Double> spent = new LinkedHashMap<>();
        long lastStep = Long.MIN_VALUE;
    }

    private static final Map<ServerLevel, LevelState> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());

    private ServerColliderForceAuthority() { }

    public static Runnable authorize(ServerLevel level, Identifier effectId, long seed, Vec3 origin,
                                     double maxRange, int durationTicks) {
        var input = new CollisionInput(Pose.at(origin), null);
        return authorize(level, effectId, seed, () -> input, maxRange, durationTicks);
    }

    public static Runnable authorizeEntity(ServerLevel level, Identifier effectId, long seed, Entity owner,
                                           double maxRange, int durationTicks) {
        Objects.requireNonNull(owner, "owner");
        if (owner.level() != level) throw new IllegalArgumentException("Owner entity belongs to a different ServerLevel");
        return authorize(level, effectId, seed, () -> owner.isRemoved() || owner.level() != level ? null
                : new CollisionInput(Pose.at(owner.position()), owner.getDeltaMovement().scale(1 / TICK_SECONDS)),
                maxRange, durationTicks);
    }

    public static Runnable authorize(ServerLevel level, Identifier effectId, long seed, Supplier<@Nullable CollisionInput> input,
                                     double maxRange, int durationTicks) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(effectId, "effectId");
        Objects.requireNonNull(input, "input");
        if (!Double.isFinite(maxRange * maxRange) || maxRange <= 0.0)
            throw new IllegalArgumentException("maxRange must be positive and finite");
        if (durationTicks <= 0) throw new IllegalArgumentException("durationTicks must be positive");
        requireTickThread(level);
        var authored = ServerUnityAssets.resolve(level.getServer().getResourceManager(), effectId);
        long now = level.getGameTime();
        long expiry = now > Long.MAX_VALUE - durationTicks ? Long.MAX_VALUE : now + durationTicks;
        var key = new Key(NEXT_INSTANCE_ID.incrementAndGet(), effectId, seed);
        var simulation = new UnityEffectSimulation(authored, seed);
        var state = LEVELS.computeIfAbsent(level, ignored -> new LevelState());
        var entry = new Instance(key, simulation, input, maxRange * maxRange, expiry);
        state.instances.put(key, entry);
        return () -> {
            if (level.getServer().isSameThread()) state.instances.remove(key, entry);
            else level.getServer().execute(() -> state.instances.remove(key, entry));
        };
    }

    public static void registerDefinition(Identifier effectId, JsonObject effectJson, ResourceProvider resources) {
        var definition = UnityEffectSimulation.read(effectJson, resources);
        ServerColliderForceSpecs.registerFromJson(effectId, effectJson);
        ServerUnityAssets.register(effectId, definition);
    }

    public static void clearAll() {
        LEVELS.clear();
    }

    public static void tick(ServerLevel level) {
        var state = LEVELS.get(level);
        if (state == null) return;
        long now = level.getGameTime();
        if (state.lastStep == now) return;
        state.lastStep = now;
        state.spent.clear();
        state.pending.clear();
        try {
            for (var entry : List.copyOf(state.instances.values())) {
                if (now > entry.expiry()) {
                    state.instances.remove(entry.key(), entry);
                    continue;
                }
                if (state.instances.get(entry.key()) != entry) continue;
                var input = entry.input().get();
                if (state.instances.get(entry.key()) != entry) continue;
                if (input == null) {
                    state.instances.remove(entry.key(), entry);
                    continue;
                }
                double time = now * TICK_SECONDS;
                entry.simulation().step(time, input.pose(), level, input.velocity(), input.collisionLayers(),
                        input.emissionGeometry(), input.triggerColliders(), input.manualRequests(),
                        input.emitting(), input.stopping(), input.forceFields(), state.hits);
                if (state.instances.get(entry.key()) == entry) {
                    for (var hit : state.hits) recordImpulse(level, state, entry, input.pose().position(), hit);
                    if (entry.simulation().finished(time, input.stopping())) state.instances.remove(entry.key(), entry);
                }
                state.hits.clear();
            }
            for (var impulse : state.pending.values()) {
                Entity entity = impulse.entity();
                if (entity.level() != level || entity.isRemoved() || !entity.isAlive()) continue;
                Vec3 force = impulse.force();
                entity.push(force.x, force.y, force.z);
                entity.hurtMarked = true;
            }
        } finally {
            state.hits.clear();
            state.pending.clear();
            state.spent.clear();
        }
    }

    public static void unload(ServerLevel level) {
        LEVELS.remove(level);
    }

    private static void recordImpulse(ServerLevel world, LevelState state, Instance entry, Vec3 origin, ForceImpact hit) {
        if (origin.distanceToSqr(hit.position()) > entry.maxRangeSquared()) return;
        int id = Math.toIntExact(hit.entityId());
        Entity target = world.getEntity(id);
        if (target == null || !target.isAlive() || target.isRemoved() || target.isSpectator() || target.noPhysics) return;
        Vec3 impulse = impulse(state, entry, hit, id);
        double squared = impulse.lengthSqr();
        if (!(squared > 1e-24) || !Double.isFinite(squared)) return;
        var previous = state.pending.get(id);
        var next = previous == null ? impulse : previous.force().add(impulse);
        if (!Double.isFinite(next.lengthSqr())) return;
        state.pending.put(id, new TargetImpulse(target, next));
    }

    private static Vec3 impulse(LevelState state, Instance entry, ForceImpact hit, int entityId) {
        var override = ServerColliderForceSpecs.explicitOverride(entry.key().effect(), hit.emitter());
        if (override == null) return hit.force().scale(TICK_SECONDS);

        double strength = override.colliderForce() * TICK_SECONDS;
        if (override.multiplyByAngle()) strength *= Math.abs(hit.direction().dot(hit.normal()));
        if (override.multiplyBySpeed()) strength *= Math.min(hit.speed(), override.maxSpeed());
        if (override.multiplyBySize()) strength *= Math.min(hit.particleSize(), override.maxSize());

        var budget = new BudgetKey(entry.key(), hit.emitter(), entityId);
        double spent = state.spent.getOrDefault(budget, 0.0);
        double remaining = Math.max(0.0, override.maxTickEntityImpulse() - spent);
        double maximum = Math.min(override.maxSingleHitImpulse(), remaining);
        double applied = Math.min(Math.abs(strength), maximum);
        if (!(applied > 0) || !Double.isFinite(applied)) return Vec3.ZERO;
        state.spent.put(budget, spent + applied);
        return hit.direction().scale(Math.copySign(applied, strength));
    }

    private static void requireTickThread(ServerLevel level) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Unity collider-force instances must be authorized on the server thread");
    }
}
