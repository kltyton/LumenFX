// Shared anisotropic bent-normal and Charlie Sheen (Fuzz) helpers for translucent Substrate Slabs.

float lfx_slab_sheen_d(float roughness, float noH) {
    float r = clamp(roughness, 0.02, 1.0);
    float invR = 1.0 / (r * r);
    float cos2h = noH * noH;
    float sin2h = max(1.0 - cos2h, 0.0078125);
    return (2.0 + invR) * pow(sin2h, invR * 0.5) / (2.0 * 3.141592653589793);
}

float lfx_slab_sheen_v(float noV, float noL) {
    return 1.0 / max(4.0 * (noL + noV - noL * noV), 1e-4);
}

// Smooth approximation of directional Sheen albedo E(NoV, roughness) in [0, 1].
float lfx_slab_sheen_albedo(float noV, float roughness) {
    float r = clamp(roughness, 0.02, 1.0);
    float c = clamp(noV, 0.0, 1.0);
    float grazing = pow(1.0 - c, 2.5);
    return clamp(mix(0.12 + 0.58 * r, 0.45 + 0.52 * r, grazing), 0.0, 0.98);
}

vec3 lfx_slab_aniso_env_normal(vec3 normal, vec3 view, float roughness, float anisotropy,
                               vec3 worldTangent, bool hasTangent) {
    if (!hasTangent || abs(anisotropy) <= 1e-4) return normal;
    vec3 t = worldTangent - normal * dot(worldTangent, normal);
    float tLen = dot(t, t);
    if (tLen <= 1e-8) return normal;
    vec3 tangent = t * inversesqrt(tLen);
    vec3 bitangent = cross(normal, tangent);
    vec3 anisoDir = anisotropy >= 0.0 ? bitangent : tangent;
    vec3 anisoTangent = cross(anisoDir, view);
    float aLen = dot(anisoTangent, anisoTangent);
    if (aLen <= 1e-8) return normal;
    vec3 anisoNormal = cross(anisoTangent * inversesqrt(aLen), anisoDir);
    float bendFactor = clamp(abs(anisotropy) * clamp(5.0 * roughness, 0.0, 1.0), 0.0, 1.0);
    vec3 blended = mix(normal, anisoNormal, bendFactor);
    float bLen = dot(blended, blended);
    return bLen > 1e-8 ? blended * inversesqrt(bLen) : normal;
}

vec3 lfx_translucent_slab_environment(vec3 position, vec3 normal, vec3 view, vec3 diffuseColor,
                                      vec3 f0, vec3 f90, float roughness, float materialAo,
                                      float anisotropy, vec3 worldTangent, bool hasTangent,
                                      vec3 fuzzColor, float fuzzAmount, float fuzzRoughness,
                                      vec3 transmittanceAbove, float coverageAbove) {
    roughness = clamp(roughness, 0.02, 1.0);
    float noV = clamp(abs(dot(normal, view)) + 1e-5, 0.0, 1.0);
    float fuzzA = clamp(fuzzAmount, 0.0, 1.0);
    float fuzzAlbedo = fuzzA > 0.0 ? lfx_slab_sheen_albedo(noV, fuzzRoughness) : 0.0;
    float fuzzAtten = 1.0 - fuzzA * fuzzAlbedo;
    vec3 envNormal = lfx_slab_aniso_env_normal(normal, view, roughness, anisotropy, worldTangent, hasTangent);
    vec3 baseEnv = lfx_translucent_surface_environment(position, envNormal, view, diffuseColor,
            diffuseColor * fuzzAtten, f0 * fuzzAtten, roughness, vec3(0.0), materialAo, normal, false);
    vec3 fuzzEnv = vec3(0.0);
    if (fuzzA > 0.0) {
        vec3 unusedIrradiance;
        vec3 fuzzIndirect = lfx_scene_indirect_diffuse(position, normal, fuzzColor, fuzzColor * (fuzzA * fuzzAlbedo),
                clamp(materialAo, 0.0, 1.0), false, vec3(0.0), unusedIrradiance);
        fuzzEnv = fuzzIndirect;
    }
    vec3 lightThru = mix(vec3(1.0), clamp(transmittanceAbove, 0.0, 1.0), clamp(coverageAbove, 0.0, 1.0));
    return (baseEnv + fuzzEnv) * lightThru;
}
