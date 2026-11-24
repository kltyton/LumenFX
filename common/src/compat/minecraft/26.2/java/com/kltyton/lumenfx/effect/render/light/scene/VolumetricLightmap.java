package com.kltyton.lumenfx.effect.render.light.scene;

import java.util.Locale;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Source level bounds and padded brick textures in Unreal world centimetres. */
public record VolumetricLightmap(Identifier asset, Vec3 min, Vec3 max, int brickSize,
                                 boolean skyBentNormal, boolean directionalShadow) {
    public enum Layer {
        INDIRECTION, AMBIENT, SH0, SH1, SH2, SH3, SH4, SH5, SKY_BENT_NORMAL, DIRECTIONAL_SHADOW;
    }

    public VolumetricLightmap {
        Objects.requireNonNull(asset);
        if (brickSize <= 0 || !validExtent(min.x, max.x) || !validExtent(min.y, max.y) || !validExtent(min.z, max.z))
            throw new IllegalArgumentException("Invalid volumetric lightmap bounds or brick size");
    }

    public Identifier texture(Layer layer) {
        return texture(asset, layer);
    }

    public static Identifier texture(Identifier asset, Layer layer) {
        return asset.withSuffix("/" + layer.name().toLowerCase(Locale.ROOT));
    }

    private static boolean validExtent(double min, double max) {
        return Double.isFinite(min) && Double.isFinite(max) && Double.isFinite(max - min) && max > min;
    }
}
