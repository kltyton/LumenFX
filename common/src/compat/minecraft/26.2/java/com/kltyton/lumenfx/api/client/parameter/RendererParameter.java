package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Immutable renderer objects supplied in the same input snapshot as numerical parameters. */
public sealed interface RendererParameter {
    record Mesh(@Nullable StaticMeshAsset value) implements RendererParameter { }
    record Material(@Nullable MaterialDefinition value) implements RendererParameter { }
    record MaterialVariants(Map<String, Material> values) implements RendererParameter {
        public MaterialVariants { values = Map.copyOf(values); }
    }
    static @Nullable RendererParameter named(Map<String, RendererParameter> parameters, String name) {
        var exact = parameters.get(name);
        if (exact != null) return exact;
        for (var entry : parameters.entrySet()) if (SourceNames.equal(entry.getKey(), name)) return entry.getValue();
        return null;
    }
    static @Nullable MaterialDefinition material(@Nullable RendererParameter parameter, String variant) {
        if (parameter instanceof Material material) return material.value();
        if (parameter instanceof MaterialVariants variants) {
            Material material = variants.values().get(variant);
            if (material == null) {
                for (var entry : variants.values().entrySet()) {
                    if (SourceNames.equal(entry.getKey(), variant)) {
                        material = entry.getValue();
                        break;
                    }
                }
            }
            return material == null ? null : material.value();
        }
        return null;
    }
    record Meshes(List<MeshRendererSettings.Entry> values) implements RendererParameter {
        public Meshes { values = List.copyOf(values); }
    }
}
