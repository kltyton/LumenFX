float hash21(vec2 p) {
    p = fract(p * vec2(127.1, 311.7)); p += dot(p, p + 19.19); return fract(p.x * p.y);
}
float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x),
               mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y);
}
void main() {
    float distFade = pow(clamp(1.0 - vUv.y, 0.0, 1.0), 1.35) * smoothstep(0.0, 0.05, vUv.y);
    float sideFade = smoothstep(0.0, 0.26, vUv.x) * smoothstep(1.0, 0.74, vUv.x);
    float rays = 0.55 + 0.45 * sin(vUv.x * 22.0 + 0.4) * sin(vUv.x * 11.0 - 0.3);
    float mist = 0.82 + 0.18 * noise(vec2(vUv.x * 5.0, vUv.y * 4.0 - uTime * 0.2));
    float soft = lfx_soft_particle_alpha();
    float intensity = distFade * sideFade * rays * mist * soft * 0.55;
    if (intensity < 0.006) discard;
    vec3 col = mix(vec3(1.05, 0.78, 0.42), vec3(1.25, 1.12, 0.82), pow(1.0 - vUv.y, 2.0));
    fragColor = vec4(col * vColor.rgb * intensity, clamp(intensity * vColor.a, 0.0, 1.0));
}
