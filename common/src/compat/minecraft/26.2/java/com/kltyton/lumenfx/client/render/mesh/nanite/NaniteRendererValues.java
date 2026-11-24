package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Renderer;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reads the frozen renderer parameter store using each binding's parameter-map name. */
final class NaniteRendererValues {
    private final Renderer renderer;
    private final Map<Key, byte[]> values;

    NaniteRendererValues(Renderer renderer, Map<Key, byte[]> values) {
        this.renderer = renderer;
        this.values = values;
    }

    void floating(String semantic, float[] target) {
        var binding = renderer.attributeBindings().get(semantic);
        if (binding == null) return;
        String type = switch (semantic) {
            case "Position", "PreviousPosition" -> "/Script/Niagara.NiagaraPosition";
            case "MeshOrientation", "PreviousMeshOrientation" -> "/Script/CoreUObject.Quat4f";
            case "Scale", "PreviousScale" -> "/Script/CoreUObject.Vector3f";
            default -> throw new IllegalArgumentException("Unknown Nanite renderer parameter " + semantic);
        };
        var bytes = numeric(binding.name(), type, target.length * Float.BYTES);
        if (bytes == null) return;
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < target.length; i++) target[i] = data.getFloat();
    }

    @Nullable Integer integer(@Nullable String name) {
        var bytes = numeric(name, "/Script/Niagara.NiagaraInt32", Integer.BYTES);
        return bytes == null ? null : ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    boolean selectsMesh(int slot) {
        var binding = renderer.attributeBindings().get("MeshIndex");
        Integer selected = binding == null || binding.particle() ? null : integer(binding.name());
        return selected == null || selected == slot;
    }

    boolean visible() {
        Integer selected = renderer.visibilityParticleBinding() ? null : integer(renderer.visibilityBinding());
        return selected == null || selected == renderer.rendererVisibility();
    }

    private byte @Nullable [] numeric(@Nullable String name, String type, int size) {
        if (name == null) return null;
        for (var entry : values.entrySet())
            if (SourceNames.equal(entry.getKey().name(), name) && SourceNames.equal(entry.getKey().sourceType(), type)
                    && entry.getValue().length == size) return entry.getValue();
        return null;
    }
}
