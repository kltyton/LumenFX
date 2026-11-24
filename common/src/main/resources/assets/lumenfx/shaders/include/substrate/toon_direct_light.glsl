#moj_import <lumenfx:substrate/toon_profile.glsl>

uniform sampler2D LfxToonGgxMaxEnergy;

float lfx_toon_ggx_max_energy(float roughness, float anisotropy, float noV) {
    vec2 uv = vec2(roughness, abs(anisotropy)) * (31.0 / 32.0) + (0.5 / 32.0);
    vec4 energy = textureLod(LfxToonGgxMaxEnergy, uv, 0.0);
    vec4 weights = 1.0 - clamp(abs(noV - vec4(0.0, 0.3333, 0.6666, 1.0)) / 0.3333, vec4(0.0), vec4(1.0));
    return LFX_BRDF_PI * dot(energy, weights);
}

float lfx_toon_ggx(float roughness, vec3 n, vec3 v, vec3 l, vec4 tangentAnisotropy) {
    float noV = dot(n, v);
    float noL = dot(n, l);
    vec3 h = v + l;
    float halfLength2 = dot(h, h);
    if (halfLength2 <= 0.0) return 0.0;
    h *= inversesqrt(halfLength2);
    float noH = clamp(dot(n, h), 0.0, 1.0);
    float alpha = roughness * roughness;
    float anisotropy = tangentAnisotropy.w;
    float distribution;
    if (anisotropy != 0.0) {
        vec3 tangent = tangentAnisotropy.xyz;
        vec3 bitangent = normalize(cross(n, tangent));
        vec2 axis = max(alpha * vec2(1.0 + anisotropy, 1.0 - anisotropy), vec2(0.001));
        float area = axis.x * axis.y;
        vec3 slope = vec3(axis.y * dot(tangent, h), axis.x * dot(bitangent, h), area * noH);
        float ratio = area / dot(slope, slope);
        distribution = area * ratio * ratio / LFX_BRDF_PI;
    } else {
        float alpha2 = alpha * alpha;
        float d = (noH * alpha2 - noH) * noH + 1.0;
        distribution = alpha2 / (LFX_BRDF_PI * d * d);
    }
    return distribution * lfx_toon_ggx_max_energy(roughness, anisotropy, noV);
}
