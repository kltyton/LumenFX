float hash21(vec2 p) {
    p = fract(p * vec2(127.1, 311.7)); p += dot(p, p + 19.19); return fract(p.x * p.y);
}
float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x),
               mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y);
}
void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    float r = length(uv);
    if (r > 0.98) discard;
    float z = sqrt(max(0.0, 1.0 - r * r));
    float fresnel = pow(1.0 - z, 2.5);
    float rimLine = smoothstep(0.86, 0.96, r) * smoothstep(0.99, 0.95, r);
    float swirl = noise(uv * 4.5 + vec2(uTime * 0.4, -uTime * 0.3)) * 0.45
                + noise(uv * 9.0 - vec2(uTime * 0.5, uTime * 0.35)) * 0.22;
    float body = 0.28 + 0.26 * swirl + fresnel * 0.95 + rimLine * 0.75;
    vec3 deepCyan = vec3(0.08, 0.48, 0.92);
    vec3 brightCyan = vec3(0.55, 0.95, 1.25);
    vec3 col = mix(deepCyan, brightCyan, clamp(fresnel * 1.1 + rimLine, 0.0, 1.0));
    float alpha = clamp((0.40 + fresnel * 0.56) * smoothstep(0.98, 0.92, r), 0.0, 0.96) * vColor.a;
    fragColor = vec4(col * vColor.rgb * body, alpha);
}
