package com.kltyton.lumenfx.effect.render.light.scene;

import com.kltyton.lumenfx.effect.EffectDefinition;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** A frame owns frozen component data while its effect definition owns material and texture resources. */
public record ScenePointLight(EffectDefinition effect, long identity, PointLightData data,
                              @Nullable Identifier iesTexture, @Nullable String lightFunctionMaterial) { }
