package com.kltyton.lumenfx.effect.render.light.scene;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The three-band diffuse environment captured by the source sky light. */
public record SkyLightIrradiance(List<Coefficient> coefficients, Vec3 color, float volumetricScattering,
                                 boolean staticLighting, boolean staticShadowing, boolean affectsWorld,
                                 @Nullable Reflection reflection, boolean stationary, Vec3 occlusionTint) {
    public SkyLightIrradiance(List<Coefficient> coefficients, Vec3 color, float volumetricScattering,
                              boolean staticLighting, boolean staticShadowing, boolean affectsWorld,
                              @Nullable Reflection reflection) {
        this(coefficients, color, volumetricScattering, staticLighting, staticShadowing, affectsWorld, reflection,
                staticShadowing || reflection != null && !reflection.movable() && !staticLighting, Vec3.ZERO);
    }

    public SkyLightIrradiance(List<Coefficient> coefficients, Vec3 color, float volumetricScattering,
                              boolean staticLighting, boolean staticShadowing) {
        this(coefficients, color, volumetricScattering, staticLighting, staticShadowing, true, null);
    }
    public record Blend(Identifier texture, float fraction) {
        public Blend {
            Objects.requireNonNull(texture);
            if (!(fraction > 0 && fraction < 1)) throw new IllegalArgumentException("Invalid sky reflection blend fraction");
        }
    }

    public record Reflection(Identifier texture, int mipCount, float averageBrightness, boolean movable,
                             boolean preintegrated, Vec3 luminanceWeights, @Nullable Blend blend) {
        public Reflection(Identifier texture, int mipCount, float averageBrightness, boolean movable,
                          boolean preintegrated, Vec3 luminanceWeights) {
            this(texture, mipCount, averageBrightness, movable, preintegrated, luminanceWeights, null);
        }

        public Reflection {
            Objects.requireNonNull(texture);
            Objects.requireNonNull(luminanceWeights);
            if (mipCount < 1 || !Float.isFinite(averageBrightness) || averageBrightness < 0
                    || !Double.isFinite(luminanceWeights.x) || !Double.isFinite(luminanceWeights.y) || !Double.isFinite(luminanceWeights.z)
                    || blend != null && blend.texture().equals(texture))
                throw new IllegalArgumentException("Invalid source sky reflection");
        }
        public Identifier preintegratedTexture() {
            return preintegratedTexture(texture);
        }
        public static Identifier preintegratedTexture(Identifier texture) {
            return Identifier.fromNamespaceAndPath(texture.getNamespace(), texture.getPath() + ".gf");
        }
    }
    public record Coefficient(float x, float y, float z, float w) {
        public Coefficient {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(w))
                throw new IllegalArgumentException("Invalid sky irradiance coefficient");
        }
    }

    public SkyLightIrradiance {
        coefficients = List.copyOf(coefficients);
        Objects.requireNonNull(color, "sky light color");
        Objects.requireNonNull(occlusionTint, "sky light occlusion tint");
        if (coefficients.size() != 7 || !Float.isFinite(volumetricScattering)
                || !finite(color) || !finite(occlusionTint))
            throw new IllegalArgumentException("Invalid source sky irradiance");
        volumetricScattering = Math.max(0, volumetricScattering);
    }

    private static boolean finite(Vec3 color) {
        return Float.isFinite((float) color.x) && Float.isFinite((float) color.y) && Float.isFinite((float) color.z);
    }
}
