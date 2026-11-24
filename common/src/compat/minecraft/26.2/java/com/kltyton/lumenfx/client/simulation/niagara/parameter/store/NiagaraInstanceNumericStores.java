package com.kltyton.lumenfx.client.simulation.niagara.parameter.store;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParameterValues;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.UserParameterNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NumericStoreDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.Reference;
import java.util.LinkedHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Collections;
import org.jspecify.annotations.Nullable;

/** Component overrides feed the instance store before its concurrent parameter Tick. */
public final class NiagaraInstanceNumericStores implements AutoCloseable {
    private final NumericStoreDefinition definition;
    private final NiagaraParameterStore instance, override;
    private final byte[] defaults, prepared;
    private final ByteBuffer writer;
    private final double[] @Nullable [] positions;
    private final boolean hasPositions;
    private final NiagaraDefinition.@Nullable InstanceStores full;
    private final @Nullable NiagaraParameterStoreSources sources;
    private final boolean ownsSources;
    private @Nullable Map<String, EffectHandle.ParameterValue> published;
    private @Nullable Map<String, TextureParameter> publishedTextures;
    private Map<Key, Reference> interfaceOverrides = Map.of();
    private Map<Key, Reference> objectOverrides = Map.of();
    private double originX, originY, originZ;

    public NiagaraInstanceNumericStores(NumericStoreDefinition definition) {
        this(definition, null, null);
    }
    public NiagaraInstanceNumericStores(NumericStoreDefinition legacy, NiagaraDefinition.@Nullable InstanceStores full,
                                        @Nullable NiagaraParameterStoreSources sources) {
        this.full = full;
        ownsSources = full != null && sources == null;
        this.sources = full == null ? null : sources == null ? new NiagaraParameterStoreSources(new LinkedHashMap<>()) : sources;
        NumericStoreDefinition definition = full == null ? legacy : new NumericStoreDefinition(new NiagaraParameterLayout(
                full.override().layout().variables().stream().filter(value -> value.key().storage() == NiagaraParameterLayout.Storage.NUMERIC).toList(),
                full.override().layout().numericBytes()), full.override().data(), full.override().numeric());
        this.definition = definition;
        instance = new NiagaraParameterStore(full == null ? definition.layout() : full.instance().layout());
        override = new NiagaraParameterStore(full == null ? definition.layout() : full.override().layout(), full != null);
        defaults = definition.data(); prepared = defaults.clone();
        writer = ByteBuffer.wrap(prepared).order(ByteOrder.LITTLE_ENDIAN);
        positions = new double[definition.values().size()][];
        boolean hasPositions = false;
        for (int index = 0; index < positions.length; index++) {
            var value = definition.values().get(index);
            positions[index] = value.position();
            hasPositions |= SourceNames.equal(value.key().sourceType(), "/Script/Niagara.NiagaraPosition");
        }
        this.hasPositions = hasPositions;
        reset();
    }

    public NiagaraParameterStore instance() { return instance; }
    public NiagaraParameterStore override() { return override; }

    public boolean hasObjectParameterOverride(String name) {
        for (Key key : objectOverrides.keySet()) {
            if (UserParameterNames.equal(key.name(), name)) return true;
        }
        return false;
    }

    public void unbindOverride() { override.unbind(instance); }
    public void applyDataInterfaces(Map<Key, Reference> values) {
        if (!values.isEmpty() && full == null) throw new IllegalArgumentException("Effect has no exposed DI declarations");
        if (full == null) return;
        var next = new LinkedHashMap<Key, Reference>();
        for (var entry : values.entrySet()) {
            Key key = entry.getKey();
            if (key.storage() != Storage.INTERFACE || !key.equals(entry.getValue().key()) || !override.contains(key))
                throw new IllegalArgumentException("Missing exposed data interface parameter: " + key.name());
            Key canonical = override.canonicalInterfaceKey(key);
            if (next.putIfAbsent(canonical, entry.getValue()) != null)
                throw new IllegalArgumentException("Duplicate exposed data interface parameter: " + canonical.name());
        }
        var updates = new LinkedHashMap<Key, @Nullable Object>();
        for (var entry : next.entrySet()) {
            if (entry.getValue() != interfaceOverrides.get(entry.getKey()))
                updates.put(entry.getKey(), sources.interfaceObject(entry.getKey(), entry.getValue().value()));
        }
        for (Key key : interfaceOverrides.keySet()) {
            if (!next.containsKey(key))
                updates.put(key, sources.defaultDataInterface(full.override(), key));
        }
        updates.forEach(override::setDataInterface);
        interfaceOverrides = Collections.unmodifiableMap(next);
        override.tick();
        instance.tick();
    }
    private void writeDataInterfaces() {
        interfaceOverrides.forEach((key, value) -> override.setDataInterface(key, sources.interfaceObject(key, value.value())));
        override.tick();
        instance.tick();
    }

