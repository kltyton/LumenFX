package com.kltyton.lumenfx.client.simulation.niagara.interfaces;

import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.interfaces.NiagaraGpuInterfaceFunctions;
import java.util.Set;

/** Classifies array access from the current Override pointer and the original full script-function graph. */
public final class NiagaraGpuArrayAccess {
    private static final Set<String> WRITES = Set.of("clear", "resize", "resizeandfill", "setarrayelem", "add",
            "removelastelem", "atomicadd", "atomicmin", "atomicmax");
    private NiagaraGpuArrayAccess() { }
    public static boolean writable(NiagaraDataInterfaceObject object, NiagaraParameterStore overrides,
                                   NiagaraGpuInterfaceFunctions functions) {
        return functions.uses(object.loadedSourcePath(), overrides.findVariableFromDataInterface(object), WRITES::contains);
    }
}
