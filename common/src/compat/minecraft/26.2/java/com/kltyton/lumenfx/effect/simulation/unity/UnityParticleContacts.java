package com.kltyton.lumenfx.effect.simulation.unity;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.Collider;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.Hit;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.Kind;
import com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers;
import com.kltyton.lumenfx.effect.unity.UnityCollision;
import com.lowdragmc.photon.client.collision.BlockBoxSource;
import com.lowdragmc.photon.client.collision.BlockFilter;
import com.lowdragmc.photon.client.collision.BoxBuffer;
import com.lowdragmc.photon.client.collision.ColliderTarget;
import com.lowdragmc.photon.client.collision.CollisionQuery;
import com.lowdragmc.photon.client.collision.CollisionScene;
import com.lowdragmc.photon.client.collision.Contact;
import com.lowdragmc.photon.client.collision.ContactSolver;
import com.lowdragmc.photon.client.collision.CollisionWorld;
import com.lowdragmc.photon.client.collision.EntityTarget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Supplies Shuriken collision geometry to Photon's contact solver. */
public final class UnityParticleContacts {
    public record Impact(double time, Vec3 position, Vec3 velocity, double lifeLost,
                         @Nullable Collider collider, @Nullable Hit hit) { }
    @FunctionalInterface
    public interface EntityBoxSource {
        EntityTarget[] collect(AABB bounds, int maximum, double now, double hostSecondsPerSimulationSecond);
    }
    private static final double TICKS_PER_SECOND = 20;
    private static final double MODE_2D_SLICE_HALF = 1e-4;
    private static final int ENTITY_CELL_SHIFT = 4;
    private record VoxelKey(long x, long y, long z) { }
    private record CachedVoxel(double[] boxes, byte[] kinds, long[] keys, @Nullable Object[] payloads) {
        static final CachedVoxel EMPTY = new CachedVoxel(new double[0], new byte[0], new long[0], new Object[0]);
        boolean isEmpty() { return keys.length == 0; }
    }
    private final UnityCollision settings;
    private final boolean local;
    private final ContactSolver.Body body = new ContactSolver.Body();
    private final ContactSolver.Params response = new ContactSolver.Params();
    private final CollisionScene.Stats stats = new CollisionScene.Stats();
    private final Contact contact = new Contact();
    private final RandomSource responseRandom = RandomSource.create(0);
    private final double[] scratch = new double[3];
    private final Quaterniond rotation = new Quaterniond(), inverse = new Quaterniond();
    private final Vector3d center = new Vector3d(), travel = new Vector3d(), velocity = new Vector3d();
    private final Vector3d startCenter = new Vector3d(), frameMotion = new Vector3d();
    private final Vector3d beforeScale = new Vector3d();
    private final HashMap<VoxelKey, CachedVoxel> voxelCache = new HashMap<>();
    private final HashMap<Long, EntityTarget[]> entityCellCache = new HashMap<>();
    private final BoxBuffer voxelProbe = new BoxBuffer();
    private @Nullable Level voxelCacheLevel;
    private int voxelWorldVersion = Integer.MIN_VALUE;
    private int voxelQueriesRemaining;
    private int stepCounter;
    private @Nullable Pose pose;
    private @Nullable Pose before;
    private @Nullable Pose planePose;
    private boolean worldSpaceContacts;
    private double inverseX, inverseY, inverseZ, frameStart, frameEnd;
    private double entityTime;
    private boolean collectAllEntityContacts;
    private double hostSecondsPerSimulationSecond;
    private boolean frozenWorld;
    private double currentParticleDiameter;
    private @Nullable EntityBoxSource entitySource;
    private @Nullable CollisionScene scene;
    private @Nullable UnityCollisionLayers layerInputs;
    private int layerMask = -1;
    private int layerTagGeneration;
    private @Nullable BlockFilter blockFilter;
    private boolean blockLayersSelected;
    private Set<Identifier> entityTypes = Set.of();
    private List<TagKey<EntityType<?>>> entityTags = List.of();
    private @Nullable Consumer<Impact> impactListener;
    private double impactStepStart;
    private final ContactSolver.Events impactEvents = new ContactSolver.Events() {
        @Override public void impact(Contact value, double speed) { }
        @Override public void killed(Contact value) { }
        @Override public void triggerEnter(Contact value) { }
        @Override public void impactAt(Contact value, double speed, double elapsed,
                                       double x, double y, double z, ContactSolver.Body source,
                                       double vx, double vy, double vz) {
            var listener = impactListener;
            if (listener == null) return;
            Vec3 point = new Vec3(x, y, z);
            Vec3 speedVector = new Vec3(source.svx, source.svy, source.svz);
            double time = impactStepStart + elapsed;
            var eventPose = local && !worldSpaceContacts ? interpolatePose(time) : null;
            if (eventPose != null) {
                point = eventPose.transformPosition(point);
                speedVector = eventPose.transformDirection(speedVector);
            }
            Collider collider = null;
            Hit hit = null;
            var worldSettings = settings.world();
            boolean checkEntityForce = worldSettings != null && worldSettings.colliderForce() != 0.0
                    && value.kind == Contact.ENTITY;
            if (settings.sendCollisionMessages() || checkEntityForce
                    || collectAllEntityContacts && value.kind == Contact.ENTITY) {
                Kind kind = switch (value.kind) {
                    case Contact.BLOCK -> Kind.BLOCK;
                    case Contact.ENTITY -> Kind.ENTITY;
                    case Contact.COLLIDER -> Kind.PLANE;
                    default -> throw new IllegalStateException("Unexpected Unity collision kind " + value.kind);
                };
                long key = value.key;
                if (kind == Kind.PLANE) {
                    int slot = settings.planes().get((int) key).slot();
                    if (slot >= 0) key = slot;
                }
                collider = new Collider(kind, key);
                var intersection = new Vec3(value.px, value.py, value.pz);
                var normal = new Vec3(value.nx, value.ny, value.nz);
                var incident = new Vec3(vx, vy, vz);
                if (eventPose != null) {
                    intersection = eventPose.transformPosition(intersection);
                    normal = transformNormal(eventPose, normal);
                    incident = eventPose.transformDirection(incident);
                }
                Vec3 appliedForce = Vec3.ZERO;
                if (checkEntityForce) {
                    double incidentLen = incident.length();
                    Vec3 dir = incidentLen > 1e-8 ? incident.scale(1.0 / incidentLen) : normal.scale(-1.0);
                    double mag = worldSettings.colliderForce();
                    if (worldSettings.multiplyForceByCollisionAngle()) {
                        mag *= Math.abs(dir.dot(normal));
                    }
                    if (worldSettings.multiplyForceByParticleSpeed()) {
                        mag *= incidentLen;
                    }
                    if (worldSettings.multiplyForceByParticleSize()) {
                        mag *= currentParticleDiameter;
                    }
                    appliedForce = dir.scale(mag);
                }
                hit = new Hit(time, intersection, normal, incident, appliedForce, currentParticleDiameter);
            }
            listener.accept(new Impact(time, point, speedVector, source.lifetimeLost, collider, hit));
        }
    };

