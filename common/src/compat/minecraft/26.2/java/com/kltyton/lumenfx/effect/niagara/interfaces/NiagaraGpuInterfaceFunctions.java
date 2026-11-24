package com.kltyton.lumenfx.effect.niagara.interfaces;

import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** Saved source-object and resolved-variable associations for GPU generated functions. */
public record NiagaraGpuInterfaceFunctions(List<Binding> bindings) {
    public record Binding(String sourcePath, @Nullable Key variable, List<String> functions) {
        public Binding { functions = List.copyOf(functions); }
    }
    public NiagaraGpuInterfaceFunctions { bindings = List.copyOf(bindings); }

    public boolean uses(@Nullable String sourcePath, @Nullable Key userVariable, Predicate<String> function) {
        if (userVariable != null && (userVariable.name().isEmpty() || SourceNames.equal(userVariable.name(), "None"))) userVariable = null;
        for (var binding : bindings) {
            boolean sameObject = sourcePath != null && SourceNames.equal(sourcePath, binding.sourcePath());
            boolean sameUser = userVariable != null && userVariable.equals(binding.variable());
            if ((sameObject || sameUser) && binding.functions().stream().map(SourceNames::canonical).anyMatch(function)) return true;
        }
        return false;
    }
}
