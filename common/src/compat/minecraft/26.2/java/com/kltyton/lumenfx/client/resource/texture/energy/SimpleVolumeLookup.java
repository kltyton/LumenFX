package com.kltyton.lumenfx.client.resource.texture.energy;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.texture.EffectTextureLoader;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/** The packed 4D directional albedo lookup, exposed as slice rows to graphics samplers. */
public final class SimpleVolumeLookup {
    public static final Identifier PUNCTUAL = LumenFx.id("textures/lumenfx/shading_energy/simple_volume_punctual.lftex.json");

    private SimpleVolumeLookup() { }

    public static @Nullable PreparedTexture prepare(Identifier id, ResourceManager resources, int depth) {
        PreparedTexture volume = EffectTextureLoader.prepare(id, resources);
        if (volume == null) return null;
        TextureMetadata source = volume.metadata();
        if (volume.volumeDepth() != depth || source.width() != 11 || source.height() != 11)
            throw new IllegalArgumentException("The native Substrate lookup has the wrong volume extent: " + id);
        TextureStorageFormat storage = TextureStorageFormat.from(source.cookedFormat());
        // A complete compressed slice occupies whole block rows. The GLSL sampler clamps to the authored 11 rows.
        int stride = storage.compressed() ? 12 : 11;
        int height = stride * depth;
        var metadata = new TextureMetadata(source.width(), height, source.srgb(), TextureMetadata.MipMode.NO_MIPMAPS,
                0, 2, false, false, false, TextureMetadata.Address.CLAMP, TextureMetadata.Address.CLAMP,
                TextureMetadata.FilterMode.LINEAR, TextureMetadata.FilterMode.POINT, 1,
                source.lodGroup(), source.compression(), source.cookedFormat(), source.pngSha256(), source.mipData(),
                source.legacyGamma(), source.sourceInput());
        return new PreparedTexture(id, metadata, List.of(volume.mips().getFirst()));
    }
}