    public UnityParticleContacts(UnityCollision settings, boolean local) {
        this.settings = settings;
        this.local = local;
        response.depenetrate = false;
        response.checkExitSpeed = true;
        response.minKillSpeed = settings.minimumSpeed();
        response.maxKillSpeed = settings.maximumSpeed();
        if (local && settings.world() == null) scene = planeScene(null);
    }

    public boolean usesWorld() { return settings.world() != null; }

    public void collectAllEntityContacts() { collectAllEntityContacts = true; }
    public boolean sendsCollisionMessages() { return settings.sendCollisionMessages(); }

    public void prepare(@Nullable Level level, Pose previous, Pose next, double previousTime, double now,
                        double worldTime, double simulationSpeed, boolean frozenWorld, @Nullable Pose geometryPose,
                        UnityCollisionLayers layers) {
        var world = settings.world();
        worldSpaceContacts = world != null || !local || geometryPose != null;
        if (world == null) {
            var nextPlanePose = worldSpaceContacts ? geometryPose == null ? next : geometryPose : null;
            if (!Objects.equals(nextPlanePose, planePose)) {
                scene = planeScene(nextPlanePose);
                planePose = nextPlanePose;
            }
        } else if (level != null) {
            if (world.layerMask() != -1) prepareLayers(world.layerMask(), layers, blocks -> BlockFilter.of(blocks, List.of()));
            else if (layerMask != -1) resetLayerSelection();
            entityTime = worldTime;
            this.frozenWorld = frozenWorld || simulationSpeed == 0;
            hostSecondsPerSimulationSecond = this.frozenWorld ? 0 : 1 / simulationSpeed;
            boolean allLayers = world.layerMask() == -1;
            var quality = world.quality();
            configureQuality(quality);
            // Low quality follows Unity's static-only voxel cache semantics and omits dynamic entity sweeps.
            boolean dynamicEnabled = world.dynamicColliders() && quality != UnityCollision.Quality.LOW
                    && (allLayers || !entityTypes.isEmpty() || !entityTags.isEmpty());
            entitySource = dynamicEnabled
                    ? (bounds, maximum, t, scale) -> entityTargets(level, bounds, maximum, t, scale)
                    : null;
            if (!dynamicEnabled) entityCellCache.clear();
            var blocks = allLayers || blockLayersSelected ? CollisionWorld.of(level) : null;
            if (blocks != null) {
                var a = previous.position();
                var b = next.position();
                double minX = Math.min(a.x, b.x) - 16.0, minY = Math.min(a.y, b.y) - 16.0, minZ = Math.min(a.z, b.z) - 16.0;
                double maxX = Math.max(a.x, b.x) + 16.0, maxY = Math.max(a.y, b.y) + 16.0, maxZ = Math.max(a.z, b.z) + 16.0;
                blocks.prefetch(minX, minY, minZ, maxX, maxY, maxZ);
            }
            if (quality != UnityCollision.Quality.HIGH
                    && (voxelCacheLevel != level || (blocks != null && voxelWorldVersion != blocks.version()))) {
                voxelCache.clear();
                voxelCacheLevel = level;
                voxelWorldVersion = blocks != null ? blocks.version() : Integer.MIN_VALUE;
            }
            BlockBoxSource blockSource = blocks == null ? null
                    : quality == UnityCollision.Quality.HIGH ? blocks : approximateBlockSource(blocks, blocks, quality);
            scene = new CollisionScene(blockSource, blockSource == null ? 0 : BlockBoxSource.BLOCKS, blockFilter,
                    CollisionScene.NO_ENTITIES, CollisionScene.NO_COLLIDERS, stats, !world.mode2d());
        } else {
            entitySource = null;
            entityCellCache.clear();
            voxelCacheLevel = null;
            scene = null;
        }
        pose = next;
        before = previous;
        frameStart = previousTime;
        frameEnd = now;
    }

