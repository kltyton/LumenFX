package com.kltyton.lumenfx.client.component;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.EffectComponent;

/** Typed scene templates are prepared before the owning resource generation is published. */
public final class SceneComponentFactory implements ComponentFactory {
    @Override public EffectComponent create(Context context, JsonObject template) { return new SceneEffectComponent(context, template); }
    @Override public Prepared prepare(JsonObject template) { return SceneEffectComponent.prepare(template); }
}
