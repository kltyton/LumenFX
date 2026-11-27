float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x),
               mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y);
}
void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    float t = uTime * 2.0;
    float d = length(uv);
    float n = noise(uv * 3.0 - vec2(0.0, t)) * 0.30 + noise(uv * 6.0 - vec2(0.0, t * 1.3)) * 0.14;
    float mask = 1.0 - d + (n - 0.22) * 0.55;
    if (mask < 0.10) discard;
    vec3 col;
    if (mask > 0.68) col = vec3(1.15, 1.05, 0.72);
    else if (mask > 0.44) col = vec3(1.05, 0.55, 0.08);
    else if (mask > 0.24) col = vec3(0.75, 0.15, 0.03);
    else col = vec3(0.14, 0.02, 0.01);
    if (length(uv - vec2(0.18, -0.14)) < 0.11 || length(uv - vec2(-0.22, 0.12)) < 0.075)
        col = vec3(0.04, 0.01, 0.01);
    float alpha = smoothstep(0.10, 0.16, mask) * vColor.a;
    fragColor = vec4(col * vColor.rgb, alpha);
}