    /** Configures world block geometry directly for offline simulation / quality / 2D verification. */
    public void prepareBlockSource(BlockBoxSource rawBlocks, int worldVersion, Pose currentPose,
                                   double previousTime, double now) {
        prepareSources(rawBlocks, null, worldVersion, currentPose, previousTime, now, UnityCollisionLayers.EMPTY);
    }

    /** Configures offline block, entity, and layer inputs directly for simulation / quality / 2D verification. */
    public void prepareSources(@Nullable BlockBoxSource rawBlocks, @Nullable EntityBoxSource rawEntities, int worldVersion,
                               Pose currentPose, double previousTime, double now, UnityCollisionLayers layers) {
        prepareSources(rawBlocks, rawEntities, worldVersion, currentPose, previousTime, now, layers,
                blocks -> BlockFilter.of(blocks, List.of()));
    }

    /** Overload allowing offline tests without an FML registry environment to customize block filter construction. */
    public void prepareSources(@Nullable BlockBoxSource rawBlocks, @Nullable EntityBoxSource rawEntities, int worldVersion,
                               Pose currentPose, double previousTime, double now, UnityCollisionLayers layers,
                               java.util.function.Function<List<String>, @Nullable BlockFilter> filterResolver) {
        var world = Objects.requireNonNull(settings.world());
        worldSpaceContacts = true;
        if (world.layerMask() != -1) prepareLayers(world.layerMask(), layers, filterResolver);
        else if (layerMask != -1) resetLayerSelection();
        var quality = world.quality();
        configureQuality(quality);
        entityTime = now;
        frozenWorld = false;
        hostSecondsPerSimulationSecond = 1.0;
        boolean allLayers = world.layerMask() == -1;
        boolean dynamicEnabled = world.dynamicColliders() && rawEntities != null && quality != UnityCollision.Quality.LOW
                && (allLayers || !entityTypes.isEmpty() || !entityTags.isEmpty());
        entitySource = dynamicEnabled ? rawEntities : null;
        if (!dynamicEnabled) entityCellCache.clear();
        if (quality != UnityCollision.Quality.HIGH && (voxelCacheLevel != null || voxelWorldVersion != worldVersion)) {
            voxelCache.clear();
            voxelCacheLevel = null;
            voxelWorldVersion = worldVersion;
        }
        boolean staticEnabled = rawBlocks != null && (allLayers || blockLayersSelected);
        BlockBoxSource source = !staticEnabled ? null
                : quality == UnityCollision.Quality.HIGH ? rawBlocks : approximateBlockSource(rawBlocks, null, quality);
        scene = new CollisionScene(source, source == null ? 0 : BlockBoxSource.BLOCKS, blockFilter,
                CollisionScene.NO_ENTITIES, CollisionScene.NO_COLLIDERS, stats, !world.mode2d());
        pose = currentPose;
        before = currentPose;
        frameStart = previousTime;
        frameEnd = now;
    }

    private void configureQuality(UnityCollision.Quality quality) {
        stepCounter++;
        if (quality == UnityCollision.Quality.HIGH) {
            response.maxBounces = 0;
            response.maxContacts = 4;
            entityCellCache.clear();
        } else if (quality == UnityCollision.Quality.MEDIUM) {
            response.maxBounces = 1;
            response.maxContacts = 2;
            voxelQueriesRemaining = 4;
            if ((stepCounter & 1) != 0) entityCellCache.clear();
        } else {
            response.maxBounces = 1;
            response.maxContacts = 1;
            voxelQueriesRemaining = 1;
            entityCellCache.clear();
        }
    }

