package com.kltyton.lumenfx.effect.render.component;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Source CPU component allocation policy, template and ordered property bindings. */
public record ComponentRendererSettings(String sourceClass, String templateKey, JsonObject template, int countLimit,
                                        boolean assignByParticleId, boolean firstParticleFrameOnly, boolean activateOnlyNew,
                                        String enabledAttribute, String visibilityAttribute, int visibilityTag,
                                        List<Binding> bindings, boolean inheritOwnerVisibility,
                                        ComponentFactory.@Nullable Prepared preparedFactory) {
    public ComponentRendererSettings(String sourceClass, String templateKey, JsonObject template, int countLimit,
                                     boolean assignByParticleId, boolean firstParticleFrameOnly, boolean activateOnlyNew,
                                     String enabledAttribute, String visibilityAttribute, int visibilityTag,
                                     List<Binding> bindings, ComponentFactory.@Nullable Prepared preparedFactory) {
        this(sourceClass, templateKey, template, countLimit, assignByParticleId, firstParticleFrameOnly, activateOnlyNew,
                enabledAttribute, visibilityAttribute, visibilityTag, bindings, true, preparedFactory);
    }
    public ComponentRendererSettings(String sourceClass, String templateKey, JsonObject template, int countLimit,
                                     boolean assignByParticleId, boolean firstParticleFrameOnly, boolean activateOnlyNew,
                                     String enabledAttribute, String visibilityAttribute, int visibilityTag, List<Binding> bindings) {
        this(sourceClass, templateKey, template, countLimit, assignByParticleId, firstParticleFrameOnly, activateOnlyNew,
                enabledAttribute, visibilityAttribute, visibilityTag, bindings, null);
    }
    public ComponentRendererSettings(String sourceClass, JsonObject template, int countLimit,
                                     boolean assignByParticleId, boolean firstParticleFrameOnly, boolean activateOnlyNew,
                                     String enabledAttribute, String visibilityAttribute, int visibilityTag, List<Binding> bindings) {
        this(sourceClass, "", template, countLimit, assignByParticleId, firstParticleFrameOnly, activateOnlyNew,
                enabledAttribute, visibilityAttribute, visibilityTag, bindings);
    }
    public record Binding(String attribute, ComponentValue.@Nullable Kind sourceType, String property,
                          ComponentValue.@Nullable Kind propertyType, @Nullable String metadataSetter,
                          Map<String, String> setterDefaults) {
        public Binding {
            Objects.requireNonNull(attribute); Objects.requireNonNull(property);
            setterDefaults = Map.copyOf(setterDefaults);
            if (attribute.isBlank() || property.isBlank()) throw new IllegalArgumentException("Component binding has an empty source or property");
        }
    }

    public ComponentRendererSettings {
        Objects.requireNonNull(sourceClass); Objects.requireNonNull(templateKey); Objects.requireNonNull(template);
        Objects.requireNonNull(enabledAttribute); Objects.requireNonNull(visibilityAttribute);
        if (sourceClass.isBlank() || countLimit < 1) throw new IllegalArgumentException("Invalid component renderer template or count limit");
        template = template.deepCopy(); bindings = List.copyOf(bindings);
    }

    @Override public JsonObject template() { return template.deepCopy(); }
    public ComponentRendererSettings withPreparedFactory(ComponentFactory.Prepared factory) {
        return new ComponentRendererSettings(sourceClass, templateKey, template, countLimit, assignByParticleId,
                firstParticleFrameOnly, activateOnlyNew, enabledAttribute, visibilityAttribute, visibilityTag,
                bindings, inheritOwnerVisibility, Objects.requireNonNull(factory));
    }
}
