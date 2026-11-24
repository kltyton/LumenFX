package com.kltyton.lumenfx.common.force;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.LumenFx;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trusted server-side catalog of authored Unity colliderForce emitter contracts loaded from datapack JSON,
 * mod classpath effect JSONs, or explicit server API registration.
 */
public final class ServerColliderForceSpecs {
    private static final double MIN_DT = ServerColliderForceAuthority.TICK_SECONDS;
    private static final FileToIdConverter EFFECT_FILES = FileToIdConverter.json("lumenfx/effects");
    private static final ServerColliderForceSpecs INSTANCE = new ServerColliderForceSpecs();

    public record EmitterForceSpec(
            double colliderForce,
            boolean multiplyByAngle,
            boolean multiplyBySpeed,
            boolean multiplyBySize,
            double maxSpeed,
            double maxSize,
            double radiusScale,
            int maximumParticles,
            double maxRatePerSecond,
            int maxBurstCount,
            double maxLifetime,
            double lifetimeLoss,
            double simulationSpeed,
            boolean prewarm,
            double duration,
            boolean looping,
            double maxTravelRange,
            boolean subEmitterTarget,
            int maxConcurrentEmitters,
            boolean authoredEmissionSchedule
    ) {
        public EmitterForceSpec(
                double colliderForce,
                boolean multiplyByAngle,
                boolean multiplyBySpeed,
                boolean multiplyBySize,
                double maxSpeed,
                double maxSize
        ) {
            this(colliderForce, multiplyByAngle, multiplyBySpeed, multiplyBySize,
                    maxSpeed, maxSize, 1.0, 4096, 0.0, 0, 1.0, 0.0, 1.0,
                    false, 1.0, true, Math.max(1.0, maxSpeed * 2.0), false, 1, false);
        }

        public EmitterForceSpec {
            if (!Double.isFinite(colliderForce)) throw new IllegalArgumentException("colliderForce must be finite");
            if (!Double.isFinite(maxSpeed) || maxSpeed < 0.0) throw new IllegalArgumentException("maxSpeed must be >= 0");
            if (!Double.isFinite(maxSize) || maxSize < 0.0) throw new IllegalArgumentException("maxSize must be >= 0");
            if (!Double.isFinite(radiusScale) || radiusScale < 0.0) throw new IllegalArgumentException("radiusScale must be >= 0");
            if (maximumParticles < 1) throw new IllegalArgumentException("maximumParticles must be >= 1");
            if (!Double.isFinite(maxRatePerSecond) || maxRatePerSecond < 0.0) throw new IllegalArgumentException("maxRatePerSecond must be >= 0");
            if (maxBurstCount < 0) throw new IllegalArgumentException("maxBurstCount must be >= 0");
            if (!Double.isFinite(maxLifetime) || maxLifetime < 0.0) throw new IllegalArgumentException("maxLifetime must be >= 0");
            if (!Double.isFinite(lifetimeLoss) || lifetimeLoss < 0.0) throw new IllegalArgumentException("lifetimeLoss must be >= 0");
            if (!Double.isFinite(simulationSpeed) || simulationSpeed < 0.0) throw new IllegalArgumentException("simulationSpeed must be >= 0");
            if (!Double.isFinite(duration) || duration <= 0.0) throw new IllegalArgumentException("duration must be > 0");
            if (!Double.isFinite(maxTravelRange) || maxTravelRange <= 0.0) throw new IllegalArgumentException("maxTravelRange must be > 0");
            if (maxConcurrentEmitters < 1) throw new IllegalArgumentException("maxConcurrentEmitters must be >= 1");
        }

        /** Maximum physical particle contact radius plus one tick step tolerance, derived from authored size & radiusScale. */
        public double contactTolerance() {
            double particleRadius = Math.max(0.05, maxSize * 0.5 * radiusScale);
            double stepMargin = Math.max(0.25, maxSpeed * MIN_DT * 0.5);
            return particleRadius + stepMargin;
        }

        /** Exact upper bound on the impulse a single particle collision can impart per authored contract. */
        public double maxSingleHitImpulse() {
            double speedFactor = multiplyBySpeed ? Math.max(1e-6, maxSpeed) : 1.0;
            double sizeFactor = multiplyBySize ? Math.max(1e-6, maxSize) : 1.0;
            return Math.abs(colliderForce) * speedFactor * sizeFactor * MIN_DT;
        }

        /** Exact upper bound on cumulative impulse per entity per server tick for this emitter contract. */
        public double maxTickEntityImpulse() {
            int maxSimultaneousHits = Math.max(1, Math.min(maximumParticles, 64) * Math.max(1, maxConcurrentEmitters));
            return maxSingleHitImpulse() * maxSimultaneousHits;
        }

        /** Maximum total number of sequential particle IDs any single instance of this emitter could have spawned. */
        public long maxParticleIdAtElapsedSeconds(double elapsedSeconds) {
            if (!authoredEmissionSchedule) {
                return maximumParticles;
            }
            double clampedElapsed = Math.max(0.0, elapsedSeconds);
            double activeSeconds = (clampedElapsed + (prewarm ? duration : 0.0)) * Math.max(0.0, simulationSpeed);
            double cycleDuration = Math.max(duration, 1e-4);
            long cycles = looping ? (long) Math.floor((activeSeconds + MIN_DT) / cycleDuration) + 1L : 1L;
            double continuous = maxRatePerSecond * (looping ? activeSeconds + MIN_DT : Math.min(activeSeconds + MIN_DT, cycleDuration));
            double bursts = cycles * (double) maxBurstCount;
            long scheduleBound = (long) Math.ceil(continuous + bursts) + 1L;
            return Math.max(1L, scheduleBound);
        }

        /** Maximum sequential hitSeq any single alive particle can accumulate from lifetimeLoss and authored lifetime. */
        public int maxHitsPerParticle() {
            if (lifetimeLoss >= 1.0 - 1e-6) {
                return 0;
            }
            if (lifetimeLoss > 1e-4) {
                return Math.max(1, (int) Math.ceil(1.0 / lifetimeLoss));
            }
            double lifeSeconds = maxLifetime / Math.max(1e-4, simulationSpeed);
            return Math.max(1, (int) Math.ceil(lifeSeconds / MIN_DT) + 2);
        }
    }

