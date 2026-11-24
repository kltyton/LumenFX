package com.kltyton.lumenfx.effect.render.light.scene;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.jspecify.annotations.Nullable;

/** Point-light shader parameters retain source axes and centimetres until view upload. */
public record PointLightShaderParameters(Vec3 worldPosition, float inverseRadius, Vec3 color,
                                        float falloffExponent, Vec3 direction, Vec3 tangent,
                                        float specularScale, float diffuseScale, float sourceRadius,
                                        float softSourceRadius, float sourceLength, float inverseExposureBlend,
                                        int iesAtlasIndex, int lightFunctionAtlasIndex, boolean affectsTranslucency,
                                        boolean inverseSquared, @Nullable SpotLightCone cone, @Nullable RectLightShape rect) {
    public PointLightShaderParameters(Vec3 worldPosition, float inverseRadius, Vec3 color,
                                      float falloffExponent, Vec3 direction, Vec3 tangent, float specularScale, float diffuseScale,
                                      float sourceRadius, float softSourceRadius, float sourceLength, float inverseExposureBlend,
                                      int iesAtlasIndex, int lightFunctionAtlasIndex, boolean affectsTranslucency, boolean inverseSquared,
                                      @Nullable SpotLightCone cone) {
        this(worldPosition, inverseRadius, color, falloffExponent, direction, tangent, specularScale, diffuseScale,
                sourceRadius, softSourceRadius, sourceLength, inverseExposureBlend, iesAtlasIndex, lightFunctionAtlasIndex,
                affectsTranslucency, inverseSquared, cone, null);
    }
    public PointLightShaderParameters(Vec3 worldPosition, float inverseRadius, Vec3 color,
                                      float falloffExponent, Vec3 direction, Vec3 tangent, float specularScale, float diffuseScale,
                                      float sourceRadius, float softSourceRadius, float sourceLength, float inverseExposureBlend,
                                      int iesAtlasIndex, int lightFunctionAtlasIndex, boolean affectsTranslucency, boolean inverseSquared) {
        this(worldPosition, inverseRadius, color, falloffExponent, direction, tangent, specularScale, diffuseScale,
                sourceRadius, softSourceRadius, sourceLength, inverseExposureBlend, iesAtlasIndex, lightFunctionAtlasIndex,
                affectsTranslucency, inverseSquared, null);
    }
    public PointLightShaderParameters(Vec3 worldPosition, float inverseRadius, Vec3 color,
                                      float falloffExponent, Vec3 direction, Vec3 tangent, float specularScale, float diffuseScale,
                                      float sourceRadius, float softSourceRadius, float sourceLength, float inverseExposureBlend,
                                      int iesAtlasIndex, int lightFunctionAtlasIndex, boolean affectsTranslucency) {
        this(worldPosition, inverseRadius, color, falloffExponent, direction, tangent, specularScale, diffuseScale,
                sourceRadius, softSourceRadius, sourceLength, inverseExposureBlend, iesAtlasIndex, lightFunctionAtlasIndex,
                affectsTranslucency, falloffExponent == 0);
    }
    public static PointLightShaderParameters prepare(Matrix4dc lightToWorld, Matrix4dc worldToLight,
                                                     float inverseRadius, Vec3 color, boolean inverseSquared, float falloffExponent,
                                                     float specularScale, float diffuseScale, float sourceRadius, float softSourceRadius,
                                                     float sourceLength, float inverseExposureBlend, int iesAtlasIndex,
                                                     int lightFunctionAtlasIndex, boolean affectsTranslucency) {
        return prepare(lightToWorld, worldToLight, inverseRadius, color, inverseSquared, falloffExponent, specularScale, diffuseScale,
                sourceRadius, softSourceRadius, sourceLength, inverseExposureBlend, iesAtlasIndex, lightFunctionAtlasIndex,
                affectsTranslucency, null);
    }

    public static PointLightShaderParameters prepare(Matrix4dc lightToWorld, Matrix4dc worldToLight,
                                                     float inverseRadius, Vec3 color, boolean inverseSquared, float falloffExponent,
                                                     float specularScale, float diffuseScale, float sourceRadius, float softSourceRadius,
                                                     float sourceLength, float inverseExposureBlend, int iesAtlasIndex,
                                                     int lightFunctionAtlasIndex, boolean affectsTranslucency, @Nullable SpotLightCone cone) {
        return prepare(lightToWorld, worldToLight, inverseRadius, color, inverseSquared, falloffExponent, specularScale, diffuseScale,
                sourceRadius, softSourceRadius, sourceLength, inverseExposureBlend, iesAtlasIndex, lightFunctionAtlasIndex,
                affectsTranslucency, cone, null);
    }

    public static PointLightShaderParameters prepare(Matrix4dc lightToWorld, Matrix4dc worldToLight,
                                                     float inverseRadius, Vec3 color, boolean inverseSquared, float falloffExponent,
                                                     float specularScale, float diffuseScale, float sourceRadius, float softSourceRadius,
                                                     float sourceLength, float inverseExposureBlend, int iesAtlasIndex, int lightFunctionAtlasIndex,
                                                     boolean affectsTranslucency, @Nullable SpotLightCone cone, @Nullable RectLightShape rect) {
        Vec3 position = new Vec3(lightToWorld.m30(), lightToWorld.m31(), lightToWorld.m32());
        Vec3 direction = new Vec3((float)-worldToLight.m00(), (float)-worldToLight.m10(), (float)-worldToLight.m20());
        Vec3 tangent = new Vec3((float)worldToLight.m02(), (float)worldToLight.m12(), (float)worldToLight.m22());
        return new PointLightShaderParameters(position, inverseRadius, color, inverseSquared ? 0 : falloffExponent,
                direction, tangent, clampScale(specularScale), clampScale(diffuseScale), sourceRadius, softSourceRadius,
                sourceLength, inverseExposureBlend, iesAtlasIndex, lightFunctionAtlasIndex, affectsTranslucency, inverseSquared, cone, rect);
    }

    private static float clampScale(float value) { return value < 0 ? 0 : value < 1 ? value : 1; }

    public PointLightShaderParameters withAtlases(int ies, int lightFunction) {
        return new PointLightShaderParameters(worldPosition, inverseRadius, color, falloffExponent, direction, tangent,
                specularScale, diffuseScale, sourceRadius, softSourceRadius, sourceLength, inverseExposureBlend,
                ies, lightFunction, affectsTranslucency, inverseSquared, cone, rect);
    }
}
