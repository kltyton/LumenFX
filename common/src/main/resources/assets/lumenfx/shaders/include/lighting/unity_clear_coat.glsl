#ifndef LFX_UNITY_CLEAR_COAT_INCLUDED
#define LFX_UNITY_CLEAR_COAT_INCLUDED

// URP Complex Lit uses a dielectric upper layer with an IOR of 1.5.
vec4 lfx_unity_coat_base(vec4 base, float mask) {
    float ieta = mix(1.0, 2.0 / 3.0, mask);
    float r2 = base.w * base.w;
    r2 *= r2;
    // Combined RoughnessToVariance and VarianceToRoughness, including the smooth limit at zero.
    base.w = sqrt(sqrt(r2 / ((1.0 - r2) * ieta * ieta + r2)));
    vec3 underCoat = clamp(base.rgb * (base.rgb * 0.526868 + 0.529324) - 0.0482256, 0.0, 1.0);
    base.rgb = mix(base.rgb, underCoat, mask);
    return base;
}

float lfx_unity_coat_fresnel_power(float noV) {
    float f = 1.0 - clamp(noV, 0.0, 1.0);
    f *= f;
    return f * f;
}

float lfx_unity_coat_transmission(float mask, float fresnelPower) {
    return 1.0 - mask * (0.04 + 0.96 * fresnelPower);
}

float lfx_unity_coat_specular(float pRoughness, float noH, float loH) {
    float roughness = max(pRoughness * pRoughness, 0.0078125);
    float roughnessSquared = max(roughness * roughness, 0.00006103515625);
    float d = noH * noH * (roughnessSquared - 1.0) + 1.00001;
    return roughnessSquared / (d * d * max(0.1, loH * loH) * (4.0 * roughness + 2.0));
}

vec3 lfx_unity_coat_direct(vec3 diffuse, vec4 base, vec2 coat, vec3 n, vec3 v, vec3 l, vec2 scales) {
    vec3 h = l + v;
    h *= inversesqrt(max(dot(h, h), 1e-12));
    float noH = clamp(dot(n, h), 0.0, 1.0);
    float loH = clamp(dot(l, h), 0.0, 1.0);
    float transmission = lfx_unity_coat_transmission(coat.x, lfx_unity_coat_fresnel_power(dot(n, v)));
    vec3 baseSpecular = base.rgb * lfx_unity_coat_specular(base.w, noH, loH);
    float topSpecular = 0.04 * coat.x * lfx_unity_coat_specular(coat.y, noH, loH);
    return (diffuse * scales.x + baseSpecular * scales.y) * transmission + topSpecular * scales.y;
}

vec3 lfx_unity_coat_environment_brdf(vec4 surface, float grazing, float fresnelPower) {
    float roughness = max(surface.w * surface.w, 0.0078125);
    float reduction = 1.0 / (1.0 + max(roughness * roughness, 0.00006103515625));
    return reduction * mix(surface.rgb, vec3(grazing), fresnelPower);
}

#endif
