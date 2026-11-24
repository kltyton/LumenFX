const float LFX_BRDF_PI = 3.141592653589793;

vec3 lfx_diffuse_eon(vec3 diffuse, float sigma, float noV, float noL, float voL) {
    vec3 rho = diffuse * (1.0 + (0.189468 - 0.189468 * diffuse) * sigma);
    float s = voL - noV * noL;
    float sOverT = max(s / max(1e-6, max(noV, noL)), s);
    const float c1 = 0.5 - 2.0 / (3.0 * LFX_BRDF_PI);
    float af = 1.0 - sigma * (1.0 - 1.0 / (1.0 + c1));
    float singleScatter = af * (1.0 + sigma * sOverT);
    float multiScatter = 1.0 - af * (1.0 + sigma * (0.262048 - 0.262048 * noV));
    return rho * (singleScatter + rho * multiScatter) / LFX_BRDF_PI;
}

// Area-light GGX uses the representative direction on the spherical source for its half vector.
vec2 lfx_default_lit_half_vector(float noV, float noL, float voL, float sphereSinAlpha, bool newton) {
    if (sphereSinAlpha > 0.0) {
        float cosine = sqrt(1.0 - sphereSinAlpha * sphereSinAlpha);
        float reflectionDotLight = 2.0 * noL * noV - voL;
        if (reflectionDotLight >= cosine) return vec2(1.0, abs(noV));
        float scaledInverseLength = sphereSinAlpha * inversesqrt(max(1e-8, 1.0 - reflectionDotLight * reflectionDotLight));
        float noTangent = scaledInverseLength * (noV - reflectionDotLight * noL);
        float voTangent = scaledInverseLength * (2.0 * noV * noV - 1.0 - reflectionDotLight * voL);
        if (newton) {
            float triple = sqrt(clamp(1.0 - noL * noL - noV * noV - voL * voL + 2.0 * noL * noV * voL, 0.0, 1.0));
            float noBitangent = scaledInverseLength * triple;
            float voBitangent = noBitangent * 2.0 * noV;
            float noCombined = noL * cosine + noV + noTangent;
            float voCombined = voL * cosine + 1.0 + voTangent;
            float p = noBitangent * voCombined;
            float q = noCombined * voCombined;
            float s = voBitangent * noCombined;
            float numerator = q * (-0.5 * p + 0.25 * voBitangent * noCombined);
            float denominator = p * p + s * (s - 2.0 * p)
                    + noCombined * ((noL * cosine + noV) * voCombined * voCombined
                        + q * (-0.5 * (voCombined + voL * cosine) - 0.5));
            float rotation = 2.0 * numerator / max(1e-8, denominator * denominator + numerator * numerator);
            float sinTheta = rotation * denominator;
            float cosTheta = 1.0 - rotation * numerator;
            noTangent = cosTheta * noTangent + sinTheta * noBitangent;
            voTangent = cosTheta * voTangent + sinTheta * voBitangent;
        }
        noL = noL * cosine + noTangent;
        voL = voL * cosine + voTangent;
    }
    float inverseLength = inversesqrt(max(1e-8, 2.0 + 2.0 * voL));
    return clamp(vec2((noL + noV) * inverseLength, (1.0 + voL) * inverseLength), 0.0, 1.0);
}

float lfx_default_lit_broaden(float alphaSquared, float sourceSinAlpha, float voH, bool substrate) {
    float alpha = substrate ? intBitsToFloat(0x1fbd1df5 + (floatBitsToInt(alphaSquared) >> 1)) : sqrt(alphaSquared);
    return alphaSquared + 0.25 * sourceSinAlpha * (3.0 * alpha + sourceSinAlpha) / (voH + 0.001);
}

vec3 lfx_default_lit_fresnel(vec3 f0, float voH) {
    float grazing = 1.0 - voH;
    float grazingSquared = grazing * grazing;
    return mix(f0, vec3(clamp(50.0 * f0.g, 0.0, 1.0)), grazingSquared * grazingSquared * grazing);
}

vec2 lfx_ggx_area_distribution(float roughness, vec2 halfVector, float sphereSinAlpha,
                               float softSinAlpha, float lineCosSubtended, bool substrate) {
    float alpha = roughness * roughness;
    float alphaSquared = alpha * alpha;
    if (softSinAlpha > 0.0)
        alphaSquared = clamp(alphaSquared + softSinAlpha * softSinAlpha / (halfVector.y * 3.6 + 0.4), 0.0, 1.0);
    if (alphaSquared == 0.0) return vec2(0.0);
    float sphereAlpha = alphaSquared;
    float energy = 1.0;
    if (sphereSinAlpha > 0.0) {
        sphereAlpha = lfx_default_lit_broaden(alphaSquared, sphereSinAlpha, halfVector.y, substrate);
        energy = alphaSquared / sphereAlpha;
    }
    if (lineCosSubtended < 1.0) {
        float lineTangent = sqrt((1.0001 - lineCosSubtended) / max(1e-8, 1.0 + lineCosSubtended));
        float lineAlpha = lfx_default_lit_broaden(sphereAlpha, lineTangent, halfVector.y, substrate);
        energy *= sqrt(sphereAlpha / max(1e-5, lineAlpha));
    }
    float noH = halfVector.x;
    float denominator = (noH * alphaSquared - noH) * noH + 1.0;
    return vec2(alphaSquared * energy / (LFX_BRDF_PI * denominator * denominator), alphaSquared);
}

