package com.kltyton.lumenfx.client.simulation.niagara.parameter;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.ParameterCollectionValues;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import com.kltyton.lumenfx.client.material.collection.MaterialCollectionInstance;
import com.kltyton.lumenfx.client.material.collection.MaterialCollectionWorlds;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.logging.LogUtils;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** World NPC instances consume MPC parameter events independently of uniform-buffer revisions. */
public final class NiagaraCollectionWorlds {
    public static final class Catalog {
        private final Map<String, Map<ParameterCollectionValues.Parameter, NiagaraCollectionBinding>> bindings;
        private Catalog(Map<String, Map<ParameterCollectionValues.Parameter, NiagaraCollectionBinding>> bindings) { this.bindings = bindings; }
    }
    private static final class Driver {
        NiagaraCollectionBinding binding;
        final String source, parameter;
        MaterialCollectionDefinition definition;
        long revision, definitionRevision;
        Driver(NiagaraCollectionBinding binding, String source, String parameter, MaterialCollectionInstance.Snapshot snapshot) {
            this.binding = binding;
            this.source = source; this.parameter = parameter; definition = snapshot.definition();
            revision = snapshot.revision(); definitionRevision = snapshot.definitionRevision();
        }
    }
    private static final class World {
        ParameterCollectionValues values = ParameterCollectionValues.EMPTY;
        double lastTime = Double.NaN;
        final Set<String> initialized = new TreeSet<>(SourceNames.ASCII_CASE_INSENSITIVE);
        final Map<String, Map<ParameterCollectionValues.Parameter, Driver>> drivers = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
    }
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> LOGGED_CONFLICTS = new HashSet<>();
    private static final Map<ClientLevel, World> WORLDS = new WeakHashMap<>();
    private static Catalog catalog = new Catalog(Map.of());
    private NiagaraCollectionWorlds() { }

    public static Catalog prepare(Iterable<NiagaraCollectionBinding> definitions) {
        Map<String, Map<ParameterCollectionValues.Parameter, NiagaraCollectionBinding>> result = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        for (NiagaraCollectionBinding binding : definitions) {
            var values = result.computeIfAbsent(binding.path(), ignored -> new TreeMap<>(ParameterCollectionValues.parameterOrder()));
            var key = new ParameterCollectionValues.Parameter(binding.name(), binding.sourceType());
            NiagaraCollectionBinding previous = values.get(key);
            if (previous != null && (previous.baseDefaults() != null && binding.baseDefaults() != null
                    && (!Arrays.equals(previous.baseDefaults(), binding.baseDefaults()) || !Arrays.equals(previous.basePosition(), binding.basePosition()))
                    || !Objects.equals(previous.materialSource(), binding.materialSource())
                    || !Objects.equals(previous.materialParameter(), binding.materialParameter())))
                throw new IllegalArgumentException("Conflicting NPC source definition: " + binding.path() + " " + binding.name());
            if (previous == null || previous.baseDefaults() == null) values.put(key, binding);
        }
        Map<String, Map<ParameterCollectionValues.Parameter, NiagaraCollectionBinding>> immutable = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        result.forEach((path, values) -> immutable.put(path, Collections.unmodifiableMap(values)));
        return new Catalog(Collections.unmodifiableMap(immutable));
    }
    public static synchronized void publish(Catalog value) { catalog = Objects.requireNonNull(value); }

    public static synchronized void set(ClientLevel level, String collection, String name, String type, EffectHandle.ParameterValue value) {
        World world = WORLDS.computeIfAbsent(Objects.requireNonNull(level), ignored -> new World());
        initialize(level, world, collection, null);
        if (conflict(collection, name)) {
            if (LOGGED_CONFLICTS.add(type)) LOGGER.warn("NPC parameter '{}' is driven by its source MPC; this setter leaves its value unchanged", name);
            return;
        }
        world.values = world.values.with(collection, name, type, value);
    }
    public static synchronized void setTexture(ClientLevel level, String collection, String name, String type, @Nullable TextureParameter value) {
        World world = WORLDS.computeIfAbsent(Objects.requireNonNull(level), ignored -> new World());
        initialize(level, world, collection, null);
        world.values = world.values.withTexture(collection, name, type, value);
    }
    public static synchronized void setObject(ClientLevel level, String collection, String name, String type, @Nullable ObjectValue value) {
        World world = WORLDS.computeIfAbsent(Objects.requireNonNull(level), ignored -> new World());
        initialize(level, world, collection, null);
        world.values = world.values.withObject(collection, name, type, value);
    }
    public static synchronized void remove(ClientLevel level, String collection, String name, String type) {
        World world = WORLDS.get(Objects.requireNonNull(level));
        if (world != null) world.values = world.values.withoutParameter(collection, name, type);
    }
    public static synchronized void reset(ClientLevel level, String collection) {
        World world = WORLDS.get(Objects.requireNonNull(level));
        if (world != null) {
            world.values = world.values.withoutCollection(collection);
            world.initialized.remove(collection); world.drivers.remove(collection);
        }
    }
    public static synchronized ParameterCollectionValues snapshot(@Nullable ClientLevel level) {
        World world = level == null ? null : WORLDS.get(level);
        return world == null ? ParameterCollectionValues.EMPTY : world.values;
    }
    public static synchronized ParameterCollectionValues prepareFrame(@Nullable ClientLevel level, Iterable<NiagaraCollectionBinding> bindings,
            ParameterCollectionValues component, Map<String, MaterialCollectionInstance.Snapshot> materials) {
        if (level == null) return ParameterCollectionValues.EMPTY;
        World world = WORLDS.computeIfAbsent(level, ignored -> new World());
        for (NiagaraCollectionBinding binding : bindings) {
            if (binding.systemOverride() || component.contains(binding.path())) continue;
            initialize(level, world, binding.path(), materials);
            update(level, world, binding, materials, false);
        }
        return world.values;
    }
    public static synchronized void advance(ClientLevel level, double time) {
        World world = WORLDS.get(level);
        if (world == null || !Double.isNaN(world.lastTime) && time <= world.lastTime) return;
        var active = new ArrayList<NiagaraCollectionBinding>();
        for (String path : world.initialized) {
            var definitions = catalog.bindings.get(path);
            if (definitions != null) active.addAll(definitions.values());
            else {
                var retained = world.drivers.get(path);
                if (retained != null) retained.values().forEach(driver -> active.add(driver.binding));
            }
        }
        Map<String, MaterialCollectionDefinition> definitions = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        for (NiagaraCollectionBinding binding : active) if (binding.materialSource() != null)
            definitions.put(binding.materialSource().path(), binding.materialSource());
        var materials = MaterialCollectionWorlds.snapshot(level, definitions.values());
        for (NiagaraCollectionBinding binding : active) update(level, world, binding, materials, true);
        world.lastTime = time;
    }
    public static synchronized void clearWorld(ClientLevel level) { WORLDS.remove(level); }
    public static synchronized void clearWorlds() { WORLDS.clear(); }

