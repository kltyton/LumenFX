package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheDebugData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheDebugData.Parameter;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheDebugData.Reference;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class NiagaraCacheDebugCapture {
    private final Map<Object, String> hostObjects = new IdentityHashMap<>();

    NiagaraCacheDebugData.Frame snapshot(Map<String, NiagaraParameterStore> stores, NiagaraParameterStoreSources sources,
                                         List<Parameter> staticValues) {
        var captured = new LinkedHashMap<String, List<Parameter>>();
        stores.forEach((name, store) -> {
            var values = new ArrayList<Parameter>();
            store.snapshotValues((key, numeric) -> values.add(new Parameter(key, numeric, null)),
                    (key, object) -> values.add(new Parameter(key, new byte[0], reference(key, object, sources))));
            if (!values.isEmpty()) captured.put(name, values);
        });
        if (!staticValues.isEmpty()) captured.put("Static Variables", staticValues);
        return new NiagaraCacheDebugData.Frame(captured);
    }

    private @Nullable Reference reference(Key key, @Nullable Object object, NiagaraParameterStoreSources sources) {
        if (object == null) return null;
        if (object instanceof NiagaraDataInterfaceObject dataInterface) {
            String className = dataInterface.sourceClassName();
            return new Reference(dataInterface.identity(), className == null ? key.sourceType() : className);
        }
        String sourceIdentity = sources.sourceIdentity(object);
        if (sourceIdentity != null) return new Reference(sourceIdentity, key.sourceType());
        if (object instanceof TextureParameter.Resource resource)
            return new Reference(resource.id().toString(), key.sourceType());
        return new Reference(hostObjects.computeIfAbsent(object, ignored -> "host:" + hostObjects.size()),
                object.getClass().getName());
    }
}
