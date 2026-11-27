void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    float r = length(uv);
    if (r > 0.98) discard;
    float ang = atan(uv.y, uv.x);
    float a1 = ang + uTime * 0.6;
    float a2 = ang - uTime * 0.9;
    float outerRing = smoothstep(0.028, 0.007, abs(r - 0.90));
    float midRing = smoothstep(0.024, 0.006, abs(r - 0.72));
    float innerRing = smoothstep(0.020, 0.006, abs(r - 0.42));
    float runeBand = smoothstep(0.73, 0.75, r) * smoothstep(0.89, 0.87, r);
    float ticks = smoothstep(0.45, 0.85, abs(sin(a1 * 12.0))) * runeBand * 0.85;
    float tri1 = abs(r * cos(mod(a2, 2.094395) - 1.047197) - 0.36);
    float tri2 = abs(r * cos(mod(a2 + 1.047197, 2.094395) - 1.047197) - 0.36);
    float hexagram = (smoothstep(0.022, 0.006, tri1) + smoothstep(0.022, 0.006, tri2)) * step(r, 0.73);
    float coreGlow = exp(-r * 6.5) * 0.55;
    float sigil = clamp(outerRing + midRing + innerRing + ticks + hexagram, 0.0, 1.15);
    float total = sigil + coreGlow;
    if (total < 0.02) discard;
    vec3 gold = vec3(1.18, 0.85, 0.18);
    vec3 cyan = vec3(0.24, 0.98, 1.28);
    vec3 col = mix(gold, cyan, clamp(hexagram * 0.65 + innerRing * 0.85, 0.0, 1.0));
    col += vec3(1.1, 0.95, 0.65) * coreGlow * 0.45;
    fragColor = vec4(col * total * 0.95, clamp(total * vColor.a, 0.0, 1.0));
}
