package com.kltyton.lumenfx.client.component.light;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.EffectComponent;

public final class DirectionalLightComponentFactory implements ComponentFactory {
    @Override public int sceneOutputs() { return LIGHTING; }
    @Override public EffectComponent create(Context context, JsonObject template) { return prepare(template).create(context); }
    @Override public Prepared prepare(JsonObject template) { return DirectionalLightEffectComponent.prepare(template); }
}
