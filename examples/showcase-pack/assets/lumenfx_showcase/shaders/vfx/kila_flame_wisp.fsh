float hash21(vec2 p) {
    p = fract(p * vec2(127.1, 311.7)); p += dot(p, p + 19.19); return fract(p.x * p.y);
}
float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x),
               mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y);
}
float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.05 + vec2(3.1, 1.7); a *= 0.5; }
    return v;
}
void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    vec2 q = uv; q.x *= 1.15;
    float t = uTime * 2.5;
    float warp = fbm(q * 3.0 - vec2(t * 0.45, t));
    float d = length(q + vec2((warp - 0.5) * 0.55, (warp - 0.5) * 0.45));
    float flame = clamp(1.0 - d * 1.32 + (warp - 0.40) * 0.62, 0.0, 1.3);
    if (flame < 0.08) discard;
    vec3 red = vec3(0.78, 0.14, 0.02);
    vec3 orange = vec3(1.12, 0.48, 0.06);
    vec3 whiteHot = vec3(1.35, 1.22, 0.85);
    vec3 col = mix(red, orange, smoothstep(0.10, 0.55, flame));
    col = mix(col, whiteHot, smoothstep(0.62, 1.05, flame));
    float alpha = smoothstep(0.08, 0.35, flame) * vColor.a;
    fragColor = vec4(col * vColor.rgb, alpha);
}
