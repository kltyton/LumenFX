package com.kltyton.lumenfx.client.render.texture;

import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.toon.ToonProfiles;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import java.util.List;
import java.util.Objects;

public final class ToonProfileBindings {
    private static final String SAMPLER = "LfxToonProfiles";

    private ToonProfileBindings() { }

    public static void define(RenderPipeline.Builder pipeline, List<String> profiles) {
        for (int slot = 0; slot < profiles.size(); slot++)
            pipeline.withShaderDefine("LFX_TOON_PROFILE_ID_" + slot, VfxEngine.INSTANCE.toonProfileIndex(profiles.get(slot)));
    }

    public static void layout(BindGroupLayout.Builder layout) { layout.withSampler(SAMPLER); }

    public static void bind(RenderPass pass) {
        var atlas = Objects.requireNonNull(EffectTextures.get(ToonProfiles.ATLAS), "Native Toon profiles were not prepared");
        pass.bindTexture(SAMPLER, atlas.arrayView(), atlas.sampler());
    }
}
