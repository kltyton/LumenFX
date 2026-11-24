package com.kltyton.lumenfx.client.resource.texture.splat;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextureLoader;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/** UE inverse Filmic tone-curve 3D LUT textures consumed by GaussianSplat post-blend composition. */
public final class GaussianSplatLutTextures {
    public static final Identifier INVERSE_FILMIC_TC = LumenFx.id("textures/lumenfx/gaussian_splat/inverse_filmic_tc.lftex.json");
    public static final Identifier INVERSE_FILMIC_TC_FULL = LumenFx.id("textures/lumenfx/gaussian_splat/inverse_filmic_tc_full.lftex.json");

    private GaussianSplatLutTextures() { }

    public static void prepare(ResourceManager resources, Map<Identifier, PreparedTexture> textures) {
        var filmic = EffectTextureLoader.prepare(INVERSE_FILMIC_TC, resources);
        var full = EffectTextureLoader.prepare(INVERSE_FILMIC_TC_FULL, resources);
        if (filmic != null) textures.put(INVERSE_FILMIC_TC, requireCubeLayout(INVERSE_FILMIC_TC, filmic));
        if (full != null) textures.put(INVERSE_FILMIC_TC_FULL, requireCubeLayout(INVERSE_FILMIC_TC_FULL, full));
    }

    public static @Nullable EffectTexture find(boolean expandGamut) {
        return EffectTextures.get(expandGamut ? INVERSE_FILMIC_TC_FULL : INVERSE_FILMIC_TC);
    }

    private static PreparedTexture requireCubeLayout(Identifier id, PreparedTexture texture) {
        var meta = texture.metadata();
        if (!texture.volume() || meta.width() < 2 || meta.width() != meta.height() || meta.width() != texture.volumeDepth()) {
            throw new IllegalArgumentException(
                    "Gaussian Splat inverse tone-curve LUT must be a cubic 3D volume texture: " + id
                            + " (" + meta.width() + "x" + meta.height() + "x" + texture.volumeDepth() + ")");
        }
        if (EffectTexture.class != null && texture.volumeDepth() != meta.width()) {
            throw new IllegalArgumentException("Invalid Gaussian Splat inverse tone-curve LUT depth: " + id);
        }
        return texture;
    }

    public static EffectTexture requireCubeVolume(Identifier id) {
        var texture = EffectTextures.get(id);
        if (texture == null || texture.dimension() != TextureParameter.Dimension.VOLUME) {
            throw new IllegalStateException("Gaussian Splat inverse tone-curve LUT was not prepared as a 3D volume: " + id);
        }
        return texture;
    }
}