float lfx_ggx_joint_visibility(float alphaSquared, float noV, float noL, bool accurate) {
    if (accurate) {
        float smithV = noL * sqrt(noV * (noV - noV * alphaSquared) + alphaSquared);
        float smithL = noV * sqrt(noL * (noL - noL * alphaSquared) + alphaSquared);
        return 0.5 / (smithV + smithL);
    }
    float slope = sqrt(alphaSquared);
    return 0.5 / (noL * (noV * (1.0 - slope) + slope) + noV * (noL * (1.0 - slope) + slope));
}

float lfx_ggx_area(float roughness, float noV, float noL, vec2 halfVector, float sphereSinAlpha,
                   float softSinAlpha, float lineCosSubtended, bool substrate, bool accurate) {
    vec2 ggx = lfx_ggx_area_distribution(roughness, halfVector, sphereSinAlpha, softSinAlpha, lineCosSubtended, substrate);
    return ggx.y == 0.0 ? 0.0 : ggx.x * lfx_ggx_joint_visibility(ggx.y, noV, noL, accurate);
}

float lfx_default_lit_ggx(float roughness, float noV, float noL, vec2 halfVector, float sphereSinAlpha,
                          float softSinAlpha, float lineCosSubtended) {
    return lfx_ggx_area(roughness, noV, noL, halfVector, sphereSinAlpha, softSinAlpha, lineCosSubtended, false, false);
}

vec3 lfx_default_lit_specular(vec3 f0, float roughness, vec3 normal, vec3 view, vec3 direction, float noL,
                              float sphereSinAlpha, float softSinAlpha, float lineCosSubtended) {
    float noV = dot(normal, view);
    vec2 halfVector = lfx_default_lit_half_vector(noV, dot(normal, direction), dot(view, direction), sphereSinAlpha, true);
    noV = clamp(abs(noV) + 1e-5, 0.0, 1.0);
    return lfx_default_lit_ggx(roughness, noV, noL, halfVector, sphereSinAlpha, softSinAlpha, lineCosSubtended)
            * lfx_default_lit_fresnel(f0, halfVector.y);
}

#ifdef LFX_NATIVE_PARTICLE_ANISOTROPY
float lfx_default_lit_anisotropic_ggx(float roughness, float anisotropy, float noH, float noV, float noL,
                                      vec2 tangentH, vec2 tangentV, vec2 tangentL) {
    float alpha = roughness * roughness;
    vec2 axis = max(alpha * vec2(1.0 + anisotropy, 1.0 - anisotropy), vec2(0.001));
    float area = axis.x * axis.y;
    vec3 halfSlope = vec3(axis.yx * tangentH, area * noH);
    float slopeRatio = area / dot(halfSlope, halfSlope);
    float distribution = (area / LFX_BRDF_PI) * slopeRatio * slopeRatio;
    float visibility = 0.5 / (noL * length(vec3(axis * tangentV, noV)) + noV * length(vec3(axis * tangentL, noL)));
    return distribution * visibility;
}

vec3 lfx_default_lit_anisotropic_specular(vec3 f0, float roughness, float anisotropy,
                                         vec3 normal, vec3 tangent, vec3 view, vec3 direction, float noL) {
    vec3 bitangent = normalize(cross(normal, tangent));
    float rawNoV = dot(normal, view);
    float voL = dot(view, direction);
    float inverseHalfLength = inversesqrt(2.0 + 2.0 * voL);
    float noH = clamp((dot(normal, direction) + rawNoV) * inverseHalfLength, 0.0, 1.0);
    float voH = clamp(inverseHalfLength + inverseHalfLength * voL, 0.0, 1.0);
    float noV = clamp(abs(rawNoV) + 1e-5, 0.0, 1.0);
    vec2 tangentV = vec2(dot(tangent, view), dot(bitangent, view));
    vec2 tangentL = vec2(dot(tangent, direction), dot(bitangent, direction));
    vec2 tangentH = (tangentV + tangentL) * inverseHalfLength;
    return lfx_default_lit_anisotropic_ggx(roughness, anisotropy, noH, noV, noL, tangentH, tangentV, tangentL)
            * lfx_default_lit_fresnel(f0, voH);
}
#endif