    private void resetLayerSelection() {
        voxelCache.clear();
        layerInputs = null;
        layerMask = -1;
        layerTagGeneration = 0;
        blockFilter = null;
        blockLayersSelected = false;
        entityTypes = Set.of();
        entityTags = List.of();
    }

    private BlockBoxSource approximateBlockSource(BlockBoxSource raw, @Nullable CollisionWorld world,
                                                  UnityCollision.Quality quality) {
        final double vs = Objects.requireNonNull(settings.world()).voxelSize();
        return (minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out) -> {
            long vx0 = (long) Math.floor(minX / vs);
            long vy0 = (long) Math.floor(minY / vs);
            long vz0 = (long) Math.floor(minZ / vs);
            long vx1 = (long) Math.floor((maxX - 1e-9) / vs);
            long vy1 = (long) Math.floor((maxY - 1e-9) / vs);
            long vz1 = (long) Math.floor((maxZ - 1e-9) / vs);
            boolean readable = true;
            for (long vx = vx0; vx <= vx1; vx++) {
                for (long vy = vy0; vy <= vy1; vy++) {
                    for (long vz = vz0; vz <= vz1; vz++) {
                        VoxelKey vkey = new VoxelKey(vx, vy, vz);
                        CachedVoxel cached = voxelCache.get(vkey);
                        if (cached == null) {
                            if (voxelQueriesRemaining <= 0) {
                                // Budget exhaustion skips uncached voxel probes without fabricating an unloaded-chunk freeze.
                                continue;
                            }
                            voxelQueriesRemaining--;
                            voxelProbe.clear();
                            double cminX = vx * vs, cminY = vy * vs, cminZ = vz * vs;
                            double cmaxX = (vx + 1) * vs, cmaxY = (vy + 1) * vs, cmaxZ = (vz + 1) * vs;
                            boolean chunkReady = raw.collect(cminX, cminY, cminZ, cmaxX, cmaxY, cmaxZ, mask, filter, voxelProbe);
                            if (!chunkReady) {
                                readable = false;
                                continue;
                            }
                            if (voxelProbe.isEmpty()) {
                                voxelCache.put(vkey, CachedVoxel.EMPTY);
                                continue;
                            }
                            if (quality == UnityCollision.Quality.LOW) {
                                int chosen = 0;
                                double bestDistSq = Double.POSITIVE_INFINITY;
                                double vxCenter = (cminX + cmaxX) * 0.5;
                                double vyCenter = (cminY + cmaxY) * 0.5;
                                double vzCenter = (cminZ + cmaxZ) * 0.5;
                                int probeCount = voxelProbe.size();
                                for (int idx = 0; idx < probeCount; idx++) {
                                    double bx = (voxelProbe.minX(idx) + voxelProbe.maxX(idx)) * 0.5;
                                    double by = (voxelProbe.minY(idx) + voxelProbe.maxY(idx)) * 0.5;
                                    double bz = (voxelProbe.minZ(idx) + voxelProbe.maxZ(idx)) * 0.5;
                                    double dSq = (bx - vxCenter) * (bx - vxCenter)
                                            + (by - vyCenter) * (by - vyCenter)
                                            + (bz - vzCenter) * (bz - vzCenter);
                                    if (dSq < bestDistSq) {
                                        bestDistSq = dSq;
                                        chosen = idx;
                                    }
                                }
                                cached = new CachedVoxel(
                                        new double[] { cminX, cminY, cminZ, cmaxX, cmaxY, cmaxZ },
                                        new byte[] { voxelProbe.kind(chosen) },
                                        new long[] { voxelProbe.key(chosen) },
                                        new Object[] { voxelProbe.payload(chosen) });
                            } else {
                                int count = voxelProbe.size();
                                double[] cBoxes = new double[count * 6];
                                byte[] cKinds = new byte[count];
                                long[] cKeys = new long[count];
                                Object[] cPayloads = new Object[count];
                                int kept = 0;
                                for (int idx = 0; idx < count; idx++) {
                                    double bminX = Math.max(cminX, voxelProbe.minX(idx));
                                    double bminY = Math.max(cminY, voxelProbe.minY(idx));
                                    double bminZ = Math.max(cminZ, voxelProbe.minZ(idx));
                                    double bmaxX = Math.min(cmaxX, voxelProbe.maxX(idx));
                                    double bmaxY = Math.min(cmaxY, voxelProbe.maxY(idx));
                                    double bmaxZ = Math.min(cmaxZ, voxelProbe.maxZ(idx));
                                    if (bmaxX <= bminX + 1e-9 || bmaxY <= bminY + 1e-9 || bmaxZ <= bminZ + 1e-9) {
                                        continue;
                                    }
                                    int base = kept * 6;
                                    cBoxes[base] = bminX; cBoxes[base + 1] = bminY; cBoxes[base + 2] = bminZ;
                                    cBoxes[base + 3] = bmaxX; cBoxes[base + 4] = bmaxY; cBoxes[base + 5] = bmaxZ;
                                    cKinds[kept] = voxelProbe.kind(idx);
                                    cKeys[kept] = voxelProbe.key(idx);
                                    cPayloads[kept] = voxelProbe.payload(idx);
                                    kept++;
                                }
                                if (kept == 0) {
                                    cached = CachedVoxel.EMPTY;
                                } else {
                                    cached = kept == count
                                            ? new CachedVoxel(cBoxes, cKinds, cKeys, cPayloads)
                                            : new CachedVoxel(Arrays.copyOf(cBoxes, kept * 6),
                                                    Arrays.copyOf(cKinds, kept),
                                                    Arrays.copyOf(cKeys, kept),
                                                    Arrays.copyOf(cPayloads, kept));
                                }
                            }
                            voxelCache.put(vkey, cached);
                        }
                        if (!cached.isEmpty()) {
                            int nBoxes = cached.keys().length;
                            for (int idx = 0; idx < nBoxes; idx++) {
                                int base = idx * 6;
                                out.add(cached.boxes()[base], cached.boxes()[base + 1], cached.boxes()[base + 2],
                                        cached.boxes()[base + 3], cached.boxes()[base + 4], cached.boxes()[base + 5],
                                        cached.kinds()[idx], cached.keys()[idx], cached.payloads()[idx]);
                            }
                        }
                    }
                }
            }
            return readable;
        };
    }

