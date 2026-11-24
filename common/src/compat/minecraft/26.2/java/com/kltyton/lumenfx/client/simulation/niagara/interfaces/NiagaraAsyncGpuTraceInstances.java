package com.kltyton.lumenfx.client.simulation.niagara.interfaces;

import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition;
import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition.Provider;
import com.kltyton.lumenfx.effect.niagara.interfaces.NiagaraGpuInterfaceFunctions;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Freezes each system instance's trace DI initialization before it submits a GPU tick. */
public final class NiagaraAsyncGpuTraceInstances {
    private static final Set<String> TRACE_FUNCTIONS = Set.of("issueasyncraytracegpu", "createasyncraytracegpu",
            "reserveasyncraytracegpu", "readasyncraytracegpu");
    private final Map<NiagaraDataInterfaceObject, NiagaraAsyncGpuTraceDefinition> initialized = new IdentityHashMap<>();
    private @Nullable String failure;

    public void reset() {
        initialized.clear();
        failure = null;
    }

    public void initialize(NiagaraDataInterfaceObject object, boolean gpu, NiagaraParameterStore overrides,
                           @Nullable NiagaraGpuInterfaceFunctions functions, NiagaraPlatformEnvironment platform) {
        var authored = object.asyncGpuTrace();
        if (authored == null) return;
        if (authored.provider() == Provider.NONE) {
            initialized.put(object, authored);
            return;
        }
        Provider provider = platform.asyncTraceSettings().resolveSupported(authored.provider(),
                platform.hardwareRayTraceSupported(), platform.globalDistanceFieldTraceSupported());
        if (provider == Provider.NONE && failure == null) {
            failure = object.identity() + " requested " + authored.provider() + " without a supported provider";
        }
        boolean used = functions == null ? gpu
                : functions.uses(object.loadedSourcePath(), overrides.findVariableFromDataInterface(object), TRACE_FUNCTIONS::contains);
        int maximum = used ? authored.maxTracesPerParticle() : 0;
        initialized.put(object, new NiagaraAsyncGpuTraceDefinition(maximum, authored.maxRetraces(), provider));
    }

    public @Nullable String failure() { return failure; }

    public @Nullable NiagaraAsyncGpuTraceDefinition definition(NiagaraDataInterfaceObject object) {
        return initialized.getOrDefault(object, object.asyncGpuTrace());
    }
}
