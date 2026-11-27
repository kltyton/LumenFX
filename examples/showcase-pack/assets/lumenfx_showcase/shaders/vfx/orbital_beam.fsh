void main() {
    float x = abs(vUv.x * 2.0 - 1.0);
    float core = exp(-x * x * 36.0) * 1.25;
    float sheath = exp(-x * 5.2) * 0.62;
    float wave = 0.55 + 0.45 * sin(vUv.y * 34.0 + uTime * 11.0 + x * 6.0);
    float fadeEnds = smoothstep(0.0, 0.03, vUv.y) * smoothstep(1.0, 0.82, vUv.y);
    float intensity = (core + sheath * wave) * fadeEnds;
    if (intensity < 0.015) discard;
    vec3 violet = vec3(0.55, 0.18, 1.15);
    vec3 cyan = vec3(0.18, 0.96, 1.32);
    vec3 whiteHot = vec3(1.38, 1.48, 1.55);
    vec3 col = mix(violet, cyan, smoothstep(0.75, 0.18, x));
    col = mix(col, whiteHot, clamp(core * 0.65, 0.0, 1.0));
    fragColor = vec4(col * intensity, clamp(intensity * vColor.a, 0.0, 1.0));
}