    private void prepareLayers(int mask, UnityCollisionLayers inputs,
                               java.util.function.Function<List<String>, @Nullable BlockFilter> filterResolver) {
        int generation = BlockFilter.tagGeneration();
        if (inputs == layerInputs && generation == layerTagGeneration && mask == layerMask) return;
        voxelCache.clear();
        entityCellCache.clear();
        List<String> blocks = new ArrayList<>();
        Set<Identifier> entities = new HashSet<>();
        Set<TagKey<EntityType<?>>> tags = new HashSet<>();
        inputs.layers().forEach((index, layer) -> {
            if ((mask & (1 << index)) == 0) return;
            blocks.addAll(layer.blocks());
            for (String selector : layer.entities()) {
                if (selector.startsWith("#")) tags.add(TagKey.create(Registries.ENTITY_TYPE, Identifier.parse(selector.substring(1))));
                else entities.add(Identifier.parse(selector));
            }
        });
        blockLayersSelected = !blocks.isEmpty();
        blockFilter = filterResolver.apply(blocks);
        entityTypes = Set.copyOf(entities);
        entityTags = List.copyOf(tags);
        layerInputs = inputs;
        layerMask = mask;
        layerTagGeneration = generation;
    }

    private boolean entityLayerSelected(Entity entity) {
        if (Objects.requireNonNull(settings.world()).layerMask() == -1) return true;
        var type = entity.getType();
        if (entityTypes.contains(EntityType.getKey(type))) return true;
        for (var tag : entityTags) if (type.builtInRegistryHolder().is(tag)) return true;
        return false;
    }

    private CollisionScene planeScene(@Nullable Pose transform) {
        var planes = settings.planes();
        var targets = new ColliderTarget[planes.size()];
        for (int index = 0; index < targets.length; index++) {
            var plane = planes.get(index);
            var point = plane.point();
            var normal = plane.normal();
            if (transform != null) {
                point = transform.transformPosition(point);
                normal = transformNormal(transform, normal);
            }
            targets[index] = ColliderTarget.plane(index, null, false, point.x, point.y, point.z, normal.x, normal.y, normal.z);
        }
        return new CollisionScene(null, 0, null, CollisionScene.NO_ENTITIES, targets, stats);
    }

    private static Vec3 transformNormal(Pose pose, Vec3 normal) {
        // Surface normals use the inverse-transpose of the owner transform.
        var axis = new Vector3d(normal.x, normal.y, normal.z);
        if (pose.scaleX() != 0 && pose.scaleY() != 0 && pose.scaleZ() != 0)
            axis.div(pose.scaleX(), pose.scaleY(), pose.scaleZ());
        new Quaterniond(pose.x(), pose.y(), pose.z(), pose.w()).transform(axis).normalize();
        return new Vec3(axis.x, axis.y, axis.z);
    }

