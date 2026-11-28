#version 330
#moj_import <lumenfx:material_fog.glsl>

layout(std140) uniform EffectFrame {
    vec4 EffectClock;        // EffectClock.x = elapsed world seconds
    vec4 MaterialParameters; // x: ring radius (default 0.72), y: ring width (default 0.09), z: noise amp (default 0.15), w: hdr boost (default 1.35)
};

in vec2 effectUV;
in vec2 effectUV1;
in vec4 effectColor;
in vec4 effectParticle; // (normalizedAge, randomSeed01, lifetimeSeconds, birthTimeSeconds)
in vec4 effectCustom;
out vec4 fragColor;

float lfx_hash21(vec2 p) {
    p = fract(p * vec2(127.1, 311.7));
    p += dot(p, p + 19.19);
    return fract(p.x * p.y);
}

float lfx_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(lfx_hash21(i), lfx_hash21(i + vec2(1.0, 0.0)), f.x),
        mix(lfx_hash21(i + vec2(0.0, 1.0)), lfx_hash21(i + vec2(1.0, 1.0)), f.x),
        f.y
    );
}

void main() {
    vec2 p = effectUV * 2.0 - 1.0;
    float r = length(p);
    if (r > 0.99) discard;

    float age = clamp(effectParticle.x, 0.0, 1.0);
    float seed = effectParticle.y * 17.31;
    float time = EffectClock.x;

    float ringRadius = MaterialParameters.x > 0.01 ? clamp(MaterialParameters.x, 0.15, 0.92) : mix(0.25, 0.86, 1.0 - pow(1.0 - age, 2.5));
    float ringWidth = MaterialParameters.y > 0.005 ? MaterialParameters.y : 0.09;
    float noiseAmp = MaterialParameters.z > 0.0 ? MaterialParameters.z : 0.14;
    float hdrBoost = MaterialParameters.w > 0.01 ? MaterialParameters.w : 1.35;

    float angle = atan(p.y, p.x);
    float warp = lfx_noise(vec2(angle * 2.5 + seed, r * 4.5 - time * 1.8));
    float dist = abs(r - ringRadius + (warp - 0.5) * noiseAmp);
    float ring = exp(-pow(dist / max(ringWidth, 0.015), 2.0));

    // Inner Fresnel shimmer
    float z = sqrt(max(0.0, 1.0 - r * r));
    float fresnel = pow(max(0.0, 1.0 - z), 3.0) * 0.35;

    // Clean radial dissolve near end of lifetime
    float dissolveMask = smoothstep(age * 0.95 - 0.15, age * 0.95 + 0.25, warp + 0.35);
    float outerFade = smoothstep(0.99, 0.86, r);
    float intensity = (ring + fresnel) * outerFade * mix(1.0, dissolveMask, smoothstep(0.4, 1.0, age));
    float alpha = clamp(intensity * effectColor.a, 0.0, 1.0);
    if (alpha < 0.005) discard;

    // White-hot ridge shifting into particle tint
    vec3 coreColor = mix(effectColor.rgb, vec3(1.0, 0.98, 0.95), clamp(ring * 0.75, 0.0, 1.0));
    vec4 outColor = vec4(coreColor * intensity * hdrBoost, alpha);
    fragColor = lfx_apply_material_fog(outColor);
}
