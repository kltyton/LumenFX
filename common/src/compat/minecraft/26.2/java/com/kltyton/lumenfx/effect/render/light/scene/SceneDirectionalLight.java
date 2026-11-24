package com.kltyton.lumenfx.effect.render.light.scene;

import com.kltyton.lumenfx.effect.EffectDefinition;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.jspecify.annotations.Nullable;

/** Direction and illuminance in the source scene's axes and working color space. */
public record SceneDirectionalLight(Vec3 direction, Vec3 color, int forwardPriority, int lightingChannels,
                                    boolean affectsTranslucency, float sourceRadius, float softSourceRadius,
                                    float diffuseScale, float specularScale, @Nullable Function lightFunction,
                                    @Nullable Cascades cascades, float volumetricScattering, boolean castVolumetricShadow,
                                    double luminance, @Nullable StaticShadow staticShadow, long identity, @Nullable Atmosphere atmosphere) {
    public SceneDirectionalLight(Vec3 direction, Vec3 color, int forwardPriority, int lightingChannels,
                                 boolean affectsTranslucency, float sourceRadius, float softSourceRadius,
                                 float diffuseScale, float specularScale, @Nullable Function lightFunction,
                                 @Nullable Cascades cascades, float volumetricScattering, boolean castVolumetricShadow,
                                 double luminance, @Nullable StaticShadow staticShadow, long identity) {
        this(direction, color, forwardPriority, lightingChannels, affectsTranslucency, sourceRadius, softSourceRadius,
                diffuseScale, specularScale, lightFunction, cascades, volumetricScattering, castVolumetricShadow, luminance,
                staticShadow, identity, null);
    }
    public SceneDirectionalLight(Vec3 direction, Vec3 color, int forwardPriority, int lightingChannels,
                                 boolean affectsTranslucency, float sourceRadius, float softSourceRadius,
                                 float diffuseScale, float specularScale, @Nullable Function lightFunction,
                                 @Nullable Cascades cascades, float volumetricScattering, boolean castVolumetricShadow,
                                 double luminance, @Nullable StaticShadow staticShadow) {
        this(direction, color, forwardPriority, lightingChannels, affectsTranslucency, sourceRadius, softSourceRadius,
                diffuseScale, specularScale, lightFunction, cascades, volumetricScattering, castVolumetricShadow, luminance, staticShadow, 0L);
    }

    public record Atmosphere(int index, boolean perPixelTransmittance, float diskCosHalfApex, Vec3 diskColorScale) {
        public static final float DEFAULT_DISK_DIAMETER = .5357f;
        public static final Vec3 DEFAULT_DISK_COLOR = new Vec3(1, 1, 1);

        public Atmosphere {
            if (index < 0 || index >= 2) throw new IllegalArgumentException("Atmosphere light index must be 0 or 1");
        }
        public Atmosphere(int index, boolean perPixelTransmittance) {
            this(index, perPixelTransmittance, cosHalfApex(DEFAULT_DISK_DIAMETER), DEFAULT_DISK_COLOR);
        }

        public static float cosHalfApex(float diameterDegrees) {
            return (float) Math.cos(.5 * Math.toRadians(diameterDegrees));
        }
    }

    public record Cascades(float distance, int count, float farDistance, int farCount,
                           float distributionExponent, float transitionFraction, float distanceFadeFraction,
                           float biasDistribution, float depthBias, float slopeBias, boolean unbuiltPreview) {
        public Cascades(float distance, int count, float farDistance, int farCount, float distributionExponent,
                        float transitionFraction, float distanceFadeFraction, float biasDistribution, float depthBias,
                        float slopeBias) {
            this(distance, count, farDistance, farCount, distributionExponent, transitionFraction, distanceFadeFraction,
                    biasDistribution, depthBias, slopeBias, false);
        }
        public Cascades(float distance, int count, float farDistance, int farCount, float distributionExponent,
                        float transitionFraction, float distanceFadeFraction, float biasDistribution, float depthBias) {
            this(distance, count, farDistance, farCount, distributionExponent, transitionFraction, distanceFadeFraction,
                    biasDistribution, depthBias, .5f);
        }
    }
    public record Function(EffectDefinition effect, String material, LightFunctionSettings settings, Matrix4dc worldToLight) {
        public Function { worldToLight = new Matrix4d(worldToLight); }
        @Override public Matrix4dc worldToLight() { return new Matrix4d(worldToLight); }
    }
    public record StaticShadow(Identifier texture, Matrix4dc worldToLight) {
        public StaticShadow { worldToLight = new Matrix4d(worldToLight); }
        @Override public Matrix4dc worldToLight() { return new Matrix4d(worldToLight); }
    }

    /** The forward renderer has one directional slot, including when its winner excludes translucency. */
    public static @Nullable SceneDirectionalLight selectForward(List<SceneDirectionalLight> lights) {
        SceneDirectionalLight selected = null;
        int priority = -1;
        float intensity = 0;
        for (SceneDirectionalLight light : lights) {
            float red = (float) light.color.x, green = (float) light.color.y, blue = (float) light.color.z;
            float strength = red * red + green * green + blue * blue;
            if (light.forwardPriority > priority || (light.forwardPriority == priority && strength > intensity)) {
                selected = light;
                priority = light.forwardPriority;
                intensity = strength;
            }
        }
        return selected;
    }

    public static @Nullable SceneDirectionalLight selectAtmosphere(List<SceneDirectionalLight> lights, int index, boolean legacyLuminance) {
        SceneDirectionalLight selected = null;
        float brightest = 0;
        for (var light : lights) {
            var atmosphere = light.atmosphere();
            if (atmosphere == null || atmosphere.index() != index) continue;
            var color = light.color();
            float luminance = legacyLuminance ? (float) color.x * .3f + (float) color.y * .59f + (float) color.z * .11f
                    : (float) light.luminance();
            if (selected == null || luminance > brightest) {
                selected = light;
                brightest = luminance;
            }
        }
        return selected;
    }
}
