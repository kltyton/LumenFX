package com.kltyton.lumenfx.client.simulation.niagara.gpu;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParameterValues;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataSetStoreBinding;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Raw execution values remain independent of GPU uniform encoding and renderer propagation. */
final class NiagaraGpuCombinedParameters implements AutoCloseable {
    private final ScriptParameterStoreDefinition definition;
    private final NiagaraParameterStore store;
    private @Nullable NiagaraDataSetStoreBinding dataset;
    private final byte[] defaults;
    private final NiagaraParameterStoreSources sources;
    private @Nullable NiagaraParameterStore instance;
    private long instanceRevision;
    private @Nullable Map<String, EffectHandle.ParameterValue> previousOverrides;
    private double originX, originY, originZ;

    NiagaraGpuCombinedParameters(ScriptParameterStoreDefinition definition, @Nullable List<NiagaraDefinition.Variable> layout,
                                 NiagaraParameterStoreSources sources) {
        this.definition = definition;
        this.sources = sources;
        store = new NiagaraParameterStore(definition.layout());
        dataset = layout == null ? null : new NiagaraDataSetStoreBinding(store, layout);
        sources.initializeInstance(store, definition);
        defaults = store.numericData();
    }

    NiagaraParameterStore store() { return store; }
    void bindInstance(NiagaraParameterStore instance) {
        this.instance = instance;
        instance.bindTo(store);
        sources.bind(definition.instanceSources(), store);
        resolveUserInterfaces();
    }
    void tickRapidIteration() { sources.tick(definition.instanceSources()); }

    void prepare(List<NiagaraGpuParameterValues.Binding> bindings, Map<String, EffectHandle.ParameterValue> overrides,
                 NiagaraCollectionSnapshot collections, double x, double y, double z) {
        boolean changed = previousOverrides != overrides;
        boolean shifted = originX != x || originY != y || originZ != z;
        for (var binding : bindings) {
            Key key = binding.key();
            if (key == null) continue;
            var value = find(overrides, binding.name());
            var old = previousOverrides == null ? null : find(previousOverrides, binding.name());
            if (changed && (value != null || old != null) || shifted && value != null && value.type() == EffectHandle.ParameterType.POSITION) {
                byte[] raw = defaultValue(key);
                if (value != null) NiagaraParameterValues.writeValue(binding.name(), binding.sourceType(), raw.length,
                        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN), 0, value, x, y, z);
                store.setNumeric(key, raw, 0, raw.length);
            }
            var collection = binding.collection();
            if (collection == null) continue;
            boolean component = collections.component().contains(collection.path());
            byte[] raw = read(key);
            collection.copyDefault(raw, 0, component, x, y, z);
            var replacement = component ? collections.component().get(collection.path(), binding.name(), binding.sourceType())
                    : collection.systemOverride() ? null : collections.world().get(collection.path(), binding.name(), binding.sourceType());
            if (replacement != null) NiagaraParameterValues.writeValue(binding.name(), binding.sourceType(), raw.length,
                    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN), 0, replacement, x, y, z);
            store.setNumeric(key, raw, 0, raw.length);
        }
        previousOverrides = overrides; originX = x; originY = y; originZ = z;
    }

    void transfer(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, boolean contextTick) {
        refreshUserInterfaces(false);
        if (dataset == null) dataset = new NiagaraDataSetStoreBinding(store, List.copyOf(variables.values()));
        dataset.toStore(system, 0);
        // ComputeExecutionContext::Tick propagates Combined only in its interfaces-dirty branch.
        if (contextTick && store.interfacesDirty()) store.tick();
    }

    private void resolveUserInterfaces() {
        NiagaraParameterStoreSources.resolveUserInterfaces(definition, instance, store);
        instanceRevision = instance.interfacesRevision();
    }
    void refreshUserInterfaces(boolean rebind) {
        if (instance != null && (rebind || instanceRevision != instance.interfacesRevision())) resolveUserInterfaces();
        if (rebind) store.markInterfacesDirty();
    }

    byte[] read(Key key) {
        int bytes = store.numericBytes(key);
        if (bytes < 0) throw new IllegalArgumentException("GPU execution store has no numeric declaration: " + key.name());
        byte[] result = new byte[bytes];
        store.copyNumeric(key, result, 0);
        return result;
    }

    byte[] readPrevious(Key key) {
        if (!definition.interpolated()) return read(key);
        return read(new Key("PREV_" + key.name(), key.sourceType(), key.underlyingType(), key.flags(), Storage.NUMERIC));
    }

    void commit() {
        if (definition.interpolated()) store.copyNumericRange(0, definition.currentBytes(), definition.currentBytes());
    }

    void reset() {
        sources.unbind(definition.instanceSources(), store);
        if (instance != null) instance.unbind(store);
        sources.initializeInstance(store, definition);
        if (instance != null) bindInstance(instance);
        previousOverrides = null;
    }

    private byte[] defaultValue(Key key) {
        int offset = store.numericOffset(key), bytes = store.numericBytes(key);
        byte[] result = new byte[bytes];
        System.arraycopy(defaults, offset, result, 0, bytes);
        return result;
    }

    private static EffectHandle.@Nullable ParameterValue find(Map<String, EffectHandle.ParameterValue> values, String name) {
        var exact = values.get(name);
        if (exact != null) return exact;
        for (var entry : values.entrySet()) if (SourceNames.equal(entry.getKey(), name)) return entry.getValue();
        return null;
    }

    @Override public void close() { sources.releaseInstance(store); store.close(); previousOverrides = null; }
}
