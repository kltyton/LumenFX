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
    float ang = atan(uv.y, uv.x);
    float twist = ang + 6.2 * (1.0 - r) - uTime * 2.2;
    float arms = sin(twist * 5.0) * 0.5 + 0.5;
    float n = noise(vec2(cos(twist) * 3.0 + r * 4.0, sin(twist) * 3.0 - uTime * 0.7));
    float spiral = pow(clamp(arms * 0.65 + n * 0.50, 0.0, 1.0), 2.1);
    float core = exp(-r * 5.2) * 1.05;
    float edgeMask = smoothstep(0.98, 0.68, r);
    float energy = (spiral * 0.92 + core) * edgeMask;
    if (energy < 0.02) discard;
    vec3 deepPurple = vec3(0.32, 0.08, 0.72);
    vec3 brightViolet = vec3(0.75, 0.35, 1.05);
    vec3 whiteCore = vec3(1.25, 1.15, 1.35);
    vec3 col = mix(deepPurple, brightViolet, clamp(spiral, 0.0, 1.0));
    col = mix(col, whiteCore, clamp(core * 0.8 + pow(spiral, 3.2) * 0.55, 0.0, 1.0));
    fragColor = vec4(col * vColor.rgb * energy, clamp(energy * vColor.a, 0.0, 1.0));
}
