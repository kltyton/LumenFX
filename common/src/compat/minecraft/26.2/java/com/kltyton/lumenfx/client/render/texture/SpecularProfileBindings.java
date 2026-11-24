package com.kltyton.lumenfx.client.render.texture;

import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.specular.SpecularProfiles;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import java.util.List;
import java.util.Objects;

public final class SpecularProfileBindings {
    private static final String SAMPLER = "LfxSpecularProfiles";

    private SpecularProfileBindings() { }

    public static void layout(BindGroupLayout.Builder layout, boolean required) {
        if (required) layout.withSampler(SAMPLER);
    }

    public static void define(RenderPipeline.Builder pipeline, List<String> profiles) {
        for (int slot = 0; slot < profiles.size(); slot++)
            pipeline.withShaderDefine("LFX_SPECULAR_PROFILE_ID_" + slot, VfxEngine.INSTANCE.specularProfileIndex(profiles.get(slot)));
    }

    public static void bind(RenderPass pass, boolean required) {
        if (!required) return;
        var atlas = Objects.requireNonNull(EffectTextures.get(SpecularProfiles.ATLAS), "Native specular profiles were not prepared");
        pass.bindTexture(SAMPLER, atlas.view(), atlas.sampler());
    }
}
