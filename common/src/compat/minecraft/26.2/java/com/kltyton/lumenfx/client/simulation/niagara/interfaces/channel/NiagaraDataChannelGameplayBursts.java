package com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.NiagaraDataChannels.AccessContext;
import com.kltyton.lumenfx.api.client.VfxEffects;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Buffer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.World;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Address;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.AttachmentSettings;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Channel;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.GameplayBurst;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Buckets, routes, and spawns handlers matching UE5's UNiagaraDataChannelHandler_GameplayBurst. */
final class NiagaraDataChannelGameplayBursts {
    @FunctionalInterface
    interface HandlerSpawner {
        @Nullable EffectHandle spawn(@Nullable ClientLevel level, Identifier effect, Vec3 position, long seed);
    }

    record BucketKey(boolean attached, @Nullable Object ownerKey, long cellX, long cellY, long cellZ,
                     @Nullable Vec3 cellSizeOverride, String gameplayTag, String systemKey,
                     List<Identifier> systems) { }

    private final World world;
    private final Channel channel;
    private final GameplayBurst settings;
    private final HandlerSpawner spawner;
    private final Map<BucketKey, Bucket> active = new HashMap<>();
    private final ArrayDeque<Bucket> freePool = new ArrayDeque<>();

    NiagaraDataChannelGameplayBursts(World world, Channel channel) {
        this(world, channel, (level, effect, position, seed) ->
                level == null ? null : VfxEffects.spawn(level, effect, position, seed));
    }

    NiagaraDataChannelGameplayBursts(World world, Channel channel, HandlerSpawner spawner) {
        this.world = world;
        this.channel = channel;
        this.settings = Objects.requireNonNull(channel.gameplayBurst());
        this.spawner = spawner;
    }

    Buffer acquire(Vec3 sourceLocation, AccessContext context) {
        return acquire(sourceLocation, context, null);
    }

    Buffer acquire(Vec3 sourceLocation, AccessContext context, @Nullable EffectHandle requester) {
        List<Identifier> systems;
        String systemKey;
        if (context.overrideSystemToSpawn()) {
            systems = !context.systemsToSpawn().isEmpty() ? context.systemsToSpawn()
                    : context.systemToSpawn() != null ? List.of(context.systemToSpawn()) : List.of();
            if (context.selectedSystemKey() != null && !context.selectedSystemKey().isEmpty()) {
                systemKey = "selected:" + context.selectedSystemKey();
            } else {
                systemKey = !systems.isEmpty() ? "ids:" + systems : "";
            }
        } else {
            systems = settings.systems();
            if (!settings.defaultSelectedSystem().isEmpty()) {
                systemKey = "selected:" + settings.defaultSelectedSystem();
            } else {
                systemKey = !systems.isEmpty() ? "ids:" + systems : "";
            }
        }
        Object effectiveOwnerKey = context.ownerKey();
        @Nullable NiagaraBounds effectiveBounds = context.ownerBounds();
        @Nullable Supplier<Pose> effectivePose = context.ownerPose();
        @Nullable BooleanSupplier effectiveValid = context.ownerValid();
        boolean unwrappedFromAttachedChild = false;
        boolean attach = false;
        EffectHandle attachedCandidate = requester != null ? requester
                : effectiveOwnerKey instanceof EffectHandle handleKey ? handleKey : null;
        if (attachedCandidate != null) {
            NiagaraDataChannelWorlds.AttachedHandlerOwner tagParent = world.attachedHandlers.get(attachedCandidate);
            if (tagParent != null) {
                unwrappedFromAttachedChild = true;
                int guard = 0;
                while (tagParent.parentOwnerKey() instanceof EffectHandle nested && guard++ < 8) {
                    NiagaraDataChannelWorlds.AttachedHandlerOwner upper = world.attachedHandlers.get(nested);
                    if (upper == null) break;
                    tagParent = upper;
                }
                effectiveOwnerKey = tagParent.parentOwnerKey();
                if (tagParent.parentBounds() != null) effectiveBounds = tagParent.parentBounds();
                if (tagParent.parentPose() != null) effectivePose = tagParent.parentPose();
                if (tagParent.parentValid() != null) effectiveValid = tagParent.parentValid();
                attach = effectiveOwnerKey != null;
            }
        }
        if (!unwrappedFromAttachedChild) {
            attach = useAttachedPath(context, settings.attachment());
        }
        BucketKey key;
        if (attach) {
            key = new BucketKey(true, effectiveOwnerKey, 0, 0, 0, null, "", systemKey, systems);
        } else {
            Vec3 cellSize = context.cellSizeOverride() != null ? context.cellSizeOverride() : settings.cellSize();
            long cx = (long) Math.floor(sourceLocation.x / cellSize.x);
            long cy = (long) Math.floor(sourceLocation.y / cellSize.y);
            long cz = (long) Math.floor(sourceLocation.z / cellSize.z);
            key = new BucketKey(false, null, cx, cy, cz,
                    context.cellSizeOverride(), context.gameplayTag(), systemKey, systems);
        }
        Bucket existing = active.get(key);
        if (existing != null) {
            if (attach && !existing.isOwnerValid()) {
                existing.release();
                active.remove(key);
                freePool.addLast(existing);
            } else {
                if (attach && !unwrappedFromAttachedChild) existing.updateAttached(sourceLocation, context);
                return existing.data();
            }
        }
        Bucket bucket = freePool.pollLast();
        if (bucket == null) bucket = new Bucket();
        try {
            bucket.acquire(key, sourceLocation, context, effectiveBounds, effectivePose, effectiveValid);
            active.put(key, bucket);
            return bucket.data();
        } catch (RuntimeException | Error failure) {
            bucket.release();
            freePool.addLast(bucket);
            throw failure;
        }
    }