    private final ConcurrentHashMap<Identifier, Map<String, EmitterForceSpec>> explicitOverrides = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identifier, Map<String, EmitterForceSpec>> apiSpecs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identifier, Map<String, EmitterForceSpec>> classpathSpecs = new ConcurrentHashMap<>();
    private volatile Map<Identifier, Map<String, EmitterForceSpec>> datapackSpecs = Map.of();

    private ServerColliderForceSpecs() { }

    public static PreparableReloadListener createReloadListener() {
        return new ReloadListener();
    }

    public static void register(Identifier effectId, String emitterName, EmitterForceSpec spec) {
        Objects.requireNonNull(effectId, "effectId");
        Objects.requireNonNull(emitterName, "emitterName");
        Objects.requireNonNull(spec, "spec");
        INSTANCE.explicitOverrides.compute(effectId, (ignored, existing) -> {
            Map<String, EmitterForceSpec> next = existing == null ? new HashMap<>() : new HashMap<>(existing);
            next.put(emitterName, spec);
            return Collections.unmodifiableMap(next);
        });
    }

    public static void registerFromJson(Identifier effectId, JsonObject root) {
        var specs = parseEffect(root);
        ServerUnityAssets.register(effectId, root);
        INSTANCE.apiSpecs.put(effectId, specs);
    }

    public static void clearRegistered() {
        INSTANCE.explicitOverrides.clear();
        INSTANCE.apiSpecs.clear();
        INSTANCE.classpathSpecs.clear();
        ServerUnityAssets.clear();
        INSTANCE.datapackSpecs = Map.of();
    }

    public static @Nullable EmitterForceSpec resolve(Identifier effectId, String emitterName) {
        if (effectId == null || emitterName == null || emitterName.isEmpty()) return null;
        var override = explicitOverride(effectId, emitterName);
        if (override != null) return override;
        var registered = INSTANCE.apiSpecs.get(effectId);
        if (registered != null && registered.containsKey(emitterName)) {
            return registered.get(emitterName);
        }
        var datapack = INSTANCE.datapackSpecs.get(effectId);
        if (datapack != null && datapack.containsKey(emitterName)) {
            return datapack.get(emitterName);
        }
        var classpath = INSTANCE.classpathSpecs.computeIfAbsent(effectId, ServerColliderForceSpecs::loadFromClasspath);
        return classpath.get(emitterName);
    }

