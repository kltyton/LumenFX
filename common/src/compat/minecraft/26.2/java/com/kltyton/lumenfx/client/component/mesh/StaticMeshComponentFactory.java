package com.kltyton.lumenfx.client.component.mesh;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.EffectComponent;
import com.kltyton.lumenfx.client.resource.mesh.MeshRendererJson;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

/** Resolves the component's mesh through the resource reload's shared static mesh cache. */
public final class StaticMeshComponentFactory implements ComponentFactory {
    @Override public int sceneOutputs() { return GEOMETRY; }

    public Prepared prepare(JsonObject template, Function<Identifier, StaticMeshAsset> meshes) {
        var renderer = MeshRendererJson.read(template.getAsJsonObject("lumenfx_static_mesh"), meshes);
        return StaticMeshEffectComponent.prepare(template, renderer);
    }

    @Override public EffectComponent create(Context context, JsonObject template) {
        throw new IllegalStateException("Static mesh component templates must be prepared with the effect's geometry resources");
    }
}
