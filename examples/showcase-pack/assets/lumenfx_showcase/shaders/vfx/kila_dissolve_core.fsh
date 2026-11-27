float hash21(vec2 p) {
    p = fract(p * vec2(127.1, 311.7)); p += dot(p, p + 34.5); return fract(p.x * p.y);
}
float voronoi(vec2 x) {
    vec2 n = floor(x); vec2 f = fract(x); float md = 8.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(float(i), float(j));
            vec2 o = vec2(hash21(n + g), hash21(n + g + 17.3));
            o = 0.5 + 0.42 * sin(uTime * 1.2 + 6.2831 * o);
            vec2 r = g + o - f;
            md = min(md, dot(r, r));
        }
    }
    return sqrt(md);
}
void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    float r = length(uv);
    float orbCore = exp(-r * r * 14.0) * 1.25;
    float orbHalo = exp(-r * 4.2) * 0.68;
    vec2 auv = abs(uv);
    float cornerBox = max(auv.x, auv.y);
    float cornerMask = smoothstep(0.60, 0.90, cornerBox) * smoothstep(1.0, 0.94, cornerBox)
                     * smoothstep(0.30, 0.54, min(auv.x, auv.y));
    float cell = voronoi(uv * 8.0);
    float dissolveEdge = smoothstep(0.32, 0.52, cell) * cornerMask;
    float glowRim = smoothstep(0.26, 0.36, cell) * (1.0 - smoothstep(0.36, 0.54, cell)) * cornerMask;
    vec3 col = mix(vec3(0.75, 0.14, 0.92), vec3(1.25, 1.05, 1.32), clamp(orbCore * 0.75, 0.0, 1.0)) * (orbCore + orbHalo);
    col += vec3(0.95, 0.25, 0.88) * dissolveEdge * 0.95 + vec3(1.3, 0.9, 1.3) * glowRim * 1.1;
    float alpha = clamp((orbCore + orbHalo + dissolveEdge + glowRim) * vColor.a, 0.0, 1.0);
    if (alpha < 0.02) discard;
    fragColor = vec4(col * vColor.rgb, alpha);
}
