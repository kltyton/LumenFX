float hash21(vec2 p) {
    p = fract(p * vec2(234.34, 435.345)); p += dot(p, p + 34.23); return fract(p.x * p.y);
}
float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x),
               mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y);
}
float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.15 + vec2(1.7, 9.2); a *= 0.5; }
    return v;
}
void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    float r = length(uv);
    if (r > 0.98) discard;
    float falloff = smoothstep(0.98, 0.22, r);
    float t = floor(uTime * 8.0) * 0.37 + uTime * 0.35;
    float n1 = abs(fbm(uv * 3.8 + vec2(t, -t * 0.7)) - 0.5);
    float n2 = abs(fbm(uv * 5.2 + vec2(-t * 1.1, t * 0.8) + 3.1) - 0.5);
    float bolt1 = pow(clamp(1.0 - n1 * 12.5, 0.0, 1.0), 3.4);
    float bolt2 = pow(clamp(1.0 - n2 * 13.5, 0.0, 1.0), 3.6);
    float plasma = (bolt1 + bolt2 * 0.8) * falloff;
    float mist = exp(-r * 3.2) * 0.22 * fbm(uv * 2.8 + t * 0.25);
    float energy = plasma * 1.25 + mist;
    if (energy < 0.012) discard;
    vec3 col = mix(vec3(0.14, 0.42, 1.0), vec3(0.85, 0.96, 1.35), clamp(plasma * 1.2, 0.0, 1.0));
    fragColor = vec4(col * vColor.rgb * energy, clamp(energy * vColor.a, 0.0, 1.0));
}
