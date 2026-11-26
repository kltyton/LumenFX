/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;
import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.collision.BlockFilter;
import com.lowdragmc.photon.client.collision.CollisionEvent;
import com.lowdragmc.photon.client.collision.Contact;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.fx.VanillaParticleHost;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author KilaBash
 * @date 2023/7/17
 * @implNote SubEmittersSetting
 */
@Setter
@Getter
public class SubEmittersSetting extends ToggleGroup {

    @Persisted
    @ReadOnlyManaged(serializeMethod = "emittersSerialize", deserializeMethod = "emittersDeserialize")
    protected List<Emitter> emitters = new ArrayList<>();

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final SubEmittersSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<List<Emitter>> emitters; // slot only (list → no timeline binding)

        public Runtime(SubEmittersSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.emitters = new RuntimeValue<>(config::getEmitters);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public void triggerEvent(TileParticle father, Event event) {
            triggerEvent(father, event, null);
        }

        /** {@code contact} is the collision behind the event, if any: emitters can spawn at its point. */
        public void triggerEvent(TileParticle father, Event event, @Nullable CollisionEvent contact) {
            for (Emitter candidate : emitters.get()) {
                if (candidate.event == event && candidate.accepts(contact)) {
                    candidate.spawnEmitter(father, contact);
                }
            }
        }

        /**
         * Tick event with integer-tick-crossing semantics: fires when this step crossed a whole tick
         * and that tick matches the candidate's tickInterval. Robust under fractional dt (sub-stepped
         * timeline speed), where the particle's float age almost never lands exactly on an integer.
         */
        public void triggerTickEvent(TileParticle father, float dt) {
            var newTick = Mth.floor(father.getAge());
            if (newTick <= Mth.floor(father.getAge() - dt)) {
                return; // no whole tick crossed this step (dt <= 1 => at most one crossing)
            }
            for (Emitter candidate : emitters.get()) {
                if (candidate.event == Event.Tick && newTick % candidate.tickInterval == 0) {
                    candidate.spawnEmitter(father);
                }
            }
        }

        public void clear() {
            enable.clear();
            emitters.clear();
        }
    }

    private IntTag emittersSerialize(List<Emitter> bursts) {
        return IntTag.valueOf(bursts.size());
    }

    private List<Emitter> emittersDeserialize(IntTag tag) {
        var groups = new ArrayList<Emitter>();
        for (int i = 0; i < tag.intValue(); i++) {
            groups.add(new Emitter());
        }
        return groups;
    }

    public enum Event {
        Birth,
        Death,
        Collision,
        FirstCollision,
        Tick,
        TriggerEnter,
        TriggerExit;

        /** Whether a contact can be behind it, which is what {@link Surface} filters. */
        public boolean fromContact() {
            return this != Birth && this != Tick;
        }
    }

    public enum Surface {
        Any,
        Blocks,
        Fluids,
        Entities,
        Colliders
    }

