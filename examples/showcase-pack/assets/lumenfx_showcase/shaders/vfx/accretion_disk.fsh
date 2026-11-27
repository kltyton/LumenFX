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
    if (r < 0.25 || r > 0.98) discard;
    float ang = atan(uv.y, uv.x);
    float spiral = ang + 8.5 / max(r, 0.24) - uTime * 2.6;
    float bands = 0.45 + 0.55 * sin(r * 34.0 - uTime * 3.0);
    float streaks = 0.45 + 0.55 * sin(spiral * 4.0) * noise(vec2(r * 9.0, ang * 3.0 - uTime));
    float innerFade = smoothstep(0.25, 0.34, r);
    float outerFade = smoothstep(0.98, 0.52, r);
    float energy = (0.35 + bands * 0.42 + streaks * 0.65) * innerFade * outerFade;
    vec3 innerHot = vec3(1.38, 1.05, 0.55);
    vec3 midGold = vec3(1.08, 0.38, 0.07);
    vec3 outerCrimson = vec3(0.58, 0.09, 0.24);
    vec3 col = mix(innerHot, midGold, smoothstep(0.28, 0.58, r));
    col = mix(col, outerCrimson, smoothstep(0.58, 0.92, r));
    fragColor = vec4(col * energy, clamp(energy * vColor.a, 0.0, 1.0));
}
