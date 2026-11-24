package com.kltyton.lumenfx.effect.render.mesh.nanite;

import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Component instance payload of the native NiagaraNanite renderer. */
public record NaniteRendererSettings(List<InstanceFloat> instanceFloats, Map<Integer, Key> meshParameters) {
    public NaniteRendererSettings {
        instanceFloats = List.copyOf(instanceFloats);
        meshParameters = Map.copyOf(meshParameters);
    }

    public record InstanceFloat(@Nullable Key parameter, int component, float defaultValue) {
        public InstanceFloat {
            if (component < 0 || !Float.isFinite(defaultValue))
                throw new IllegalArgumentException("Invalid Nanite per-instance float binding");
        }
    }
}