    @Setter
    @Getter
    public static class Emitter implements IPersistedSerializable {
        @Nullable
        @Persisted
        protected Identifier fxLocation = null;
        @Persisted
        protected Event event = Event.Birth;
        @Persisted
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "probability", yAxis = "lifetime"))
        protected NumberFunction emitProbability = NumberFunction.constant(0);
        @Persisted
        protected int tickInterval = 1;
        @Persisted
        protected boolean inheritColor = false;
        @Persisted
        protected boolean inheritSize = false;
        @Persisted
        protected boolean inheritRotation = false;
        @Persisted
        protected boolean inheritLifetime = false;
        @Persisted
        protected boolean inheritDuration = false;
        /** False for entries saved before the option existed; new entries start with it on. */
        @Persisted
        protected boolean spawnAtContact = false;
        @Persisted
        protected boolean alignToNormal = false;
        @Persisted
        protected Surface surface = Surface.Any;
        @Persisted
        @ReadOnlyManaged(serializeMethod = "stringsSerialize", deserializeMethod = "stringsDeserialize")
        protected List<String> surfaceBlocks = new ArrayList<>();
        /** The resolved {@link #surfaceBlocks}; read from particle workers, so replaced whole, never changed. */
        @Nullable
        private volatile SurfaceFilter surfaceFilter;

        private record SurfaceFilter(List<String> entries, int tags, @Nullable BlockFilter filter) {
        }

        /** Whether the contact behind an event passes {@link #surface}; events without one pass only {@link Surface#Any}. Any thread. */
        public boolean accepts(@Nullable CollisionEvent contact) {
            var surface = getSurface();
            if (surface == Surface.Any || event == null || !event.fromContact()) return true;
            if (contact == null) return false;
            byte kind = switch (surface) {
                case Blocks -> Contact.BLOCK;
                case Fluids -> Contact.FLUID;
                case Entities -> Contact.ENTITY;
                default -> Contact.COLLIDER;
            };
            if (contact.kind() != kind) return false;
            if (surface != Surface.Blocks && surface != Surface.Fluids) return true;
            var filter = blockFilter();
            return filter == null || (contact.target() instanceof BlockState state && filter.test(state));
        }

        public Surface getSurface() {
            return surface == null ? Surface.Any : surface;
        }

        @Nullable
        private BlockFilter blockFilter() {
            var cached = surfaceFilter;
            int tags = BlockFilter.tagGeneration();
            if (cached == null || cached.tags != tags || !cached.entries.equals(surfaceBlocks)) {
                var entries = List.copyOf(surfaceBlocks.stream().map(entry -> entry == null ? "" : entry).toList());
                cached = new SurfaceFilter(entries, tags, BlockFilter.of(entries, List.of()));
                surfaceFilter = cached;
            }
            return cached.filter;
        }

        private ListTag stringsSerialize(List<String> values) {
            return IdLists.serialize(values);
        }

        private List<String> stringsDeserialize(ListTag tag) {
            return IdLists.deserialize(tag);
        }

        public void spawnEmitter(TileParticle father) {
            spawnEmitter(father, null);
        }

        public void spawnEmitter(TileParticle father, @Nullable CollisionEvent contact) {
            if (fxLocation == null) {
                return;
            }
            // probability roll stays here: the particle's RandomSource is confined to its update thread
            boolean spawns = father.getRandomSource().nextFloat() < emitProbability.get(father.getT(0),
                    () -> father.getMemRandom("sub_emitter_probability")).floatValue();
            // FXRuntime.emit (particle engine) is not thread-safe: particle updates
            // may run on parallelStream workers, so defer the spawn to the game thread — the owning
            // emitter drains it at the end of the same tick (deferred in serial mode too, so ordering
            // is mode-independent; a dead particle's fields stay valid until next tick's removal).
            if (father.getEmitter() instanceof ParticleEmitter particleEmitter) {
                // a de-duplicated contact counts as reported whether or not this roll spawns
                boolean dedupes = contact != null && particleEmitter.dedupes(contact);
                if (!spawns && !dedupes) return;
                particleEmitter.scheduleSubEmitterSpawn(father.nextSpawnOrder(), () -> {
                    if (dedupes && !particleEmitter.passesDedupe(father, contact)) return;
                    if (spawns) doSpawn(father, contact);
                });
            } else if (spawns) {
                doSpawn(father, contact); // non-ParticleEmitter owners never parallel-update TileParticles
            }
        }

        private void doSpawn(TileParticle father, @Nullable CollisionEvent contact) {
            var id = fxLocation;
            var executor = father.getEmitter().getEffectExecutor();
            if (id == null || executor == null || !executor.acceptsSubEffects()) return;
            var fx = FXHelper.getCachedFX(id);
            if (fx == null && !FXHelper.contains(id)) return;

            var position = contact != null && spawnAtContact ? contact.position() : father.getWorldPos();
            Quaternionf rotation = null;
            if (contact != null && alignToNormal) {
                var normal = contact.normal();
                // trigger exits have no surface, so no normal
                if (normal.lengthSquared() > 0.5f) {
                    rotation = new Quaternionf().rotationTo(0, 1, 0, normal.x, normal.y, normal.z);
                }
            }
            var spawn = new Spawn(father.getEmitter().worldOrigin(), new Vector3f(position), rotation,
                    inheritLifetime ? (int) father.getAge() : null,
                    inheritDuration ? father.getLifetime() : null,
                    inheritColor ? new Vector4f(father.getInheritedColor(0)) : null,
                    inheritSize ? new Vector3f(father.getRealSize(0)) : null,
                    inheritRotation ? new Vector3f(father.getRealRotation(0)) : null);
            var child = new ChildEffectExecutor(executor, RandomSource.create(executor.getRandomSource().nextLong()));
            if (fx != null) {
                spawn.emit(fx, child);
                return;
            }

            // Author dependencies are normally warm. A newly selected dependency needs I/O,
            // while the emitting particle and its contact are only valid for this simulation step.
            var minecraft = Minecraft.getInstance();
            var level = executor.getLevel();
            int resources = FXHelper.generation();
            int world = VanillaParticleHost.INSTANCE.generation();
            var load = FXHelper.preloadForPlayback(id, resources).thenAcceptAsync(prepared -> {
                if (minecraft.level != level || resources != FXHelper.generation()
                        || world != VanillaParticleHost.INSTANCE.generation() || !child.acceptsSubEffects()) return;
                if (prepared != null && FXHelper.getCachedFX(id) == prepared) {
                    spawn.emit(prepared, child);
                }
            }, minecraft).whenComplete((ignored, error) -> {
                if (error != null) Photon.LOGGER.error("Could not start sub-emitter {}", id, error);
            });
            child.trackSubEffectLoad(load);
        }

        private record Spawn(net.minecraft.world.phys.Vec3 worldOrigin, Vector3f position, @Nullable Quaternionf rotation,
                             @Nullable Integer age, @Nullable Integer duration, @Nullable Vector4f color,
                             @Nullable Vector3f size, @Nullable Vector3f euler) {
            void emit(FX fx, IEffectExecutor executor) {
                var runtime = fx.createRuntime();
                runtime.setWorldOrigin(worldOrigin);
                runtime.root.updatePos(position);
                if (rotation != null) runtime.root.updateRotation(rotation);
                try {
                    runtime.emit(executor, value -> {
                        if (value instanceof IParticleEmitter emitter) {
                            if (duration != null) emitter.self().inheritDuration(duration);
                            if (age != null) emitter.setAge(age);
                            if (color != null) emitter.setRGBAColor(color);
                            if (size != null) emitter.transform().scale(size);
                            if (euler != null) emitter.transform().rotation(new Quaternionf().rotationXYZ(euler.x, euler.y, euler.z));
                        }
                    });
                    executor.trackSubEffect(runtime);
                } catch (RuntimeException | Error failure) {
                    try {
                        runtime.destroy(true);
                    } catch (RuntimeException | Error cleanupError) {
                        if (cleanupError != failure) failure.addSuppressed(cleanupError);
                    }
                    throw failure;
                }
            }
        }

        private record ChildEffectExecutor(IEffectExecutor parent, RandomSource random) implements IEffectExecutor {
            @Override public Level getLevel() { return parent.getLevel(); }
            @Override public RandomSource getRandomSource() { return random; }
            @Override public @Nullable Entity owner() { return parent.owner(); }
            @Override public void updateFXObjectTick(IFXObject object) { parent.updateFXObjectTick(object); }
            @Override public void updateFXObjectFrame(IFXObject object, float partialTicks) {
                parent.updateFXObjectFrame(object, partialTicks);
            }
            @Override public boolean canEmit(IFXObject object) { return parent.canEmit(object); }
            @Override public boolean acceptsSubEffects() { return parent.acceptsSubEffects(); }
            @Override public void trackSubEffectLoad(CompletableFuture<?> load) { parent.trackSubEffectLoad(load); }
            @Override public void trackSubEffect(FXRuntime runtime) { parent.trackSubEffect(runtime); }
            @Override public void onTimelineSignal(String channel, String name, CompoundTag data, double time) {
                parent.onTimelineSignal(channel, name, data, time);
            }
        }

    }
}
