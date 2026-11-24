package com.kltyton.lumenfx.client.render.decal;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.decal.DecalRendererSettings;
import java.util.Objects;

/** Null user objects retain the configured material; incompatible domains use the engine decal material. */
public final class DecalMaterialSelection {
    private DecalMaterialSelection() { }
    public static MaterialDefinition resolve(DecalRendererSettings settings, EffectDefinition effect,
                                             MaterialDefinition configured, EffectHandle.Input input) {
        if (settings.materialBinding() == null) return configured;
        MaterialDefinition selected = RendererParameter.material(
                RendererParameter.named(input.rendererParameters(), settings.materialBinding()), settings.materialVariant());
        if (selected == null) return configured;
        if (selected.decalSettings() != null) return selected;
        return Objects.requireNonNull(effect.materials().get(settings.invalidDomainMaterial()), "Missing engine default decal material");
    }
}