    private static void initialize(ClientLevel level, World world, String collection,
                                   @Nullable Map<String, MaterialCollectionInstance.Snapshot> materials) {
        if (!world.initialized.add(collection)) return;
        var definitions = catalog.bindings.get(collection);
        if (definitions != null) for (NiagaraCollectionBinding binding : definitions.values()) update(level, world, binding, materials, false);
    }
    private static boolean conflict(String collection, String name) {
        var definitions = catalog.bindings.get(collection);
        if (definitions != null) for (NiagaraCollectionBinding binding : definitions.values()) {
            MaterialCollectionDefinition source = binding.materialSource();
            if (source != null && source.id(name) != null) return true;
        }
        return false;
    }
    private static void update(ClientLevel level, World world, NiagaraCollectionBinding binding,
                               @Nullable Map<String, MaterialCollectionInstance.Snapshot> materials, boolean advance) {
        MaterialCollectionDefinition source = binding.materialSource();
        String parameter = binding.materialParameter();
        if (source == null || parameter == null) return;
        MaterialCollectionInstance.Snapshot snapshot = materials == null ? MaterialCollectionWorlds.instance(level, source).snapshot() : materials.get(source.path());
        if (snapshot == null) throw new IllegalArgumentException("MPC was not captured for this frame: " + source.path());
        var drivers = world.drivers.computeIfAbsent(binding.path(), ignored -> new TreeMap<>(ParameterCollectionValues.parameterOrder()));
        var key = new ParameterCollectionValues.Parameter(binding.name(), binding.sourceType());
        Driver driver = drivers.get(key);
        boolean initial = driver == null || !SourceNames.equal(driver.source, source.path()) || !SourceNames.equal(driver.parameter, parameter);
        if (initial) {
            applyCurrent(world, binding, snapshot, parameter);
            drivers.put(key, new Driver(binding, source.path(), parameter, snapshot));
            return;
        }
        driver.binding = binding;
        if (!advance) return;
        if (!driver.definition.equals(snapshot.definition()) || driver.definitionRevision != snapshot.definitionRevision()) {
            applyCurrent(world, binding, snapshot, parameter);
            driver.definition = snapshot.definition(); driver.definitionRevision = snapshot.definitionRevision();
            // Native editor refresh uses an else-if: queued parameter events remain for the following world tick.
            return;
        }
        if (binding.sourceType().equals("/Script/Niagara.NiagaraFloat")) {
            MaterialCollectionInstance.ScalarValue changed = snapshot.scalarUpdates().get(parameter);
            if (changed != null && changed.revision() > driver.revision) apply(world, binding, new int[] { changed.bits() });
        } else {
            MaterialCollectionInstance.VectorValue changed = snapshot.vectorUpdates().get(parameter);
            if (changed != null && changed.revision() > driver.revision) apply(world, binding, vector(changed.value()));
        }
        driver.definition = snapshot.definition(); driver.definitionRevision = snapshot.definitionRevision(); driver.revision = snapshot.revision();
    }
    private static void applyCurrent(World world, NiagaraCollectionBinding binding, MaterialCollectionInstance.Snapshot snapshot, String parameter) {
        if (binding.sourceType().equals("/Script/Niagara.NiagaraFloat")) {
            Integer value = snapshot.scalar(parameter);
            if (value != null) apply(world, binding, new int[] { value });
        } else {
            MaterialCollectionDefinition.Vector value = snapshot.vector(parameter);
            if (value != null) apply(world, binding, vector(value));
        }
    }
    private static int[] vector(MaterialCollectionDefinition.Vector value) { return new int[] { value.x(), value.y(), value.z(), value.w() }; }
    private static void apply(World world, NiagaraCollectionBinding binding, int[] words) {
        long[] values = new long[words.length];
        for (int index = 0; index < words.length; index++) values[index] = words[index];
        EffectHandle.ParameterType type = words.length == 1 ? EffectHandle.ParameterType.FLOAT : EffectHandle.ParameterType.FLOAT4;
        world.values = world.values.with(binding.path(), binding.name(), binding.sourceType(), new EffectHandle.ParameterValue(type, values));
    }
}
