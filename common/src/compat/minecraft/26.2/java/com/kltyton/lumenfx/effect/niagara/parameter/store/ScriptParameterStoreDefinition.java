package com.kltyton.lumenfx.effect.niagara.parameter.store;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.curve.NiagaraCurve;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Execution declarations retain raw current/PREV bytes and ordered source object references. */
public record ScriptParameterStoreDefinition(NiagaraParameterLayout layout, byte[] data,
        List<NumericStoreDefinition.Value> numeric, List<Reference> references, int currentBytes, boolean interpolated,
        List<Source> initialSources, List<Source> instanceSources, List<UserInterfaceBinding> userInterfaces,
        List<@Nullable ObjectValue> interfaceValues, List<@Nullable ObjectValue> objectValues) {
    public ScriptParameterStoreDefinition(NiagaraParameterLayout layout, byte[] data,
            List<NumericStoreDefinition.Value> numeric, List<Reference> references, int currentBytes, boolean interpolated,
            List<Source> initialSources, List<Source> instanceSources, List<UserInterfaceBinding> userInterfaces) {
        this(layout, data, numeric, references, currentBytes, interpolated, initialSources, instanceSources, userInterfaces, List.of(), List.of());
    }
    public ScriptParameterStoreDefinition(NiagaraParameterLayout layout, byte[] data,
            List<NumericStoreDefinition.Value> numeric, List<Reference> references, int currentBytes, boolean interpolated,
            List<Source> initialSources, List<Source> instanceSources) {
        this(layout, data, numeric, references, currentBytes, interpolated, initialSources, instanceSources, List.of());
    }
    public record UserInterfaceBinding(Key source, Key target) {
        public UserInterfaceBinding {
            if (source.storage() != Storage.INTERFACE || target.storage() != Storage.INTERFACE)
                throw new IllegalArgumentException("User DI binding requires two interface slots");
        }
    }
    public ScriptParameterStoreDefinition(NiagaraParameterLayout layout, byte[] data,
            List<NumericStoreDefinition.Value> numeric, List<Reference> references, int currentBytes, boolean interpolated) {
        this(layout, data, numeric, references, currentBytes, interpolated, List.of(), List.of());
    }
    public record BoundOffset(Storage storage, int source, int target, int bytes) {
        public BoundOffset {
            Objects.requireNonNull(storage);
            if (source < 0 || target < 0 || bytes < 0 || (storage != Storage.NUMERIC && bytes != 0))
                throw new IllegalArgumentException("Invalid cooked Niagara bound parameter offset");
        }
    }
    public record Source(String identity, ScriptParameterStoreDefinition definition, boolean tick,
                         @Nullable List<BoundOffset> boundOffsets) {
        public Source(String identity, ScriptParameterStoreDefinition definition, boolean tick) {
            this(identity, definition, tick, null);
        }
        public Source {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(definition);
            if (boundOffsets != null) boundOffsets = List.copyOf(boundOffsets);
        }
    }
    public record ObjectValue(String identity, JsonObject source, @Nullable List<NiagaraCurve> curves,
                              @Nullable VectorFieldVolume field) {
        public ObjectValue(String identity, JsonObject source) { this(identity, source, null, null); }
        public ObjectValue(String identity, JsonObject source, @Nullable List<NiagaraCurve> curves) { this(identity, source, curves, null); }
        public ObjectValue {
            Objects.requireNonNull(identity); source = source.deepCopy();
            curves = curves == null ? null : List.copyOf(curves);
        }
        @Override public JsonObject source() { return source.deepCopy(); }
    }
    public record CollectionObjectBinding(String path, boolean systemOverride, @Nullable ObjectValue baseDefault) {
        public CollectionObjectBinding { Objects.requireNonNull(path); }
    }
    public record Reference(Key key, @Nullable ObjectValue value, @Nullable CollectionObjectBinding collection) {
        public Reference(Key key, @Nullable ObjectValue value) { this(key, value, null); }
        public Reference {
            if (key.storage() == Storage.NUMERIC) throw new IllegalArgumentException("Numeric execution slot cannot hold an object reference");
        }
    }
    public ScriptParameterStoreDefinition {
        Objects.requireNonNull(layout);
        data = data.clone(); numeric = List.copyOf(numeric); references = List.copyOf(references);
        initialSources = List.copyOf(initialSources); instanceSources = List.copyOf(instanceSources);
        userInterfaces = List.copyOf(userInterfaces);
        interfaceValues = Collections.unmodifiableList(new ArrayList<>(interfaceValues));
        objectValues = Collections.unmodifiableList(new ArrayList<>(objectValues));
        if (!interfaceValues.isEmpty() && layout.interfaceCount() >= 0 && interfaceValues.size() != layout.interfaceCount()
                || !objectValues.isEmpty() && layout.objectCount() >= 0 && objectValues.size() != layout.objectCount())
            throw new IllegalArgumentException("Parameter object values differ from their source array counts");
        if (layout.interfaceCount() < 0 && !interfaceValues.isEmpty() || layout.objectCount() < 0 && !objectValues.isEmpty())
            layout = new NiagaraParameterLayout(layout.variables(), layout.numericBytes(),
                    interfaceValues.isEmpty() ? layout.interfaceCount() : interfaceValues.size(),
                    objectValues.isEmpty() ? layout.objectCount() : objectValues.size());
        if (currentBytes < 0 || currentBytes > data.length || interpolated && currentBytes > data.length - currentBytes)
            throw new IllegalArgumentException("Execution current/PREV data exceeds its store");
        var variables = new HashMap<Key, NiagaraParameterLayout.Variable>();
        for (var variable : layout.variables()) variables.put(variable.key(), variable);
        for (var value : numeric) {
            var variable = variables.get(value.key());
            if (variable == null || variable.bytes() != value.bytes() || value.offset() > data.length - value.bytes())
                throw new IllegalArgumentException("Execution numeric default differs from its declaration");
        }
        for (var reference : references) if (!variables.containsKey(reference.key()))
            throw new IllegalArgumentException("Execution object reference has no declaration");
        for (var binding : userInterfaces) if (!variables.containsKey(binding.target()))
            throw new IllegalArgumentException("User DI binding has no execution declaration");
    }
    @Override public byte[] data() { return data.clone(); }
}
