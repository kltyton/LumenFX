/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.kltyton.lumenfx.internal.photon.core.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.collision.BlockFilter;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.collider.ColliderObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What particles collide with and how they respond, beyond {@link PhysicsSetting}'s bounce and friction. */
@Getter
@Setter
public class CollisionSetting implements IPersistedSerializable {

    public enum Shape {
        /** the particle's own size, as a box */
        Box,
        /** the particle's centre only */
        Point
    }

    public enum EntityResponse {
        /** entities block particles like blocks do */
        Solid,
        /** particles pass through and only report entering and leaving */
        Trigger
    }

    public enum EntityCategory {
        All,
        Living,
        Players,
        NonLiving
    }

    public enum ColliderFilter {
        /** every active collider in the same FX scene */
        SceneAll,
        /** only the colliders referenced in the list */
        List
    }

    public enum Dedupe {
        /** every impact reports */
        Off,
        /** each particle reports each target once (per cooldown) */
        PerParticle,
        /** the emitter reports each target once (per cooldown), whichever of its particles hits it */
        PerEmitter,
        /** the emitters of one effect that share a group name report each target once between them */
        PerGroup
    }

    @Persisted
    protected boolean blocks = true;
    @Persisted
    protected boolean fluids = false;
    @Persisted
    protected boolean entities = false;
    @Persisted
    protected boolean colliders = true;
    @Persisted
    protected Shape shape = Shape.Box;
    @Persisted
    protected float radiusScale = 1;
    @Persisted
    protected boolean killOnTriggerEnter = false;

    @Persisted(subPersisted = true)
    public final Response response = new Response();
    @Persisted(subPersisted = true)
    public final Filters filters = new Filters();
    @Persisted(subPersisted = true)
    public final Events events = new Events();
    @Persisted(subPersisted = true)
    public final Advanced advanced = new Advanced();

