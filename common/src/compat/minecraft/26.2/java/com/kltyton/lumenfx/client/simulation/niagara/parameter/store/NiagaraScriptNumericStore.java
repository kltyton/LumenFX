package com.kltyton.lumenfx.client.simulation.niagara.parameter.store;

import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParameterValues;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.VectorMachine;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader.Request;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/** A parent script's real constant-buffer values feed its separately ticked numeric parameter store. */
public final class NiagaraScriptNumericStore implements AutoCloseable {
    private record Binding(Key key, int offset, int bytes) { }
    private final NiagaraParameterStore store;
    private final List<Binding> bindings;
    private final byte[] defaults;
    private final VectorProgram program;
    private final @Nullable ScriptParameterStoreDefinition definition;
    private final @Nullable NiagaraParameterStoreSources sources;
    private final boolean ownsSources;
    private boolean rapidBound;
    private @Nullable NiagaraDataSetStoreBinding dataset;
    private @Nullable NiagaraParameterStore boundInstance;
    private long boundInstanceRevision;
    private long boundStoreRevision;
    private @Nullable NiagaraParameterStore userInstance;
    private long userInstanceRevision;

    public NiagaraScriptNumericStore(VectorProgram program) {
        this(program, null);
    }
    public NiagaraScriptNumericStore(VectorProgram program, @Nullable NiagaraParameterStoreSources sourceStores) {
        this(program, sourceStores, true);
    }
    public NiagaraScriptNumericStore(VectorProgram program, @Nullable NiagaraParameterStoreSources sourceStores, boolean bindRapid) {
        this.program = program;
        rapidBound = bindRapid;
        definition = program.parameterStore();
        ownsSources = definition != null && sourceStores == null;
        sources = definition == null ? null : sourceStores == null ? new NiagaraParameterStoreSources(new LinkedHashMap<>()) : sourceStores;
        if (sources != null) sources.register(program);
        var variables = new LinkedHashMap<Key, NiagaraParameterLayout.Variable>();
        var views = new ArrayList<Binding>();
        for (var parameter : program.parameters()) {
            if (parameter.underlyingType() < 2 || parameter.sourceType().isEmpty()) continue;
            Key key = new Key(parameter.name(), parameter.sourceType(), parameter.underlyingType(), parameter.typeFlags(), Storage.NUMERIC);
            if (variables.containsKey(key)) continue;
            variables.put(key, new NiagaraParameterLayout.Variable(key, parameter.bytes(), parameter.lwc()));
            views.add(new Binding(key, Math.addExact(program.runtimeBytes(), parameter.offset()), parameter.bytes()));
            if (definition != null && program.interpolated()) views.add(new Binding(new Key("PREV_" + parameter.name(),
                    parameter.sourceType(), parameter.underlyingType(), parameter.typeFlags(), Storage.NUMERIC),
                    Math.addExact(2 * program.runtimeBytes() + program.parameterBytes(), parameter.offset()), parameter.bytes()));
        }
        store = new NiagaraParameterStore(definition == null ? new NiagaraParameterLayout(List.copyOf(variables.values())) : definition.layout());
        bindings = List.copyOf(views);
        defaults = program.constantData();
        if (definition != null) {
            if (definition.currentBytes() != program.parameterBytes() || definition.interpolated() != program.interpolated())
                throw new IllegalArgumentException("Script execution store differs from its VM constant layout");
            initialize();
        } else capture(defaults);
    }
    private void initialize() {
        sources.initializeInstance(store, definition);
        if (rapidBound) sources.bind(definition.initialSources(), store);
    }
    public NiagaraParameterStore store() { return store; }
    public boolean modern() { return definition != null; }
    public void bindParameters(NiagaraParameterStore instance, List<NiagaraDefinition.Variable> layout) {
        if (definition == null) return;
        instance.bindTo(store);
        rapidBound = true;
        sources.bind(definition.initialSources(), store);
        userInstance = instance;
        resolveUserInterfaces();
        bindDataSet(layout);
    }
    public void bindDataSet(List<NiagaraDefinition.Variable> layout) {
        if (definition != null) dataset = new NiagaraDataSetStoreBinding(store, layout);
    }
    public void transferDataSet(VectorDataSet data) { if (dataset != null) dataset.toStore(data, 0); }
    public void prepareSources(byte[] constants, Map<String, EffectHandle.ParameterValue> values,
                               NiagaraCollectionSnapshot collections, double x, double y, double z) {
        if (!restoreConstants(constants)) return;
        NiagaraParameterValues.write(program, constants, values, x, y, z);
        NiagaraParameterValues.writeCollections(program, constants, collections, x, y, z);
        capture(constants);
    }
    public boolean restoreConstants(byte[] constants) {
        if (definition == null) return false;
        store.copyNumericData(0, constants, program.runtimeBytes(), program.parameterBytes());
        if (program.interpolated()) store.copyNumericData(program.parameterBytes(), constants,
                2 * program.runtimeBytes() + program.parameterBytes(), program.parameterBytes());
        return true;
    }
    public void tickRapidIteration() { if (definition != null) sources.tickAll(definition.initialSources()); }
    public void capture(byte[] constants) {
        for (var binding : bindings) store.setNumeric(binding.key(), constants, binding.offset(), binding.bytes());
    }
    public void tick() { store.tick(); }
    public void tick(VectorMachine machine) {
        refreshUserInterfaces(false);
        if (definition != null && store.interfacesDirty()) machine.bindInterfaces(store);
        store.tick();
    }
    public void refreshUserInterfaces(boolean rebind) {
        if (userInstance != null && (rebind || userInstanceRevision != userInstance.interfacesRevision())) resolveUserInterfaces();
        if (rebind) store.markInterfacesDirty();
    }
    private void resolveUserInterfaces() {
        NiagaraParameterStoreSources.resolveUserInterfaces(definition, userInstance, store);
        userInstanceRevision = userInstance.interfacesRevision();
    }
    public void bindSystemInterfaces(VectorMachine machine, NiagaraParameterStore instance) {
        if (definition != null && (boundStoreRevision != store.interfacesRevision() || boundInstance != instance
                || boundInstanceRevision != instance.interfacesRevision())) {
            machine.bindInterfaces(store, instance);
            boundInstance = instance;
            boundInstanceRevision = instance.interfacesRevision();
            boundStoreRevision = store.interfacesRevision();
        }
    }
    public void forEachObjectPropertyRequest(@Nullable NiagaraParameterStore instance, BiConsumer<String, Request> consumer) {
        for (var function : program.external()) {
            if (!function.interfaceType().equals("object_property_reader")) continue;
            String identity = function.interfaceId();
            Key key = function.interfaceKey();
            if (key != null) {
                Object value = store.resolveInterface(key, instance, function.instanceKey());
                if (!(value instanceof NiagaraDataInterfaceObject object) || object.propertyReader() == null) continue;
                identity = object.identity();
            }
            consumer.accept(identity, Request.read(function.name(), Objects.requireNonNull(function.interfaceData())));
        }
    }
    public void forEachStaticMeshFunction(@Nullable NiagaraParameterStore instance, BiConsumer<String, String> consumer) {
        for (var function : program.external()) {
            if (!function.interfaceType().equals("static_mesh")) continue;
            Key key = function.interfaceKey();
            if (key == null) {
                consumer.accept(function.interfaceId(), function.name());
            } else if (store.resolveInterface(key, instance, function.instanceKey()) instanceof NiagaraDataInterfaceObject object
                    && object.staticMeshReader() != null) {
                consumer.accept(object.identity(), function.name());
            }
        }
    }
    public void commit() {
        if (definition != null && program.interpolated()) store.copyNumericRange(0, program.parameterBytes(), program.parameterBytes());
    }

    public void commit(byte[] constants) {
        if (definition != null) {
            commit();
        } else if (program.interpolated()) {
            int runtimeBytes = program.runtimeBytes();
            int parameterBytes = program.parameterBytes();
            System.arraycopy(constants, runtimeBytes, constants, 2 * runtimeBytes + parameterBytes, parameterBytes);
        }
    }

    public void reset() {
        boundInstance = null;
        if (definition != null) {
            if (rapidBound) sources.unbind(definition.initialSources(), store);
            initialize();
            if (userInstance != null) resolveUserInterfaces();
        } else { store.resetValues(); capture(defaults); }
    }
    @Override public void close() {
        if (sources != null) sources.releaseInstance(store);
        store.close();
        if (ownsSources) sources.close();
    }
}
