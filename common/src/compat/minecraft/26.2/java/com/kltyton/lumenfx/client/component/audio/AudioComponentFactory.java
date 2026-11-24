package com.kltyton.lumenfx.client.component.audio;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.EffectComponent;

/** Creates pooled audio components from the source template. */
public final class AudioComponentFactory implements ComponentFactory {
    @Override public EffectComponent create(Context context, JsonObject template) { return prepare(template).create(context); }
    @Override public Prepared prepare(JsonObject template) { return AudioEffectComponent.prepare(template); }
}