    static @Nullable EmitterForceSpec explicitOverride(Identifier effectId, String emitterName) {
        return INSTANCE.explicitOverrides.getOrDefault(effectId, Map.of()).get(emitterName);
    }

    private static Map<String, EmitterForceSpec> loadFromClasspath(Identifier id) {
        String resourcePath = "assets/" + id.getNamespace() + "/lumenfx/effects/" + id.getPath() + ".json";
        ClassLoader loader = ServerColliderForceSpecs.class.getClassLoader();
        if (loader == null) return Map.of();
        try (var stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) return Map.of();
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) return Map.of();
                return parseEffect(parsed.getAsJsonObject());
            }
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Map<String, EmitterForceSpec> parseEffect(JsonObject root) {
        if (!root.has("format_version") || root.get("format_version").getAsInt() != 1) {
            return Map.of();
        }
        JsonArray emitters = root.has("emitters") && root.get("emitters").isJsonArray()
                ? root.getAsJsonArray("emitters") : null;
        if (emitters == null || emitters.isEmpty()) return Map.of();

        Map<String, JsonObject> byName = new HashMap<>();
        Map<String, Integer> subEmitterCounts = new HashMap<>();
        Map<String, Integer> subEmitterParentCaps = new HashMap<>();
        for (JsonElement element : emitters) {
            if (!element.isJsonObject()) continue;
            JsonObject emitter = element.getAsJsonObject();
            if (!emitter.has("name") || emitter.get("name").isJsonNull()) continue;
            String name = emitter.get("name").getAsString();
            byName.put(name, emitter);
            JsonObject renderer = object(emitter, "unity_renderer");
            if (renderer != null && renderer.has("sub_emitters") && renderer.get("sub_emitters").isJsonArray()) {
                int parentMax = Math.max(1, integer(emitter, "max_particles", 4096));
                for (JsonElement linkEl : renderer.getAsJsonArray("sub_emitters")) {
                    if (!linkEl.isJsonObject()) continue;
                    JsonObject link = linkEl.getAsJsonObject();
                    if (!link.has("target") || link.get("target").isJsonNull()) continue;
                    String target = link.get("target").getAsString();
                    subEmitterCounts.merge(target, 1, Integer::sum);
                    subEmitterParentCaps.merge(target, Math.min(parentMax, 128), Integer::sum);
                }
            }
        }

        Map<String, EmitterForceSpec> result = new HashMap<>();
        for (JsonElement element : emitters) {
            if (!element.isJsonObject()) continue;
            JsonObject emitter = element.getAsJsonObject();
            if (!emitter.has("name") || emitter.get("name").isJsonNull()) continue;
            String name = emitter.get("name").getAsString();
            JsonObject renderer = object(emitter, "unity_renderer");
            JsonObject collision = object(renderer, "collision");
            JsonObject world = object(collision, "world");
            if (world == null) continue;
            double force = number(world, "collider_force", 0.0);
            if (!Double.isFinite(force) || Math.abs(force) <= 1e-12) continue;
            boolean byAngle = bool(world, "multiply_force_by_collision_angle", false);
            boolean bySpeed = bool(world, "multiply_force_by_particle_speed", false);
            boolean bySize = bool(world, "multiply_force_by_particle_size", false);
            double radiusScale = Math.max(0.0, number(collision, "radius_scale", 1.0));
            double lifetimeLoss = Math.max(0.0, scalarUpperBound(collision.get("lifetime_loss"), 0.0));
            double bounce = Math.max(0.0, scalarUpperBound(collision.get("bounce"), 1.0));
            double simSpeed = Math.max(0.0, renderer != null ? number(renderer, "simulation_speed", 1.0) : 1.0);
            boolean prewarm = renderer != null && bool(renderer, "prewarm", false);
            boolean looping = bool(emitter, "loop", true);
            double duration = Math.max(1e-4, number(emitter, "duration", 1.0));
            int maximumParticles = Math.max(1, integer(emitter, "max_particles", 4096));
            double maxLifetime = Math.max(0.05, scalarUpperBound(emitter.get("lifetime"), 1.0));
            double maxRate = scalarUpperBound(emitter.get("rate"), 0.0);
            double distanceRate = scalarUpperBound(emitter.get("rate_over_distance"), 0.0);
            double distanceSpacing = number(emitter, "distance_spacing", 0.0);
            int maxBurstCount = estimateBurstCount(emitter.get("bursts"));
            boolean subEmitterTarget = subEmitterCounts.getOrDefault(name, 0) > 0;
            int maxConcurrentEmitters = subEmitterTarget ? Math.max(1, subEmitterParentCaps.getOrDefault(name, 1)) : 1;
            boolean hasSchedule = !subEmitterTarget
                    && distanceRate <= 1e-9
                    && distanceSpacing <= 1e-9
                    && (maxRate > 0.0 || maxBurstCount > 0);
            double maxSpeed = estimateMaxSpeed(emitter, renderer, collision, maxLifetime, simSpeed, bounce, subEmitterTarget);
            double maxSize = estimateMaxSize(emitter, renderer);
            double shapeExtent = estimateShapeExtent(emitter, renderer);
            double lifeTimeSec = maxLifetime / Math.max(1e-4, simSpeed);
            double maxTravelRange = Math.max(2.0, shapeExtent + maxSpeed * lifeTimeSec + maxSize * radiusScale + 2.0);
            result.put(name, new EmitterForceSpec(
                    force, byAngle, bySpeed, bySize, maxSpeed, maxSize, radiusScale, maximumParticles,
                    maxRate, maxBurstCount, maxLifetime, lifetimeLoss, simSpeed, prewarm, duration,
                    looping, maxTravelRange, subEmitterTarget, maxConcurrentEmitters, hasSchedule));
        }
        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    private static int estimateBurstCount(@Nullable JsonElement burstsEl) {
        if (burstsEl == null || !burstsEl.isJsonArray()) return 0;
        int total = 0;
        for (JsonElement item : burstsEl.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject b = item.getAsJsonObject();
            int cycles = Math.max(1, integer(b, "cycles", 1));
            int count = b.has("count") && !b.get("count").isJsonNull()
                    ? (int) Math.ceil(scalarUpperBound(b.get("count"), 0.0))
                    : Math.max(integer(b, "min", 0), integer(b, "max", 0));
            total += Math.max(0, count) * cycles;
        }
        return total;
    }

    private static double estimateMaxSpeed(JsonObject emitter, @Nullable JsonObject renderer, JsonObject collision,
                                           double maxLifetime, double simSpeed, double bounce, boolean subEmitterTarget) {
        double initial = Math.max(scalarUpperBound(emitter.get("start_speed"), 0.0),
                vectorUpperBound(emitter.get("velocity_min"), emitter.get("velocity_max")));
        double accel = componentUpperBound(emitter.get("acceleration")) + componentUpperBound(emitter.get("force_over_life"));
        double lifeFactor = Math.max(0.1, maxLifetime);
        double vol = componentUpperBound(emitter.get("velocity_over_life"));
        double integrated = initial + accel * lifeFactor + vol;
        if (renderer != null) {
            JsonObject spaceMotion = object(renderer, "space_motion");
            if (spaceMotion != null) {
                integrated += componentUpperBound(spaceMotion.get("velocity"));
                double mod = scalarUpperBound(spaceMotion.get("speed_modifier"), 1.0);
                if (mod > 1.0) integrated *= mod;
            }
            JsonObject noise = object(renderer, "noise");
            if (noise != null) {
                double strength = componentUpperBound(noise.get("strength"));
                double pos = scalarUpperBound(noise.get("position"), 1.0);
                double remap = componentUpperBound(noise.get("remap"));
                integrated += Math.max(strength, remap) * Math.max(1.0, pos);
            }
            JsonObject externalForces = object(renderer, "external_forces");
            if (externalForces != null && externalForces.has("fields") && externalForces.get("fields").isJsonArray()) {
                double mult = scalarUpperBound(externalForces.get("multiplier"), 1.0);
                for (JsonElement fieldEl : externalForces.getAsJsonArray("fields")) {
                    if (!fieldEl.isJsonObject()) continue;
                    JsonObject f = fieldEl.getAsJsonObject();
                    double fieldAccel = componentUpperBound(f.get("direction")) * mult
                            + scalarUpperBound(f.get("gravity"), 0.0) * mult
                            + scalarUpperBound(f.get("rotation_speed"), 0.0) * mult
                            + scalarUpperBound(f.get("vector_speed"), 0.0) * mult;
                    integrated += fieldAccel * lifeFactor;
                }
            }
            if (object(renderer, "inherit_velocity") != null || subEmitterTarget) {
                integrated += 32.0;
            }
        }
        double bounceGain = Math.max(1.0, bounce);
        double speed = Math.max(integrated * bounceGain * Math.max(1.0, simSpeed), 1.0);
        double authoredCeiling = number(collision, "maximum_speed", Double.POSITIVE_INFINITY);
        if (Double.isFinite(authoredCeiling) && authoredCeiling > 0.0 && authoredCeiling < 1e12) {
            speed = Math.min(speed, authoredCeiling);
        }
        return Math.max(speed * 1.1, 1.0);
    }

    private static double estimateMaxSize(JsonObject emitter, @Nullable JsonObject renderer) {
        double w = scalarUpperBound(emitter.get("width"), 1.0);
        double h = scalarUpperBound(emitter.get("height"), 1.0);
        double life = Math.max(1.0, scalarUpperBound(emitter.get("size_over_life"), 1.0));
        double base = Math.max(w, h) * life;
        if (renderer != null) {
            double axes = componentUpperBound(renderer.get("size_over_life_axes"));
            if (axes > 0.0) base = Math.max(base, Math.max(w, h) * axes);
            JsonObject speedModules = object(renderer, "speed_modules");
            JsonObject speedSize = object(speedModules, "size");
            if (speedSize != null) {
                double factor = Math.max(scalarUpperBound(speedSize.get("value"), 1.0), componentUpperBound(speedSize.get("axes")));
                base *= Math.max(1.0, factor);
            }
            JsonObject noise = object(renderer, "noise");
            if (noise != null) {
                base *= 1.0 + scalarUpperBound(noise.get("size"), 0.0);
            }
            double scale = Math.max(1.0, transformMaxScale(object(renderer, "transform")));
            base *= scale;
        }
        return Math.max(base, 0.25);
    }

    private static double estimateShapeExtent(JsonObject emitter, @Nullable JsonObject renderer) {
        JsonObject shape = object(emitter, "shape");
        double extent = shape != null ? componentUpperBound(shape.get("size")) : 0.0;
        if (renderer != null) {
            double trScale = Math.max(1.0, transformMaxScale(object(renderer, "transform")));
            double emScale = Math.max(1.0, transformMaxScale(object(renderer, "emission_transform")));
            extent *= Math.max(trScale, emScale);
        }
        return extent;
    }

    private static double transformMaxScale(@Nullable JsonObject tr) {
        if (tr == null) return 1.0;
        double sx = scalarUpperBound(tr.get("sx"), 1.0);
        double sy = scalarUpperBound(tr.get("sy"), 1.0);
        double sz = scalarUpperBound(tr.get("sz"), 1.0);
        double scaleVec = componentUpperBound(tr.get("scale"));
        return Math.max(1.0, Math.max(scaleVec, Math.max(sx, Math.max(sy, sz))));
    }

    private static int integer(JsonObject parent, String field, int fallback) {
        if (!parent.has(field) || parent.get(field).isJsonNull()) return fallback;
        JsonElement el = parent.get(field);
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsInt() : fallback;
    }

    private static double vectorUpperBound(@Nullable JsonElement a, @Nullable JsonElement b) {
        return Math.max(componentUpperBound(a), componentUpperBound(b));
    }

    private static double componentUpperBound(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) return 0.0;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return Math.abs(el.getAsDouble());
        }
        if (el.isJsonArray()) {
            double sumSq = 0.0;
            for (JsonElement item : el.getAsJsonArray()) {
                double bound = scalarUpperBound(item, 0.0);
                sumSq += bound * bound;
            }
            return Math.sqrt(sumSq);
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("x") || obj.has("y") || obj.has("z")) {
                double x = scalarUpperBound(obj.get("x"), 0.0);
                double y = scalarUpperBound(obj.get("y"), 0.0);
                double z = scalarUpperBound(obj.get("z"), 0.0);
                return Math.sqrt(x * x + y * y + z * z);
            }
            return scalarUpperBound(el, 0.0);
        }
        return 0.0;
    }

    private static double scalarUpperBound(@Nullable JsonElement el, double fallback) {
        if (el == null || el.isJsonNull()) return fallback;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return Math.abs(el.getAsDouble());
        }
        if (el.isJsonArray()) {
            double best = 0.0;
            for (JsonElement item : el.getAsJsonArray()) {
                best = Math.max(best, scalarUpperBound(item, 0.0));
            }
            return best;
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            double best = 0.0;
            if (obj.has("value")) best = Math.max(best, scalarUpperBound(obj.get("value"), 0.0));
            if (obj.has("min")) best = Math.max(best, scalarUpperBound(obj.get("min"), 0.0));
            if (obj.has("max")) best = Math.max(best, scalarUpperBound(obj.get("max"), 0.0));
            if (obj.has("scale")) {
                double scale = Math.abs(number(obj, "scale", 1.0));
                double inner = 0.0;
                if (obj.has("keys")) inner = Math.max(inner, keysUpperBound(obj.get("keys")));
                if (obj.has("min_keys")) inner = Math.max(inner, keysUpperBound(obj.get("min_keys")));
                if (obj.has("max_keys")) inner = Math.max(inner, keysUpperBound(obj.get("max_keys")));
                best = Math.max(best, inner * scale);
            } else {
                if (obj.has("keys")) best = Math.max(best, keysUpperBound(obj.get("keys")));
                if (obj.has("min_keys")) best = Math.max(best, keysUpperBound(obj.get("min_keys")));
                if (obj.has("max_keys")) best = Math.max(best, keysUpperBound(obj.get("max_keys")));
            }
            return best;
        }
        return fallback;
    }

    private static double keysUpperBound(@Nullable JsonElement keys) {
        if (keys == null || !keys.isJsonArray()) return 0.0;
        double best = 0.0;
        for (JsonElement key : keys.getAsJsonArray()) {
            if (key.isJsonObject()) {
                JsonObject ko = key.getAsJsonObject();
                best = Math.max(best, Math.abs(number(ko, "value", 0.0)));
                best = Math.max(best, Math.abs(number(ko, "v", 0.0)));
            } else if (key.isJsonArray() && key.getAsJsonArray().size() >= 2) {
                best = Math.max(best, Math.abs(key.getAsJsonArray().get(1).getAsDouble()));
            }
        }
        return best;
    }

    private static @Nullable JsonObject object(@Nullable JsonObject parent, String field) {
        if (parent == null || !parent.has(field)) return null;
        JsonElement el = parent.get(field);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static double number(JsonObject parent, String field, double fallback) {
        if (!parent.has(field) || parent.get(field).isJsonNull()) return fallback;
        JsonElement el = parent.get(field);
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsDouble() : fallback;
    }

    private static boolean bool(JsonObject parent, String field, boolean fallback) {
        if (!parent.has(field) || parent.get(field).isJsonNull()) return fallback;
        JsonElement el = parent.get(field);
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() ? el.getAsBoolean() : fallback;
    }

    private static final class ReloadListener extends SimplePreparableReloadListener<Map<Identifier, Map<String, EmitterForceSpec>>> {
        @Override
        protected Map<Identifier, Map<String, EmitterForceSpec>> prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<Identifier, Map<String, EmitterForceSpec>> loaded = new HashMap<>();
            for (var entry : EFFECT_FILES.listMatchingResources(manager).entrySet()) {
                Identifier id = EFFECT_FILES.fileToId(entry.getKey());
                try (var reader = entry.getValue().openAsReader()) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        Map<String, EmitterForceSpec> map = parseEffect(parsed.getAsJsonObject());
                        if (!map.isEmpty()) loaded.put(id, map);
                    }
                } catch (Exception ignored) { }
            }
            return Collections.unmodifiableMap(loaded);
        }

        @Override
        protected void apply(Map<Identifier, Map<String, EmitterForceSpec>> prepared, ResourceManager manager, ProfilerFiller profiler) {
            INSTANCE.datapackSpecs = prepared;
            INSTANCE.classpathSpecs.clear();
        }
    }
}
