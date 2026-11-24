package com.kltyton.lumenfx.api.client.attachment;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.VfxEffects;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Reusable client attachment host that binds fixed per-entity locator slots to a {@link VfxEffects} asset,
 * tracks per-frame deduplication, computes actor velocity in Unreal units, and releases handles automatically
 * when entities are removed or switch dimensions.
 */
public final class EntityLocatorAttachments {
    private static final long SEED_MIX = 0x9E3779B97F4A7C15L;
    private static final Set<EntityLocatorAttachments> REGISTRY = Collections.newSetFromMap(new WeakHashMap<>());

    private final Identifier effect;
    private final int slotCount;
    private final Map<Entity, HostSlots> active = new IdentityHashMap<>();

    public EntityLocatorAttachments(Identifier effect, int slotCount) {
        if (slotCount <= 0) {
            throw new IllegalArgumentException("slotCount must be positive: " + slotCount);
        }
        this.effect = Objects.requireNonNull(effect, "effect");
        this.slotCount = slotCount;
        synchronized (REGISTRY) {
            REGISTRY.add(this);
        }
    }

    /** Begins or validates an entity's attachment session for the current render frame. */
    public Session beginFrame(Entity host, Vec3 actorPosition, float uniformScale, long frameKey) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(actorPosition, "actorPosition");
        if (host.isRemoved()) {
            deactivate(host);
            return Session.INACTIVE;
        }
        HostSlots slots = active.get(host);
        if (slots == null) {
            slots = new HostSlots(this, host, slotCount);
            active.put(host, slots);
        }
        Vec3 movement = host.getDeltaMovement();
        double cmPerSecond = 100.0 * host.level().tickRateManager().tickrate();
        double vx = movement.x * cmPerSecond;
        double vy = movement.z * cmPerSecond;
        double vz = movement.y * cmPerSecond;
        Velocity actorVelocity = slots.velocity(vx, vy, vz);
        slots.session.update(actorPosition, actorVelocity, uniformScale, frameKey);
        return slots.session;
    }

    /** Releases all effect handles currently bound to {@code host}. */
    public void deactivate(Entity host) {
        HostSlots removed = active.remove(host);
        if (removed != null) {
            removed.close();
        }
    }

    /** Closes any active host slots whose entity was removed or no longer belongs to {@code activeLevel}. */
    public void tick(@Nullable ClientLevel activeLevel) {
        deactivateWhere(activeLevel, null);
    }

    /** Closes removed, level-mismatched, or caller-rejected host slots. */
    public void deactivateWhere(@Nullable ClientLevel activeLevel, @Nullable Predicate<Entity> shouldDeactivate) {
        var iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Entity host = entry.getKey();
            if (host.isRemoved() || activeLevel == null || host.level() != activeLevel
                    || (shouldDeactivate != null && shouldDeactivate.test(host))) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    /** Internal client tick sweep invoked once per tick by LumenFX. */
    public static void tickAll(@Nullable ClientLevel activeLevel) {
        EntityLocatorAttachments[] snapshot;
        synchronized (REGISTRY) {
            snapshot = REGISTRY.toArray(EntityLocatorAttachments[]::new);
        }
        for (EntityLocatorAttachments host : snapshot) {
            if (host != null) {
                host.tick(activeLevel);
            }
        }
    }

    private void sampleSlot(HostSlots slots, int slot, long frameKey, Entity host,
                            Vec3 worldPosition, Vec3 actorPosition, Velocity actorVelocity, float uniformScale) {
        if (slot < 0 || slot >= slotCount) {
            throw new IndexOutOfBoundsException("slot " + slot + " out of bounds [0, " + slotCount + ")");
        }
        if (active.get(host) != slots) {
            return;
        }
        if (!(host.level() instanceof ClientLevel level)) {
            throw new IllegalStateException("Locator attachments require a client level");
        }
        EffectHandle handle = slots.effects[slot];
        if (handle != null && handle.isAlive() && slots.sampledFrames[slot] == frameKey) {
            return;
        }
        if (handle == null || !handle.isAlive()) {
            long seed = host.getUUID().getLeastSignificantBits() ^ (slot + 1L) * SEED_MIX;
            handle = VfxEffects.spawn(level, effect, worldPosition, seed);
            handle.setNiagaraRandomSeedOffset(Long.hashCode(seed));
            handle.trackRenderSamples();
            slots.effects[slot] = handle;
        }
        handle.setPoseAndActorPosition(
                new EffectHandle.Pose(worldPosition, 0.0F, 0.0F, 0.0F, 1.0F, uniformScale),
                actorPosition,
                actorVelocity);
        slots.sampledFrames[slot] = frameKey;
    }

    public static final class Session {
        private static final Session INACTIVE = new Session(null, null, null, Vec3.ZERO, null, 1.0F, 0L);

        private final @Nullable EntityLocatorAttachments owner;
        private final @Nullable HostSlots slots;
        private final @Nullable Entity host;
        private Vec3 actorPosition;
        private @Nullable Velocity actorVelocity;
        private float uniformScale;
        private long frameKey;

        private Session(@Nullable EntityLocatorAttachments owner, @Nullable HostSlots slots, @Nullable Entity host,
                        Vec3 actorPosition, @Nullable Velocity actorVelocity, float uniformScale, long frameKey) {
            this.owner = owner;
            this.slots = slots;
            this.host = host;
            this.actorPosition = actorPosition;
            this.actorVelocity = actorVelocity;
            this.uniformScale = uniformScale;
            this.frameKey = frameKey;
        }

        public boolean isActive() {
            return owner != null && slots != null && host != null;
        }

        public long frameKey() {
            return frameKey;
        }

        private void update(Vec3 actorPosition, @Nullable Velocity actorVelocity, float uniformScale, long frameKey) {
            this.actorPosition = actorPosition;
            this.actorVelocity = actorVelocity;
            this.uniformScale = uniformScale;
            this.frameKey = frameKey;
        }

        /** Publishes an entity-local locator offset when {@code expectedFrame} matches the active session frame. */
        public void sampleLocalIfFrame(int slot, Vec3 localOffset, long expectedFrame) {
            if (frameKey == expectedFrame) {
                sampleLocal(slot, localOffset);
            }
        }

        /** Publishes an entity-local locator offset (such as a GeckoLib or KBR locator sample) for {@code slot}. */
        public void sampleLocal(int slot, Vec3 localOffset) {
            if (!isActive()) {
                return;
            }
            sampleWorld(slot, actorPosition.add(localOffset));
        }

        /** Publishes a world-space locator position for {@code slot}. */
        public void sampleWorld(int slot, Vec3 worldPosition) {
            if (!isActive()) {
                return;
            }
            owner.sampleSlot(slots, slot, frameKey, host, worldPosition, actorPosition, actorVelocity, uniformScale);
        }
    }

    private static final class HostSlots {
        private final @Nullable EffectHandle[] effects;
        private final long[] sampledFrames;
        private final Session session;
        private double lastVx = Double.NaN;
        private double lastVy = Double.NaN;
        private double lastVz = Double.NaN;
        private @Nullable Velocity lastVelocity;

        private HostSlots(EntityLocatorAttachments owner, Entity host, int count) {
            this.effects = new EffectHandle[count];
            this.sampledFrames = new long[count];
            this.session = new Session(owner, this, host, Vec3.ZERO, null, 1.0F, 0L);
        }

        private Velocity velocity(double vx, double vy, double vz) {
            Velocity cached = lastVelocity;
            if (cached != null
                    && Double.doubleToRawLongBits(vx) == Double.doubleToRawLongBits(lastVx)
                    && Double.doubleToRawLongBits(vy) == Double.doubleToRawLongBits(lastVy)
                    && Double.doubleToRawLongBits(vz) == Double.doubleToRawLongBits(lastVz)) {
                return cached;
            }
            Velocity next = new Velocity(vx, vy, vz);
            lastVx = vx;
            lastVy = vy;
            lastVz = vz;
            lastVelocity = next;
            return next;
        }

        private void close() {
            for (int i = 0; i < effects.length; i++) {
                if (effects[i] != null) {
                    effects[i].close();
                    effects[i] = null;
                }
            }
        }
    }
}
