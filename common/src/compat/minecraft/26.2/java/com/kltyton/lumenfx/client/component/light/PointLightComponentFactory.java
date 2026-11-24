package com.kltyton.lumenfx.client.component.light;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.EffectComponent;

/** Resource preparation decodes local-light templates once before client-thread creation. */
public final class PointLightComponentFactory implements ComponentFactory {
    private final PointLightEffectComponent.Type type;

    public PointLightComponentFactory() { this(PointLightEffectComponent.Type.POINT); }
    private PointLightComponentFactory(PointLightEffectComponent.Type type) { this.type = type; }
    public static PointLightComponentFactory spot() { return new PointLightComponentFactory(PointLightEffectComponent.Type.SPOT); }
    public static PointLightComponentFactory rect() { return new PointLightComponentFactory(PointLightEffectComponent.Type.RECT); }

    @Override public int sceneOutputs() { return LIGHTING; }
    @Override public EffectComponent create(Context context, JsonObject template) { return prepare(template).create(context); }
    @Override public Prepared prepare(JsonObject template) { return PointLightEffectComponent.prepare(template, type); }
}
