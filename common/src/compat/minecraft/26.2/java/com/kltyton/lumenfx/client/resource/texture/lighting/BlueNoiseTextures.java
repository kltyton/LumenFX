package com.kltyton.lumenfx.client.resource.texture.lighting;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.texture.EffectTextureLoader;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Source BlueNoise.ush stores the 64 time slices in vertically stacked 128x128 tiles. */
public record BlueNoiseTextures(GpuTextureView scalar, GpuTextureView vector) {
    private static final Identifier SCALAR = LumenFx.id("textures/lumenfx/blue_noise/scalar.lftex.json");
    private static final Identifier VECTOR = LumenFx.id("textures/lumenfx/blue_noise/vector.lftex.json");

    public static void prepare(ResourceManager resources, Map<Identifier, PreparedTexture> textures) {
        var scalar = EffectTextureLoader.prepare(SCALAR, resources);
        var vector = EffectTextureLoader.prepare(VECTOR, resources);
        if (scalar != null) textures.put(SCALAR, requireSourceLayout(SCALAR, scalar));
        if (vector != null) textures.put(VECTOR, requireSourceLayout(VECTOR, vector));
    }

    private static PreparedTexture requireSourceLayout(Identifier id, PreparedTexture texture) {
        if (texture.array() || texture.cube() || texture.volume()
                || texture.metadata().width() != 128 || texture.metadata().height() != 128 * 64
                || texture.metadata().srgb())
            throw new IllegalArgumentException("Expected a linear 128x8192 source blue noise texture: " + id);
        return texture;
    }

    public static GpuTextureView scalarActive() {
        var scalar = EffectTextures.get(SCALAR);
        if (scalar == null) throw new IllegalStateException("Nanite dithered coverage requires the source scalar blue noise texture");
        return scalar.view();
    }

    public static BlueNoiseTextures active() {
        var scalar = EffectTextures.get(SCALAR);
        var vector = EffectTextures.get(VECTOR);
        if (scalar == null || vector == null)
            throw new IllegalStateException("TranslucencyLightingVolume.PositionOffsetRadius requires the source scalar and vector blue noise textures");
        return new BlueNoiseTextures(scalar.view(), vector.view());
    }
}
