/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.collision;

import com.kltyton.lumenfx.client.concurrent.VfxFrameExecutor.OwnerRequests;
import com.lowdragmc.photon.PhotonConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * A level's collision geometry as immutable 16³ section copies, kept current by block, chunk, and per-tick dynamic
 * block-entity refreshes. Queries are thread-safe. Missing sections and dynamic shape updates are built strictly on
 * the owning game thread. Parallel workers can request a missing section while the owner joins their batch.
 */
public final class CollisionWorld implements BlockBoxSource {
    // the world holds its level weakly, or the map would keep every level alive
    private static final Map<Level, CollisionWorld> WORLDS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong IDS = new AtomicLong();
    private static final int EVICT_INTERVAL = 200;
    private static final int EVICT_AGE = 1200;

    private static volatile @Nullable BooleanSupplier clientOwnerCheck;

    public static void clientOwnerCheck(BooleanSupplier ownerCheck) {
        clientOwnerCheck = ownerCheck;
    }

    private final WeakReference<Level> level;
    final long id = IDS.incrementAndGet();
    private final boolean client;
    private final Thread ownerThread;
    private final int minSection, maxSection;
    private final ConcurrentHashMap<Long, SectionSnapshot> sections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, SectionSnapshot> dynamicSections = new ConcurrentHashMap<>();
    private volatile @Nullable OwnerRequests ownerRequests;
    /** Bumped by every invalidation or dynamic shape change; per-thread section caches are valid for one version. */
    private volatile int version;
    /** Game time at the latest {@link #of}, so workers never read the level for it. */
    private volatile long now;
    private long lastEviction;
    private long lastDynamicRefresh = Long.MIN_VALUE;

    private CollisionWorld(Level level) {
        this.level = new WeakReference<>(level);
        this.client = level.isClientSide();
        this.ownerThread = Thread.currentThread();
        this.minSection = level.getMinSectionY();
        // exclusive, as 1.21's getMaxSection was; 26.1's getMaxSectionY is the last section itself
        this.maxSection = level.getMaxSectionY() + 1;
    }

    /** The collision world of {@code level}, refreshed for the current tick. Game thread only. */
    public static CollisionWorld of(Level level) {
        var world = WORLDS.computeIfAbsent(level, CollisionWorld::new);
        world.prepare(level);
        return world;
    }

    @Nullable
    private static CollisionWorld existing(Level level) {
        return WORLDS.get(level);
    }

    public static void blockChanged(Level level, int x, int y, int z) {
        var world = existing(level);
        if (world != null) world.invalidateBlock(x, y, z);
    }

    public static void chunkChanged(Level level, int chunkX, int chunkZ) {
        var world = existing(level);
        if (world != null) world.invalidateColumn(chunkX, chunkZ);
    }

    /** Every block of {@code level} may have changed, as when a scene is rebuilt. */
    public static void blocksReplaced(Level level) {
        var world = existing(level);
        if (world != null) world.invalidateAll();
    }

    public static void levelUnloaded(Level level) {
        WORLDS.remove(level);
    }

    /** Null once the level has been garbage collected. */
    @Nullable
    public Level level() {
        return level.get();
    }

    public int sectionCount() {
        return sections.size();
    }

    /** Changes whenever any of its blocks may have. */
    public int version() {
        return version;
    }

    /** Whether particle workers may query collision worlds of {@code level}. */
    public static boolean isParallelSafe(Level level) {
        return level.isClientSide();
    }

    /** Workers can request exact missing section snapshots while the level owner joins the batch. */
    public void beginParallel(OwnerRequests requests) {
        if (!canBuild() || ownerRequests != null) throw new IllegalStateException("Collision snapshot batch has no available level owner");
        ownerRequests = requests;
    }

    public void endParallel(OwnerRequests requests) {
        if (ownerRequests == requests) ownerRequests = null;
    }

