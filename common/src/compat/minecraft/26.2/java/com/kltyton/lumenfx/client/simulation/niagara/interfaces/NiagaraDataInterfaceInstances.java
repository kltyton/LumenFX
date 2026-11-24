package com.kltyton.lumenfx.client.simulation.niagara.interfaces;

import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/** Discovers physical DI objects in the native Instance, System, then emitter store order. */
public final class NiagaraDataInterfaceInstances {
    public record Usage(String parameterName, boolean cpu, boolean gpu, @Nullable String gpuSourceName, List<String> parameterNames) {
        public Usage { parameterNames = List.copyOf(parameterNames); }
    }
    private static final class Entry {
        final NiagaraDataInterfaceObject object;
        final String parameterName;
        final Set<String> parameterNames = new LinkedHashSet<>();
        boolean cpu;
        @Nullable String gpuSourceName;
        Entry(NiagaraDataInterfaceObject object, String parameterName) {
            this.object = object;
            this.parameterName = parameterName;
        }
    }
    private final List<Entry> ordered = new ArrayList<>();
    private final Map<NiagaraDataInterfaceObject, Entry> objects = new IdentityHashMap<>();

    public void visit(NiagaraParameterStore store, @Nullable NiagaraParameterStore instance, boolean cpu, boolean gpu) {
        store.forEachDataInterface((key, defaultValue) -> {
            Object value = defaultValue;
            if (instance != null && instance.contains(key)) {
                Object replacement = instance.dataInterface(key);
                if (replacement != null) value = replacement;
            }
            if (value == null) return;
            if (!(value instanceof NiagaraDataInterfaceObject object))
                throw new IllegalStateException("DI slot has no runtime object: " + key.name());
            Entry entry = objects.get(object);
            if (entry == null) {
                entry = new Entry(object, key.name());
                objects.put(object, entry);
                ordered.add(entry);
            }
            if (gpu) entry.gpuSourceName = key.name();
            else if (cpu) entry.cpu = true;
            entry.parameterNames.add(key.name());
        });
    }

    public void forEach(BiConsumer<NiagaraDataInterfaceObject, Usage> action) {
        for (Entry entry : ordered)
            action.accept(entry.object, new Usage(entry.parameterName, entry.cpu, entry.gpuSourceName != null,
                    entry.gpuSourceName, List.copyOf(entry.parameterNames)));
    }
}