    static boolean useAttachedPath(AccessContext context, AttachmentSettings attachment) {
        if (context.ownerKey() == null) return false;
        if (context.forceAttach()) return true;
        if (matchesComponentType(context.ownerComponentType(), context.ownerComponentSuperclasses(),
                attachment.componentTypes())) {
            return true;
        }
        if (!attachment.gameplayTags().isEmpty() && !context.ownerTags().isEmpty()) {
            for (String ownerTag : context.ownerTags()) {
                for (String requiredTag : attachment.gameplayTags()) {
                    if (matchesGameplayTag(ownerTag, requiredTag)) return true;
                }
            }
        }
        return attachment.speedThreshold() >= 0.0
                && (context.ownerSpeed() >= attachment.speedThreshold()
                    || context.attachParentSpeed() >= attachment.speedThreshold());
    }

    static boolean matchesGameplayTag(String ownerTag, String requiredTag) {
        String actual = ownerTag.trim().toLowerCase(java.util.Locale.ROOT);
        String required = requiredTag.trim().toLowerCase(java.util.Locale.ROOT);
        if (actual.isEmpty() || required.isEmpty()) return false;
        return actual.equals(required)
                || (actual.length() > required.length()
                    && actual.startsWith(required)
                    && actual.charAt(required.length()) == '.');
    }

    private static boolean matchesComponentType(@Nullable String actual, List<String> superclasses, List<String> allowed) {
        if (allowed.isEmpty() || actual == null || actual.isBlank()) return false;
        // Upstream UE5.9 (acf0265dd82938ee7ee971165f14f7bfea7d3808) NiagaraDataChannel_GameplayBurst.cpp:166
        // executes `ComponentTypes.Contains(Component->GetClass())` (exact class equality), even though
        // NiagaraDataChannel_GameplayBurst.h:74 comment says "including a subclass". Match exact class behavior here.
        String canonActual = com.kltyton.lumenfx.effect.niagara.parameter.SourceNames.canonical(actual);
        for (String expected : allowed) {
            if (com.kltyton.lumenfx.effect.niagara.parameter.SourceNames.canonical(expected).equals(canonActual)) {
                return true;
            }
        }
        return false;
    }

    void syncAttached() {
        for (Bucket bucket : active.values()) {
            bucket.syncAttached();
        }
    }

