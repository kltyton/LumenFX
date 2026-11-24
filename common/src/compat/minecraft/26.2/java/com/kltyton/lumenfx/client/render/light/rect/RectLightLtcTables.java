package com.kltyton.lumenfx.client.render.light.rect;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.energy.ShadingEnergyTable;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.systems.RenderPass;
import java.util.Objects;

public record RectLightLtcTables(EffectTexture matrix, EffectTexture amplitude) {
    private static final String MATRIX = "LfxRectGgxMatrix";
    private static final String AMPLITUDE = "LfxRectGgxAmplitude";

    public static void layout(BindGroupLayout.Builder bindings) {
        bindings.withSampler(MATRIX).withSampler(AMPLITUDE);
    }

    public static RectLightLtcTables resolve() {
        return new RectLightLtcTables(texture(ShadingEnergyTable.Kind.GGX_LTC_MATRIX),
                texture(ShadingEnergyTable.Kind.GGX_LTC_AMPLITUDE));
    }

    public void bind(RenderPass pass) {
        pass.bindTexture(MATRIX, matrix.view(), matrix.sampler());
        pass.bindTexture(AMPLITUDE, amplitude.view(), amplitude.sampler());
    }

    private static EffectTexture texture(ShadingEnergyTable.Kind kind) {
        var id = kind.resource(LumenFx.MOD_ID);
        return Objects.requireNonNull(EffectTextures.get(id), "Rect light requires shading table " + id);
    }
}
