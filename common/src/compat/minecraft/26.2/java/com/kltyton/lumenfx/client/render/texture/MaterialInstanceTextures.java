package com.kltyton.lumenfx.client.render.texture;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.ParameterInfo;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.Snapshot;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Borrowed images are captured before a render pass; unbound inputs restore the base material texture. */
public final class MaterialInstanceTextures {
    private MaterialInstanceTextures() { }
    public static void prepare(MaterialDefinition material, MaterialInstanceParameters.Publication state, @Nullable Snapshot edits) {
        var parameters = material.instanceParameters();
        boolean dynamicTextures = parameters.dynamicValues() != null && !parameters.dynamicTextureSamplers().isEmpty();
        if (parameters.textureLayout().isEmpty() && parameters.fontSamplers().isEmpty() && !dynamicTextures) return;
        RenderSystem.assertOnRenderThread();
        var textures = new LinkedHashMap<String, TextureParameter.Snapshot>();
        var userImages = new TreeMap<String, TextureParameter.Snapshot>(SourceNames.ASCII_CASE_INSENSITIVE);
        var boundImages = new IdentityHashMap<TextureParameter, TextureParameter.Snapshot>();
        for (var binding : parameters.textureLayout()) {
            TextureParameter.Snapshot image;
            if (binding.collection() != null && state.exposedTextures() != null) {
                image = Objects.requireNonNull(state.exposedTextures().resolve(binding), "Collection material texture returned no snapshot");
            } else if (binding.dataInterface() != null) {
                var exposed = state.exposedTextures();
                if (exposed != null) image = Objects.requireNonNull(exposed.resolve(binding), "Exposed material texture returned no snapshot");
                else if (binding.staticExposure()) image = binding.objectDefault() == null ? TextureParameter.Snapshot.UNBOUND : EffectTextures.snapshot(binding.objectDefault());
                else throw new IllegalStateException("Material texture binding needs its exposed interface snapshot: " + binding.variable());
            } else {
                TextureParameter dynamicOverride = edits == null || binding.name().isBlank()
                        ? null : edits.textures().get(new ParameterInfo(binding.name()));
                if (dynamicOverride != null) {
                    image = boundImages.computeIfAbsent(dynamicOverride, ignored -> Objects.requireNonNull(dynamicOverride.resolve(),
                            "Dynamic material texture returned no snapshot"));
                } else {
                    var exposed = state.exposedTextures();
                    TextureParameter bound = exposed == null ? null : exposed.object(binding);
                    if (bound != null) {
                        image = boundImages.computeIfAbsent(bound, ignored -> Objects.requireNonNull(bound.resolve(), "Bound renderer texture returned no snapshot"));
                    } else {
                        TextureParameter selected = state.textureParameters().get(binding.variable());
                        image = selected != null ? userImages.computeIfAbsent(binding.variable(),
                                ignored -> Objects.requireNonNull(selected.resolve(), "Material texture parameter returned no snapshot"))
                                : binding.objectDefault() == null ? TextureParameter.Snapshot.UNBOUND : EffectTextures.snapshot(binding.objectDefault());
                        for (var input : binding.objectInputs()) {
                            TextureParameter override = state.textureParameters().get(input.variable());
                            if (override != null) image = userImages.computeIfAbsent(input.variable(),
                                    ignored -> Objects.requireNonNull(override.resolve(), "Renderer object texture returned no snapshot"));
                            else if (input.defaultPresent()) image = input.defaultTexture() == null
                                    ? TextureParameter.Snapshot.UNBOUND : EffectTextures.snapshot(input.defaultTexture());
                        }
                    }
                }
            }
            var expected = MaterialTextureBindings.expectedDimension(binding.baseDefault());
            if (image.bound() && image.dimension() != expected)
                throw new IllegalArgumentException("Material instance texture dimension does not match its base source: "
                        + binding.variable() + " expects " + expected + " but received " + image.dimension());
            if (!image.bound()) image = EffectTextures.snapshot(binding.baseDefault());
            textures.put(binding.sampler(), image);
        }
        for (var font : parameters.fontSamplers().entrySet()) {
            if (edits != null && edits.inheritedFonts().contains(font.getValue())) {
                var parent = parameters.parentFontPages().get(font.getKey());
                if (parent != null) textures.put(font.getKey(), EffectTextures.snapshot(parent));
            }
            TextureParameter page = edits == null ? null : edits.fontPages().get(font.getValue());
            if (page != null) {
                var image = boundImages.computeIfAbsent(page, ignored -> Objects.requireNonNull(page.resolve(),
                        "Material font page returned no snapshot"));
                if (image.bound() && image.dimension() != TextureParameter.Dimension.D2)
                    throw new IllegalArgumentException("Material font requires a two-dimensional page texture: " + font.getValue().name());
                if (image.bound()) textures.put(font.getKey(), image);
            }
            textures.computeIfAbsent(font.getKey(), sampler -> EffectTextures.snapshot(material.textures().get(sampler)));
        }
        if (dynamicTextures) for (var binding : parameters.dynamicTextureSamplers().entrySet()) {
            String sampler = binding.getKey();
            TextureParameter value = edits == null ? null : edits.textures().get(binding.getValue());
            var image = value == null ? TextureParameter.Snapshot.UNBOUND
                    : boundImages.computeIfAbsent(value, parameter -> Objects.requireNonNull(parameter.resolve(), "Dynamic material texture returned no snapshot"));
            if (image.bound() && image.dimension() != MaterialTextureBindings.expectedDimension(
                    Objects.requireNonNull(material.textures().get(sampler), "Dynamic texture sampler has no source texture")))
                throw new IllegalArgumentException("Dynamic material texture dimension does not match " + binding.getValue().name());
            textures.put(sampler, image);
        }
        parameters.capturedTextures(state, textures);
    }
}
