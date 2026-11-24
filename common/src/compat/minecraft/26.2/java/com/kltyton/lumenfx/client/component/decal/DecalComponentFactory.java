package com.kltyton.lumenfx.client.component.decal;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.EffectComponent;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/** Deferred decals use the shared world receiver pipeline and source fade-duration control. */
public final class DecalComponentFactory implements ComponentFactory {
    private final DoubleSupplier fadeDurationScale;
    public DecalComponentFactory() { this(() -> 1); }
    public DecalComponentFactory(DoubleSupplier fadeDurationScale) { this.fadeDurationScale = Objects.requireNonNull(fadeDurationScale); }
    @Override public int sceneOutputs() { return DECALS; }
    @Override public EffectComponent create(Context context, JsonObject template) { return prepare(template).create(context); }
    @Override public Prepared prepare(JsonObject template) { return DecalEffectComponent.prepare(template, fadeDurationScale); }
}
