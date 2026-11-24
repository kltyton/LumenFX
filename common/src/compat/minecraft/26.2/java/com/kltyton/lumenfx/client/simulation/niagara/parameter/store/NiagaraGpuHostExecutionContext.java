package com.kltyton.lumenfx.client.simulation.niagara.parameter.store;

import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParameterValues;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.HostExecutionContext;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** GPU-mode host contexts Tick parameter stores and interpolation history without executing a CPU VM. */
public final class NiagaraGpuHostExecutionContext implements AutoCloseable {
    private record CollectionInput(Key key, NiagaraCollectionBinding source, byte[] bytes, ByteBuffer writer) { }
    private final HostExecutionContext definition;
    private final NiagaraParameterStoreSources sources;
    private final NiagaraParameterStore store;
    private final List<CollectionInput> collections;
    private @Nullable NiagaraParameterStore instance;
    private long instanceRevision;
    private @Nullable NiagaraDataSetStoreBinding dataset;

    public NiagaraGpuHostExecutionContext(HostExecutionContext definition, NiagaraParameterStoreSources sources,
                                          @Nullable List<NiagaraDefinition.Variable> layout) {
        this.definition = definition;
        this.sources = sources;
        store = new NiagaraParameterStore(definition.parameters().layout());
        sources.initializeInstance(store, definition.parameters());
        dataset = layout == null ? null : new NiagaraDataSetStoreBinding(store, layout);
        var inputs = new ArrayList<CollectionInput>();
        for (var source : definition.collections()) {
            Key key = new Key(source.name(), source.sourceType(), 2, 0, Storage.NUMERIC);
            if (store.numericBytes(key) != source.bytes())
                throw new IllegalArgumentException("GPU host collection differs from its parameter store: " + source.name());
            byte[] bytes = new byte[source.bytes()];
            inputs.add(new CollectionInput(key, source, bytes, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)));
        }
        collections = List.copyOf(inputs);
    }

    public NiagaraParameterStore store() { return store; }
    public String usage() { return definition.usage(); }

    public void bindInstance(NiagaraParameterStore instance) {
        this.instance = instance;
        instance.bindTo(store);
        sources.bind(definition.parameters().initialSources(), store);
        NiagaraParameterStoreSources.resolveUserInterfaces(definition.parameters(), instance, store);
        instanceRevision = instance.interfacesRevision();
    }

    public void refreshUserInterfaces(boolean rebind) {
        if (instance != null && (rebind || instanceRevision != instance.interfacesRevision())) {
            NiagaraParameterStoreSources.resolveUserInterfaces(definition.parameters(), instance, store);
            instanceRevision = instance.interfacesRevision();
        }
        if (rebind) store.markInterfacesDirty();
    }

    public void tickRapidIteration() { sources.tickAll(definition.parameters().initialSources()); }

    public void prepareCollections(NiagaraCollectionSnapshot values, double x, double y, double z) {
        for (var binding : collections) {
            var source = binding.source();
            boolean component = values.component().contains(source.path());
            store.copyNumeric(binding.key(), binding.bytes(), 0);
            source.copyDefault(binding.bytes(), 0, component, x, y, z);
            var replacement = component ? values.component().get(source.path(), source.name(), source.sourceType())
                    : source.systemOverride() ? null : values.world().get(source.path(), source.name(), source.sourceType());
            if (replacement != null) NiagaraParameterValues.writeValue(source.name(), source.sourceType(), source.bytes(),
                    binding.writer(), 0, replacement, x, y, z);
            store.setNumeric(binding.key(), binding.bytes(), 0, binding.bytes().length);
        }
    }

    public void transferDataSet(VectorDataSet data, List<NiagaraDefinition.Variable> layout) {
        if (dataset == null) dataset = new NiagaraDataSetStoreBinding(store, layout);
        dataset.toStore(data, 0);
    }

    public void tick() { refreshUserInterfaces(false); store.tick(); }

    public void postTick() {
        var parameters = definition.parameters();
        if (parameters.interpolated()) store.copyNumericRange(0, parameters.currentBytes(), parameters.currentBytes());
    }

    public void reset() {
        sources.unbind(definition.parameters().initialSources(), store);
        if (instance != null) instance.unbind(store);
        sources.initializeInstance(store, definition.parameters());
        if (instance != null) bindInstance(instance);
    }

    @Override public void close() { sources.releaseInstance(store); store.close(); }
}