    void retireUnused() {
        Iterator<Map.Entry<BucketKey, Bucket>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BucketKey, Bucket> entry = it.next();
            Bucket bucket = entry.getValue();
            if (bucket.inUse()) continue;
            bucket.release();
            it.remove();
            freePool.addLast(bucket);
        }
    }

    void close() {
        for (Bucket bucket : active.values()) bucket.release();
        active.clear();
        freePool.clear();
    }

    int activeBucketCount() { return active.size(); }

    private final class Bucket {
        private boolean attached;
        private @Nullable Object ownerKey;
        private @Nullable Supplier<Pose> ownerPose;
        private @Nullable BooleanSupplier ownerValid;
        private @Nullable NiagaraBounds ownerBounds;
        private Vec3 padding = GameplayBurst.DEFAULT_BOUNDS_PADDING;
        private @Nullable Pose attachedPose;
        private Vec3 center = Vec3.ZERO;
        private Vec3 extent = Vec3.ZERO;
        private List<Identifier> systems = List.of();
        private EffectHandle[] handles = new EffectHandle[0];
        private @Nullable Buffer buffer;

        Buffer data() { return Objects.requireNonNull(buffer, "Inactive GameplayBurst data channel bucket"); }

        void acquire(BucketKey key, Vec3 sourceLocation, AccessContext context,
                     @Nullable NiagaraBounds effectiveBounds, @Nullable Supplier<Pose> effectivePose,
                     @Nullable BooleanSupplier effectiveValid) {
            this.attached = key.attached();
            this.ownerKey = key.ownerKey();
            this.ownerPose = effectivePose;
            this.ownerValid = effectiveValid;
            this.ownerBounds = effectiveBounds;
            this.padding = context.boundsPaddingOverride() != null ? context.boundsPaddingOverride() : settings.boundsPadding();
            this.systems = key.systems();
            this.handles = new EffectHandle[systems.size()];
            this.attachedPose = null;
            if (key.attached()) {
                Pose live = resolveLivePose();
                this.attachedPose = live;
                Vec3 ownerPos = live != null ? NiagaraDataChannelIslands.sourcePosition(live.position())
                        : context.ownerLocation() != null ? NiagaraDataChannelIslands.sourcePosition(context.ownerLocation())
                        : sourceLocation;
                center = ownerPos;
                extent = attachedExtents(ownerBounds, padding);
            } else {
                Vec3 cellSize = key.cellSizeOverride() != null ? key.cellSizeOverride() : settings.cellSize();
                Vec3 cellHalf = cellSize.scale(0.5);
                center = new Vec3(
                        key.cellX() * cellSize.x + cellHalf.x,
                        key.cellY() * cellSize.y + cellHalf.y,
                        key.cellZ() * cellSize.z + cellHalf.z);
                extent = cellHalf.add(padding);
            }
            Address address = new Address(channel, ++world.islandSequence);
            Vec3 tile = new Vec3(
                    NiagaraPositionParameters.origin(center.x),
                    NiagaraPositionParameters.origin(center.y),
                    NiagaraPositionParameters.origin(center.z));
            buffer = new Buffer(address, tile, this::activateHandlers);
            world.channels.put(address, buffer);
            activateHandlers();
        }

        void updateAttached(Vec3 sourceLocation, AccessContext context) {
            if (context.ownerPose() != null) this.ownerPose = context.ownerPose();
            if (context.ownerValid() != null) this.ownerValid = context.ownerValid();
            if (context.ownerBounds() != null) this.ownerBounds = context.ownerBounds();
            this.padding = context.boundsPaddingOverride() != null ? context.boundsPaddingOverride() : settings.boundsPadding();
            Pose live = resolveLivePose();
            this.attachedPose = live;
            Vec3 ownerPos = live != null ? NiagaraDataChannelIslands.sourcePosition(live.position())
                    : context.ownerLocation() != null ? NiagaraDataChannelIslands.sourcePosition(context.ownerLocation())
                    : sourceLocation;
            center = ownerPos;
            extent = attachedExtents(ownerBounds, padding);
            pushPoseToHandles();
        }

        void syncAttached() {
            if (!attached || !isOwnerValid()) return;
            Pose live = resolveLivePose();
            if (live == null) return;
            this.attachedPose = live;
            center = NiagaraDataChannelIslands.sourcePosition(live.position());
            extent = attachedExtents(ownerBounds, padding);
            pushPoseToHandles();
        }

        private @Nullable Pose resolveLivePose() {
            if (ownerPose != null) return ownerPose.get();
            if (ownerKey instanceof EffectHandle ownerHandle) return ownerHandle.input().pose();
            if (ownerKey instanceof Entity entity) return Pose.at(entity.position());
            return null;
        }

        boolean isOwnerValid() {
            if (!attached) return true;
            if (ownerValid != null && !ownerValid.getAsBoolean()) return false;
            if (ownerKey instanceof EffectHandle ownerHandle && !ownerHandle.isAlive()) return false;
            if (ownerKey instanceof Entity entity && (!entity.isAlive() || entity.isRemoved())) return false;
            return true;
        }

        private void pushPoseToHandles() {
            for (EffectHandle handle : handles) {
                if (handle != null && handle.isAlive()) updateHandler(handle);
            }
        }

        private Vec3 attachedExtents(@Nullable NiagaraBounds ownerBounds, Vec3 padding) {
            if (ownerBounds == null) return padding;
            double hx = Math.max(0.0, (ownerBounds.maxX() - ownerBounds.minX()) * 0.5);
            double hy = Math.max(0.0, (ownerBounds.maxY() - ownerBounds.minY()) * 0.5);
            double hz = Math.max(0.0, (ownerBounds.maxZ() - ownerBounds.minZ()) * 0.5);
            return new Vec3(hx + padding.x, hy + padding.y, hz + padding.z);
        }

        void activateHandlers() {
            for (int index = 0; index < systems.size(); index++) {
                EffectHandle handle = handles[index];
                if (handle != null && handle.isAlive()) continue;
                if (handle != null) {
                    world.handlerOwners.remove(handle);
                    world.attachedHandlers.remove(handle);
                }
                Vec3 spawnPos = attached && attachedPose != null ? attachedPose.position() : hostPosition(center);
                handle = spawner.spawn(world.level, systems.get(index), spawnPos, ThreadLocalRandom.current().nextLong());
                if (handle == null) continue;
                handles[index] = handle;
                world.handlerOwners.put(handle, data().address);
                if (attached) {
                    world.attachedHandlers.put(handle, new NiagaraDataChannelWorlds.AttachedHandlerOwner(
                            ownerKey, ownerBounds, ownerPose, ownerValid));
                }
                updateHandler(handle);
            }
        }

        private void updateHandler(EffectHandle handle) {
            if (attached && attachedPose != null) handle.setPose(attachedPose);
            else handle.moveTo(hostPosition(center));
            handle.setNiagaraSystemFixedBounds(new NiagaraBounds(-extent.x, -extent.y, -extent.z, extent.x, extent.y, extent.z));
        }

        boolean inUse() {
            if (attached && !isOwnerValid()) return false;
            if (data().inUse()) return true;
            for (EffectHandle handle : handles) if (handle != null && handle.isAlive()) return true;
            return false;
        }

        void release() {
            if (buffer != null) world.channels.remove(buffer.address);
            for (int i = 0; i < handles.length; i++) {
                EffectHandle handle = handles[i];
                if (handle != null) {
                    world.handlerOwners.remove(handle);
                    world.attachedHandlers.remove(handle);
                    handle.invalidate();
                    handles[i] = null;
                }
            }
            buffer = null;
            ownerKey = null;
            ownerPose = null;
            ownerValid = null;
            attachedPose = null;
            attached = false;
        }
    }

    private static Vec3 hostPosition(Vec3 position) {
        return new Vec3(position.x / 100.0, position.z / 100.0, position.y / 100.0);
    }
}