    /** How speed and lifetime change on every impact. */
    @Getter
    @Setter
    public static class Response implements IPersistedSerializable {
        @Persisted
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "lifetime", yAxis = "dampen"))
        protected NumberFunction dampen = NumberFunction.constant(0);
        @Persisted
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "lifetime", yAxis = "loss"))
        protected NumberFunction lifetimeLoss = NumberFunction.constant(0);
        @Persisted
        protected float minKillSpeed = 0;
        @Persisted
        protected float maxKillSpeed = 0;
        @Persisted
        protected int maxBounces = 0;
        @Persisted
        protected int killAfterImpacts = 0;
        @Persisted
        protected boolean stick = false;
    }

    /** Which blocks, entities and colliders count. */
    @Getter
    @Setter
    public static class Filters implements IPersistedSerializable {
        @Persisted
        @ReadOnlyManaged(serializeMethod = "stringsSerialize", deserializeMethod = "stringsDeserialize")
        protected List<String> onlyBlocks = new ArrayList<>();
        @Persisted
        @ReadOnlyManaged(serializeMethod = "stringsSerialize", deserializeMethod = "stringsDeserialize")
        protected List<String> exceptBlocks = new ArrayList<>();
        @Persisted
        protected EntityResponse entityResponse = EntityResponse.Solid;
        @Persisted
        protected EntityCategory entityCategory = EntityCategory.Living;
        @Persisted
        @ReadOnlyManaged(serializeMethod = "stringsSerialize", deserializeMethod = "stringsDeserialize")
        protected List<String> onlyEntities = new ArrayList<>();
        @Persisted
        @ReadOnlyManaged(serializeMethod = "stringsSerialize", deserializeMethod = "stringsDeserialize")
        protected List<String> exceptEntities = new ArrayList<>();
        @Persisted
        protected boolean ignoreOwner = true;
        @Persisted
        protected float sourceExclusionRadius = 0;
        @Persisted
        protected boolean ignoreSpectators = true;
        @Persisted
        protected boolean pickableOnly = false;
        @Persisted
        protected ColliderFilter colliderFilter = ColliderFilter.SceneAll;
        @Persisted
        @ReadOnlyManaged(serializeMethod = "refsSerialize", deserializeMethod = "refsDeserialize")
        protected List<TransformRef> colliderList = new ArrayList<>();

        public EntityCategory getEntityCategory() {
            return entityCategory == null ? EntityCategory.Living : entityCategory;
        }

        /** Whether the given collider passes the collider filter (matched by transform id). */
        public boolean accepts(ColliderObject collider) {
            if (colliderFilter == ColliderFilter.SceneAll) return true;
            var id = collider.transform().id();
            for (var ref : colliderList) {
                if (id.equals(ref.getTransformId())) return true;
            }
            return false;
        }

        private ListTag stringsSerialize(List<String> values) {
            return IdLists.serialize(values);
        }

        private List<String> stringsDeserialize(ListTag tag) {
            return IdLists.deserialize(tag);
        }

        private ListTag refsSerialize(List<TransformRef> refs) {
            var list = new ListTag();
            for (var ref : refs) {
                list.add(StringTag.valueOf(ref.getTransformId() == null ? "" : ref.getTransformId().toString()));
            }
            return list;
        }

        private List<TransformRef> refsDeserialize(ListTag tag) {
            var refs = new ArrayList<TransformRef>();
            for (Tag element : tag) {
                var value = element.asString().orElse("");
                try {
                    refs.add(value.isEmpty() ? new TransformRef() : new TransformRef(UUID.fromString(value)));
                } catch (IllegalArgumentException e) {
                    refs.add(new TransformRef());
                }
            }
            return refs;
        }
    }

    /** Which impacts and contact deaths are reported, to sub-emitters and listeners alike; the physics stays the same. */
    @Getter
    @Setter
    public static class Events implements IPersistedSerializable {
        @Persisted
        protected float minImpactSpeed = 0;
        @Persisted
        protected Dedupe dedupe = Dedupe.Off;
        @Persisted
        protected int cooldown = 0;
        @Persisted
        protected String group = "";

        public Dedupe getDedupe() {
            return dedupe == null ? Dedupe.Off : dedupe;
        }

        public String getGroup() {
            return group == null ? "" : group;
        }
    }

    /** Solver limits; the defaults suit almost everything. */
    @Getter
    @Setter
    public static class Advanced implements IPersistedSerializable {
        @Persisted
        protected int maxContacts = 4;
        @Persisted
        protected boolean depenetrate = true;
        @Persisted
        protected float maxDepenetration = 0.5f;
    }

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Per-emitter runtime layer: timeline slots and the resolved filters. */
    public static class Runtime {
        private final CollisionSetting config;
        public final RuntimeValue<Boolean> blocks;
        public final RuntimeValue<Boolean> fluids;
        public final RuntimeValue<Boolean> entities;
        public final RuntimeValue<Boolean> colliders;
        public final RuntimeValue<NumberFunction> dampen;
        public final RuntimeValue<NumberFunction> lifetimeLoss;
        @Nullable
        private BlockFilter blockFilter;
        @Nullable
        private List<String> blockFilterOnly, blockFilterExcept;
        private int blockFilterTags = -1;
        /** Null: every type. */
        @Nullable
        private Set<EntityType<?>> onlyTypes;
        private Set<EntityType<?>> exceptTypes = Set.of();
        @Nullable
        private List<String> typesOnly, typesExcept;
        private int typesTags = -1;

        public Runtime(CollisionSetting config) {
            this.config = config;
            this.blocks = new RuntimeValue<>(() -> config.blocks);
            this.fluids = new RuntimeValue<>(() -> config.fluids);
            this.entities = new RuntimeValue<>(() -> config.entities);
            this.colliders = new RuntimeValue<>(() -> config.colliders);
            this.dampen = new RuntimeValue<>(() -> config.response.dampen);
            this.lifetimeLoss = new RuntimeValue<>(() -> config.response.lifetimeLoss);
        }

        public CollisionSetting config() {
            return config;
        }

        public float getDampen(IParticle particle) {
            return dampen.get().get(particle.getT(), () -> particle.getMemRandom("collision_dampen")).floatValue();
        }

        public float getLifetimeLoss(IParticle particle) {
            return lifetimeLoss.get().get(particle.getT(), () -> particle.getMemRandom("collision_lifetime_loss")).floatValue();
        }

        /** The resolved block filter, or null for all blocks. Game thread. */
        @Nullable
        public BlockFilter blockFilter() {
            var filters = config.filters;
            int tags = BlockFilter.tagGeneration();
            if (blockFilterTags != tags || !filters.onlyBlocks.equals(blockFilterOnly) || !filters.exceptBlocks.equals(blockFilterExcept)) {
                blockFilterOnly = new ArrayList<>(filters.onlyBlocks);
                blockFilterExcept = new ArrayList<>(filters.exceptBlocks);
                blockFilterTags = tags;
                blockFilter = BlockFilter.of(blockFilterOnly, blockFilterExcept);
            }
            return blockFilter;
        }

        /** Whether an entity passes the category and type filters (the owner is excluded by the caller). Game thread. */
        public boolean acceptsEntity(Entity entity) {
            var filters = config.filters;
            if (filters.ignoreSpectators && entity.isSpectator()) return false;
            if (filters.pickableOnly && !entity.isPickable()) return false;
            boolean category = switch (filters.getEntityCategory()) {
                case All -> true;
                case Living -> entity instanceof LivingEntity;
                case Players -> entity instanceof Player;
                case NonLiving -> !(entity instanceof LivingEntity);
            };
            if (!category) return false;
            int tags = BlockFilter.tagGeneration();
            if (typesTags != tags || !filters.onlyEntities.equals(typesOnly) || !filters.exceptEntities.equals(typesExcept)) {
                typesOnly = new ArrayList<>(filters.onlyEntities);
                typesExcept = new ArrayList<>(filters.exceptEntities);
                typesTags = tags;
                onlyTypes = BlockFilter.hasEntries(typesOnly) ? resolveEntityTypes(typesOnly) : null;
                exceptTypes = resolveEntityTypes(typesExcept);
            }
            var type = entity.getType();
            return (onlyTypes == null || onlyTypes.contains(type)) && !exceptTypes.contains(type);
        }

        private static Set<EntityType<?>> resolveEntityTypes(List<String> entries) {
            var types = new HashSet<EntityType<?>>();
            for (var entry : entries) {
                if (entry == null) continue;
                var text = entry.trim();
                if (text.isEmpty()) continue;
                if (text.startsWith("#")) {
                    var id = Identifier.tryParse(text.substring(1));
                    if (id == null) continue;
                    BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(TagKey.create(Registries.ENTITY_TYPE, id))
                            .forEach(holder -> types.add(holder.value()));
                } else {
                    var id = Identifier.tryParse(text);
                    if (id != null) BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(types::add);
                }
            }
            return types;
        }

        public void clear() {
            blocks.clear();
            fluids.clear();
            entities.clear();
            colliders.clear();
            dampen.clear();
            lifetimeLoss.clear();
        }
    }
}