    public void applyObjectParameters(Map<Key, Reference> values) {
        if (values == objectOverrides) return;
        if (!values.isEmpty() && full == null) throw new IllegalArgumentException("Effect has no exposed object declarations");
        for (var entry : values.entrySet()) {
            Key key = entry.getKey();
            if (key.storage() != Storage.OBJECT || !key.equals(entry.getValue().key()) || !override.contains(key))
                throw new IllegalArgumentException("Missing exposed object parameter: " + key.name());
        }
        if (full != null) {
            for (var variable : full.override().layout().variables()) {
                Key key = variable.key();
                if (key.storage() != Storage.OBJECT) continue;
                Reference value = object(values, key), previous = object(objectOverrides, key);
                if (value != null) override.setObject(key, sources.objectParameter(value.value()));
                else if (previous != null) {
                    TextureParameter texture = publishedTextures == null ? null : texture(publishedTextures, key.name());
                    override.setObject(key, texture == null ? sources.defaultObject(full.override(), key) : texture);
                }
            }
        }
        objectOverrides = values;
        override.tick();
        instance.tick();
    }

    public void prepare(Map<String, EffectHandle.ParameterValue> values, double x, double y, double z) {
        prepare(values, Map.of(), x, y, z);
    }
    public void prepare(Map<String, EffectHandle.ParameterValue> values, Map<String, TextureParameter> textures,
                        double x, double y, double z) {
        if (full != null && publishedTextures != textures) {
            for (var variable : full.override().layout().variables()) {
                if (variable.key().storage() != NiagaraParameterLayout.Storage.OBJECT) continue;
                if (object(objectOverrides, variable.key()) != null) continue;
                TextureParameter value = texture(textures, variable.key().name());
                TextureParameter previous = publishedTextures == null ? null : texture(publishedTextures, variable.key().name());
                if (value != null) override.setObject(variable.key(), value);
                else if (previous != null) override.setObject(variable.key(), sources.defaultObject(full.override(), variable.key()));
            }
            publishedTextures = textures;
        }
        if (published != values || hasPositions && (originX != x || originY != y || originZ != z)) {
            System.arraycopy(defaults, 0, prepared, 0, defaults.length);
            for (int index = 0; index < definition.values().size(); index++) {
                var variable = definition.values().get(index);
                double[] position = positions[index];
                if (position != null) {
                    writer.putFloat(variable.offset(), (float)(position[0] - x));
                    writer.putFloat(variable.offset() + 4, (float)(position[1] - y));
                    writer.putFloat(variable.offset() + 8, (float)(position[2] - z));
                }
                var value = values.get(variable.key().name());
                if (value == null) for (var entry : values.entrySet())
                    if (SourceNames.equal(entry.getKey(), variable.key().name()) || full != null && variable.key().name().length() > 5
                            && SourceNames.equal(variable.key().name().substring(0, 5), "User.")
                            && SourceNames.equal(entry.getKey(), variable.key().name().substring(5))) { value = entry.getValue(); break; }
                if (value != null)
                    NiagaraParameterValues.writeValue(variable.key().name(), variable.key().sourceType(), variable.bytes(), writer,
                            variable.offset(), value, x, y, z);
                override.setNumeric(variable.key(), prepared, variable.offset(), variable.bytes());
            }
            published = values; originX = x; originY = y; originZ = z;
        }
        override.tick();
        instance.tick();
        instance.markNumericDirty();
    }
    private static @Nullable TextureParameter texture(Map<String, TextureParameter> values, String name) {
        TextureParameter exact = values.get(name);
        if (exact != null) return exact;
        String simple = name.length() > 5 && SourceNames.equal(name.substring(0, 5), "User.") ? name.substring(5) : name;
        for (var entry : values.entrySet()) if (SourceNames.equal(entry.getKey(), name) || SourceNames.equal(entry.getKey(), simple)) return entry.getValue();
        return null;
    }

    private static @Nullable Reference object(Map<Key, Reference> values, Key key) {
        Reference exact = values.get(key);
        if (exact != null) return exact;
        for (var entry : values.entrySet()) {
            Key candidate = entry.getKey();
            if (UserParameterNames.equal(candidate.name(), key.name())
                    && SourceNames.equal(candidate.storeTypeName(), key.storeTypeName())) return entry.getValue();
        }
        return null;
    }

    public void reset() {
        if (full != null) {
            sources.initializeInstance(instance, full.instance());
            sources.initializeInstance(override, full.override());
            override.bindTo(instance);
            published = null;
            publishedTextures = null;
            writeDataInterfaces();
            var objects = objectOverrides;
            objectOverrides = Map.of();
            applyObjectParameters(objects);
            return;
        }
        instance.resetValues(); override.resetValues();
        for (var value : definition.values()) {
            instance.setNumeric(value.key(), defaults, value.offset(), value.bytes());
            override.setNumeric(value.key(), defaults, value.offset(), value.bytes());
        }
        override.bindTo(instance);
        published = null;
        publishedTextures = null;
    }

    @Override public void close() {
        override.close(); instance.close();
        if (ownsSources) sources.close();
        published = null;
        publishedTextures = null;
    }
}