    private EntityTarget[] entityTargets(Level level, AABB bounds, int maximum, double now,
                                         double hostSecondsPerSimulationSecond) {
        var found = level.getEntities((Entity) null, bounds.inflate(CollisionScene.MAX_TARGET_MOTION), entity ->
                entity.isAlive() && !entity.isSpectator() && (entity.isPickable() || entity.canBeCollidedWith(null))
                        && entityLayerSelected(entity));
        if (found.isEmpty()) return CollisionScene.NO_ENTITIES;
        if (found.size() > maximum) {
            var center = bounds.getCenter();
            found.sort(Comparator.comparingDouble((Entity entity) -> entity.position().distanceToSqr(center))
                    .thenComparingInt(Entity::getId));
        }
        var targets = new EntityTarget[Math.min(maximum, found.size())];
        double partial = Math.clamp(now * TICKS_PER_SECOND - level.getGameTime(), 0, 1);
        double tickMotionScale = TICKS_PER_SECOND * hostSecondsPerSimulationSecond;
        for (int index = 0; index < targets.length; index++) {
            var entity = found.get(index);
            double x = entity.getX() - entity.xo, y = entity.getY() - entity.yo, z = entity.getZ() - entity.zo;
            if (x * x + y * y + z * z > CollisionScene.MAX_TARGET_MOTION * CollisionScene.MAX_TARGET_MOTION) {
                x = y = z = 0;
            }
            var box = entity.getBoundingBox().move(x * (partial - 1), y * (partial - 1), z * (partial - 1));
            targets[index] = new EntityTarget(entity, entity.getId(), box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                    x * tickMotionScale, y * tickMotionScale, z * tickMotionScale, false);
        }
        return targets;
    }