    private void prepare(Level level) {
        long now = level.getGameTime();
        this.now = now;
        if (now - lastEviction >= EVICT_INTERVAL || now < lastEviction) {
            lastEviction = now;
            boolean over = sections.size() > PhotonConfig.INSTANCE.collisionSectionCacheLimit.get();
            long oldest = over ? now - EVICT_INTERVAL : now - EVICT_AGE;
            // empty sections cost nothing to keep (chunk unloads still drop them) but a rescan to rebuild
            sections.entrySet().removeIf(entry -> {
                var snapshot = entry.getValue();
                boolean remove = snapshot == SectionSnapshot.EMPTY ? over : snapshot.lastUse < oldest;
                if (remove) {
                    dynamicSections.remove(entry.getKey());
                }
                return remove;
            });
        }
        if (now != lastDynamicRefresh && canBuild()) {
            lastDynamicRefresh = now;
            if (!dynamicSections.isEmpty()) {
                boolean changed = false;
                for (var entry : dynamicSections.entrySet()) {
                    long key = entry.getKey();
                    changed |= entry.getValue().refreshDynamic(level, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
                }
                if (changed) {
                    version++;
                }
            }
        }
    }

    /** Pre-populates cached sections covering the world-space bounds on the owning game thread before parallel queries. */
    public void prefetch(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) return;
        if (!canBuild() || level.get() == null) return;
        int x0 = Mth.floor(minX) - 1, x1 = Mth.floor(maxX) + 1;
        int z0 = Mth.floor(minZ) - 1, z1 = Mth.floor(maxZ) + 1;
        int y0 = Math.max(Mth.floor(minY) - 1, minSection << 4);
        int y1 = Math.min(Mth.floor(maxY) + 1, (maxSection << 4) - 1);
        if (y0 > y1) return;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    section(sx, sy, sz);
                }
            }
        }
    }

    private void invalidateBlock(int x, int y, int z) {
        long key = SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z));
        sections.remove(key);
        dynamicSections.remove(key);
        version++;
    }

    private void invalidateColumn(int chunkX, int chunkZ) {
        for (int sy = minSection; sy < maxSection; sy++) {
            long key = SectionPos.asLong(chunkX, sy, chunkZ);
            sections.remove(key);
            dynamicSections.remove(key);
        }
        version++;
    }

    private void invalidateAll() {
        sections.clear();
        dynamicSections.clear();
        version++;
    }

    /** Null when the section is missing and cannot be built on this thread. */
    @Nullable
    SectionSnapshot section(int sx, int sy, int sz) {
        if (sy < minSection || sy >= maxSection) return SectionSnapshot.EMPTY;
        long key = SectionPos.asLong(sx, sy, sz);
        var snapshot = sections.get(key);
        if (snapshot == null) {
            if (!canBuild()) {
                var requests = ownerRequests;
                return requests == null ? null : requests.read(() -> section(sx, sy, sz));
            }
            var level = this.level.get();
            if (level == null) return null;
            snapshot = sections.computeIfAbsent(key, k -> build(level, sx, sy, sz));
            if (snapshot.hasDynamic()) {
                dynamicSections.putIfAbsent(key, snapshot);
            }
        }
        touch(snapshot);
        return snapshot;
    }

    /** Keeps a section in use from being aged out. */
    private void touch(SectionSnapshot snapshot) {
        // at most once per tick: an unconditional write is a cache line every worker fights over
        long now = this.now;
        if (snapshot != SectionSnapshot.EMPTY && snapshot.lastUse != now) snapshot.lastUse = now;
    }

    @Nullable
    private SectionSnapshot cachedSection(BoxBuffer out, int sx, int sy, int sz) {
        if (sy < minSection || sy >= maxSection) return SectionSnapshot.EMPTY;
        long key = SectionPos.asLong(sx, sy, sz);
        int slot = BoxBuffer.sectionSlot(key);
        var cached = out.sectionValues[slot];
        if (cached != null && out.sectionKeys[slot] == key) {
            touch(cached);
            return cached;
        }
        var snapshot = section(sx, sy, sz);
        if (snapshot != null) {
            out.sectionKeys[slot] = key;
            out.sectionValues[slot] = snapshot;
        }
        return snapshot;
    }

    private boolean canBuild() {
        if (client) {
            var clientCheck = clientOwnerCheck;
            if (clientCheck != null) return clientCheck.getAsBoolean();
        }
        if (level.get() instanceof ServerLevel serverLevel) return serverLevel.getServer().isSameThread();
        return Thread.currentThread() == ownerThread;
    }

    private SectionSnapshot build(Level level, int sx, int sy, int sz) {
        if (!client && !(level instanceof ServerLevel)) return SectionSnapshot.build(level, sx, sy, sz, null);
        var chunk = level.getChunkSource().getChunkNow(sx, sz);
        return chunk == null ? SectionSnapshot.EMPTY : SectionSnapshot.build(level, sx, sy, sz, sectionOf(chunk, sy));
    }

    @Nullable
    private LevelChunkSection sectionOf(LevelChunk chunk, int sy) {
        int index = chunk.getSectionIndexFromSectionY(sy);
        var all = chunk.getSections();
        return index >= 0 && index < all.length ? all[index] : null;
    }

    /**
     * The state as collision sees it: AIR outside loaded geometry and in sections with nothing to collide with;
     * null when it cannot be read on this thread.
     */
    @Nullable
    public BlockState stateAt(int x, int y, int z) {
        var snapshot = section(x >> 4, y >> 4, z >> 4);
        if (snapshot == null) return null;
        if (snapshot == SectionSnapshot.EMPTY) return Blocks.AIR.defaultBlockState();
        return snapshot.palette[snapshot.paletteIndex(SectionSnapshot.cell(x, y, z))];
    }

    @Override
    public boolean collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                           int mask, @Nullable BlockFilter filter, BoxBuffer out) {
        if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) return true; // NaN or inverted: nothing to gather
        out.useSectionCache(this, version);
        int innerX0 = Mth.floor(minX), innerX1 = Mth.floor(maxX);
        int innerY0 = Mth.floor(minY), innerY1 = Mth.floor(maxY);
        int innerZ0 = Mth.floor(minZ), innerZ1 = Mth.floor(maxZ);
        // one cell of margin, for shapes that reach past their own cell
        int x0 = innerX0 - 1, x1 = innerX1 + 1, z0 = innerZ0 - 1, z1 = innerZ1 + 1;
        int y0 = Math.max(innerY0 - 1, minSection << 4), y1 = Math.min(innerY1 + 1, (maxSection << 4) - 1);
        if (y0 > y1) return true;
        boolean complete = true;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    var snapshot = cachedSection(out, sx, sy, sz);
                    if (snapshot == null) {
                        complete = false;
                        continue;
                    }
                    if (snapshot == SectionSnapshot.EMPTY) continue;
                    boolean margin = snapshot.anyLarge;
                    int fromX = Math.max(margin ? x0 : innerX0, sx << 4), toX = Math.min(margin ? x1 : innerX1, (sx << 4) + 15);
                    int fromY = Math.max(margin ? y0 : innerY0, sy << 4), toY = Math.min(margin ? y1 : innerY1, (sy << 4) + 15);
                    int fromZ = Math.max(margin ? z0 : innerZ0, sz << 4), toZ = Math.min(margin ? z1 : innerZ1, (sz << 4) + 15);
                    for (int x = fromX; x <= toX; x++) {
                        for (int z = fromZ; z <= toZ; z++) {
                            for (int y = fromY; y <= toY; y++) {
                                boolean edge = x < innerX0 || x > innerX1 || y < innerY0 || y > innerY1 || z < innerZ0 || z > innerZ1;
                                collectCell(snapshot, x, y, z, edge, minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out);
                            }
                        }
                    }
                }
            }
        }
        return complete;
    }

    private void collectCell(SectionSnapshot snapshot, int x, int y, int z, boolean edge,
                             double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                             int mask, @Nullable BlockFilter filter, BoxBuffer out) {
        int cell = SectionSnapshot.cell(x, y, z);
        int index = snapshot.paletteIndex(cell);
        var shape = snapshot.shapes[index];
        if ((mask & BLOCKS) != 0 && !shape.isEmpty() && (!edge || shape.large)) {
            var state = snapshot.palette[index];
            if (filter == null || filter.test(state)) {
                var boxes = shape.dynamic ? snapshot.dynamicBoxes(cell) : shape.boxes;
                for (int i = 0; i < boxes.length; i += 6) {
                    double bx0 = x + boxes[i], by0 = y + boxes[i + 1], bz0 = z + boxes[i + 2];
                    double bx1 = x + boxes[i + 3], by1 = y + boxes[i + 4], bz1 = z + boxes[i + 5];
                    if (bx0 < maxX && bx1 > minX && by0 < maxY && by1 > minY && bz0 < maxZ && bz1 > minZ) {
                        out.add(bx0, by0, bz0, bx1, by1, bz1, BoxBuffer.BLOCK, BlockPos.asLong(x, y, z), state);
                    }
                }
            }
        }
        if ((mask & FLUIDS) != 0 && !edge && snapshot.anyFluid) {
            var state = snapshot.palette[index];
            var fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                var aboveSection = cachedSection(out, x >> 4, (y + 1) >> 4, z >> 4);
                var above = aboveSection == null || aboveSection == SectionSnapshot.EMPTY ? null
                        : aboveSection.palette[aboveSection.paletteIndex(SectionSnapshot.cell(x, y + 1, z))];
                // only the top of a body of fluid is a surface
                if (above == null || !above.getFluidState().getType().isSame(fluid.getType())) {
                    double height = fluid.getOwnHeight();
                    if (x + 1 > minX && x < maxX && y + height > minY && y < maxY && z + 1 > minZ && z < maxZ) {
                        out.add(x, y, z, x + 1, y + height, z + 1, BoxBuffer.FLUID, BlockPos.asLong(x, y, z), state);
                    }
                }
            }
        }
    }
}