    private EntityTarget[] queryCachedEntities(EntityBoxSource source, AABB bounds, int shapeCap) {
        int cellSize = 1 << ENTITY_CELL_SHIFT;
        int cx0 = (int) Math.floor(bounds.minX) >> ENTITY_CELL_SHIFT;
        int cy0 = (int) Math.floor(bounds.minY) >> ENTITY_CELL_SHIFT;
        int cz0 = (int) Math.floor(bounds.minZ) >> ENTITY_CELL_SHIFT;
        int cx1 = (int) Math.floor(bounds.maxX) >> ENTITY_CELL_SHIFT;
        int cy1 = (int) Math.floor(bounds.maxY) >> ENTITY_CELL_SHIFT;
        int cz1 = (int) Math.floor(bounds.maxZ) >> ENTITY_CELL_SHIFT;
        HashMap<Integer, EntityTarget> dedup = null;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    long cellKey = BlockPos.asLong(cx, cy, cz);
                    EntityTarget[] cellTargets = entityCellCache.get(cellKey);
                    if (cellTargets == null) {
                        double x0 = cx * (double) cellSize, y0 = cy * (double) cellSize, z0 = cz * (double) cellSize;
                        AABB cellBounds = new AABB(x0, y0, z0, x0 + cellSize, y0 + cellSize, z0 + cellSize)
                                .inflate(CollisionScene.MAX_TARGET_MOTION);
                        cellTargets = source.collect(cellBounds, Integer.MAX_VALUE, entityTime, hostSecondsPerSimulationSecond);
                        entityCellCache.put(cellKey, cellTargets);
                    }
                    for (EntityTarget t : cellTargets) {
                        double em = CollisionScene.MAX_TARGET_MOTION;
                        if (t.maxX() + em >= bounds.minX && t.minX() - em <= bounds.maxX
                                && t.maxY() + em >= bounds.minY && t.minY() - em <= bounds.maxY
                                && t.maxZ() + em >= bounds.minZ && t.minZ() - em <= bounds.maxZ) {
                            if (dedup == null) dedup = new HashMap<>();
                            dedup.putIfAbsent(t.id(), t);
                        }
                    }
                }
            }
        }
        if (dedup == null || dedup.isEmpty()) return CollisionScene.NO_ENTITIES;
        if (dedup.size() <= shapeCap) return dedup.values().toArray(EntityTarget[]::new);
        Vec3 c = bounds.getCenter();
        List<EntityTarget> sorted = new ArrayList<>(dedup.values());
        sorted.sort(Comparator.comparingDouble((EntityTarget t) -> {
            double mx = (t.minX() + t.maxX()) * 0.5 - c.x;
            double my = (t.minY() + t.maxY()) * 0.5 - c.y;
            double mz = (t.minZ() + t.maxZ()) * 0.5 - c.z;
            return mx * mx + my * my + mz * mz;
        }).thenComparingInt(EntityTarget::id));
        return sorted.subList(0, shapeCap).toArray(EntityTarget[]::new);
    }

    public ContactSolver.Body move(double x, double y, double z, double dx, double dy, double dz,
                                  double speedX, double speedY, double speedZ, double time, double step, double radius, double age,
                                  double remainingLife, double startLifetime, double random, @Nullable Consumer<Impact> onImpact) {
        return move(x, y, z, dx, dy, dz, speedX, speedY, speedZ, time, step, radius, age,
                remainingLife, startLifetime, random, false, onImpact);
    }

    public ContactSolver.Body move(double x, double y, double z, double dx, double dy, double dz,
                                  double speedX, double speedY, double speedZ, double time, double step, double radius, double age,
                                  double remainingLife, double startLifetime, double random, boolean holdAtLifetimeEnd,
                                  @Nullable Consumer<Impact> onImpact) {
        center.set(x, y, z);
        travel.set(dx, dy, dz);
        velocity.set(speedX, speedY, speedZ);
        frameMotion.zero();
        double worldRadius = radius;
        boolean transform = worldSpaceContacts && local;
        if (transform) worldRadius = transformMotion(time, step, radius);
        body.cx = center.x;
        body.cy = center.y;
        body.cz = center.z;
        double scaledRadius = worldRadius * settings.radiusScale();
        body.hx = body.hy = body.hz = scaledRadius;
        currentParticleDiameter = Math.max(0.0, scaledRadius * 2.0);
        body.vx = travel.x / step;
        body.vy = travel.y / step;
        body.vz = travel.z / step;
        body.svx = velocity.x;
        body.svy = velocity.y;
        body.svz = velocity.z;
        body.previousAxes = 0;
        body.previousTarget = -1;
        body.bounces = body.impacts = 0;
        response.dampen = Math.clamp(settings.dampen().sample(age, random), 0, 1);
        response.bounceRate = Math.max(0, settings.bounce().sample(age, random));
        response.lifetimeLoss = Math.clamp(settings.lifetimeLoss().sample(age, random), 0, 1);
        response.remainingLife = remainingLife / startLifetime;
        response.lifeConsumedPerTime = 1 / startLifetime;
        response.holdAtLifetimeEnd = holdAtLifetimeEnd;
        response.frameVx = frameMotion.x;
        response.frameVy = frameMotion.y;
        response.frameVz = frameMotion.z;
        response.entityEndOffset = frozenWorld ? 0 : time + step - frameEnd;
        int freeAxis = settings.world() != null ? settings.world().plane2d().freeAxis() : -1;
        boolean mode2d = freeAxis >= 0;
        double savedWorldFreeD = freeAxis == 0 ? travel.x : freeAxis == 1 ? travel.y : travel.z;
        double savedWorldFreeSpeed = freeAxis == 0 ? velocity.x : freeAxis == 1 ? velocity.y : velocity.z;
        if (mode2d) {
            // Restrict 2D collision sweeps strictly to the active 2D collision plane (host XY or ConeVolume XZ).
            switch (freeAxis) {
                case 0 -> { body.hx = Math.min(body.hx, MODE_2D_SLICE_HALF); body.vx = 0; body.svx = 0; }
                case 1 -> { body.hy = Math.min(body.hy, MODE_2D_SLICE_HALF); body.vy = 0; body.svy = 0; }
                case 2 -> { body.hz = Math.min(body.hz, MODE_2D_SLICE_HALF); body.vz = 0; body.svz = 0; }
            }
        }
        var stepScene = scene;
        if (stepScene == null) {
            body.touched = false;
            body.moveX = dx;
            body.moveY = dy;
            body.moveZ = dz;
            return body;
        }
        if (entitySource != null) {
            var world = Objects.requireNonNull(settings.world());
            int shapeCap = world.quality() == UnityCollision.Quality.MEDIUM
                    ? Math.max(1, world.maxDynamicShapes() / 2)
                    : world.maxDynamicShapes();
            double activeTravel = switch (freeAxis) {
                case 0 -> Math.hypot(travel.y, travel.z);
                case 1 -> Math.hypot(travel.x, travel.z);
                case 2 -> Math.hypot(travel.x, travel.y);
                default -> travel.length();
            };
            double planarRadius = switch (freeAxis) {
                case 0 -> Math.max(body.hy, body.hz);
                case 1 -> Math.max(body.hx, body.hz);
                default -> Math.max(body.hx, body.hy);
            };
            double activeReach = activeTravel * Math.max(1, response.bounceRate) + planarRadius
                    + Math.abs(response.entityEndOffset) * hostSecondsPerSimulationSecond
                            * TICKS_PER_SECOND * CollisionScene.MAX_TARGET_MOTION;
            double reachX = freeAxis == 0 ? body.hx : activeReach;
            double reachY = freeAxis == 1 ? body.hy : activeReach;
            double reachZ = freeAxis == 2 ? body.hz : activeReach;
            var bounds = new AABB(center.x - reachX, center.y - reachY, center.z - reachZ,
                    center.x + reachX, center.y + reachY, center.z + reachZ);
            EntityTarget[] entities = world.quality() == UnityCollision.Quality.MEDIUM
                    ? queryCachedEntities(entitySource, bounds, shapeCap)
                    : entitySource.collect(bounds, shapeCap, entityTime, hostSecondsPerSimulationSecond);
            if (entities.length > 0) stepScene = new CollisionScene(stepScene.blocks, stepScene.blockMask, blockFilter, entities,
                    CollisionScene.NO_COLLIDERS, stats, !mode2d);
        }
        impactListener = onImpact;
        impactStepStart = time;
        try {
            ContactSolver.step(CollisionQuery.get(), stepScene, body, response, step, responseRandom,
                    onImpact == null ? null : impactEvents, contact, scratch);
        } finally {
            impactListener = null;
        }
        if (mode2d) {
            switch (freeAxis) {
                case 0 -> { body.moveX = transform ? savedWorldFreeD : dx; body.svx = transform ? savedWorldFreeSpeed : speedX; }
                case 1 -> { body.moveY = transform ? savedWorldFreeD : dy; body.svy = transform ? savedWorldFreeSpeed : speedY; }
                case 2 -> { body.moveZ = transform ? savedWorldFreeD : dz; body.svz = transform ? savedWorldFreeSpeed : speedZ; }
            }
        }
        if (transform && (body.touched || body.incomplete)) {
            center.add(body.moveX, body.moveY, body.moveZ);
            Pose from = Objects.requireNonNull(before), to = Objects.requireNonNull(pose);
            double end = body.killed ? time + body.elapsedOnDeath : time + step;
            double fraction = frameFraction(end);
            setInverse(from, to, fraction);
            center.sub(lerp(from.position().x, to.position().x, fraction),
                    lerp(from.position().y, to.position().y, fraction), lerp(from.position().z, to.position().z, fraction));
            inverse.transform(center).mul(inverseX, inverseY, inverseZ);
            velocity.set(body.svx, body.svy, body.svz).sub(frameMotion);
            inverse.transform(velocity).mul(inverseX, inverseY, inverseZ);
            body.moveX = inverseX == 0 ? dx : center.x - x;
            body.moveY = inverseY == 0 ? dy : center.y - y;
            body.moveZ = inverseZ == 0 ? dz : center.z - z;
            body.svx = inverseX == 0 ? speedX : velocity.x;
            body.svy = inverseY == 0 ? speedY : velocity.y;
            body.svz = inverseZ == 0 ? speedZ : velocity.z;
        } else if (transform) {
            body.moveX = dx;
            body.moveY = dy;
            body.moveZ = dz;
        }
        return body;
    }

    private Pose interpolatePose(double time) {
        Pose from = Objects.requireNonNull(before), to = Objects.requireNonNull(pose);
        double fraction = frameFraction(time);
        interpolateRotation(from, to, fraction);
        return new Pose(from.position().lerp(to.position(), fraction), (float) rotation.x, (float) rotation.y,
                (float) rotation.z, (float) rotation.w, (float) lerp(from.scaleX(), to.scaleX(), fraction),
                (float) lerp(from.scaleY(), to.scaleY(), fraction), (float) lerp(from.scaleZ(), to.scaleZ(), fraction));
    }

    private double transformMotion(double time, double step, double radius) {
        Pose from = Objects.requireNonNull(before), to = Objects.requireNonNull(pose);
        double start = frameFraction(time), end = frameFraction(time + step);
        startCenter.set(center);
        transformPoint(center, from, to, start);
        transformPoint(startCenter, from, to, end);
        frameMotion.set(startCenter).sub(center).div(step);
        travel.mul(beforeScale);
        rotation.transform(travel).add(startCenter).sub(center);
        velocity.mul(beforeScale);
        rotation.transform(velocity).add(frameMotion);
        return radius * Math.max(Math.abs(beforeScale.x), Math.max(Math.abs(beforeScale.y), Math.abs(beforeScale.z)));
    }

    private void transformPoint(Vector3d point, Pose from, Pose to, double fraction) {
        interpolateRotation(from, to, fraction);
        beforeScale.set(lerp(from.scaleX(), to.scaleX(), fraction), lerp(from.scaleY(), to.scaleY(), fraction),
                lerp(from.scaleZ(), to.scaleZ(), fraction));
        point.mul(beforeScale);
        rotation.transform(point).add(lerp(from.position().x, to.position().x, fraction),
                lerp(from.position().y, to.position().y, fraction), lerp(from.position().z, to.position().z, fraction));
    }

    private void setInverse(Pose from, Pose to, double fraction) {
        interpolateRotation(from, to, fraction);
        inverse.set(rotation).conjugate();
        inverseX = inverseScale(lerp(from.scaleX(), to.scaleX(), fraction));
        inverseY = inverseScale(lerp(from.scaleY(), to.scaleY(), fraction));
        inverseZ = inverseScale(lerp(from.scaleZ(), to.scaleZ(), fraction));
    }

    private void interpolateRotation(Pose from, Pose to, double fraction) {
        rotation.set(from.x(), from.y(), from.z(), from.w())
                .slerp(inverse.set(to.x(), to.y(), to.z(), to.w()), fraction).normalize();
    }

    private double frameFraction(double time) {
        return frameEnd <= frameStart ? 1 : Math.clamp((time - frameStart) / (frameEnd - frameStart), 0, 1);
    }

    private static double lerp(double from, double to, double fraction) {
        return from + (to - from) * fraction;
    }

    private static double inverseScale(double value) {
        return value == 0 ? 0 : 1 / value;
    }
}
