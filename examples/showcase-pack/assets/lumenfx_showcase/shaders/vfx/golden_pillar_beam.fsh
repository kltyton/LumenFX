void main() {
    float x = abs(vUv.x * 2.0 - 1.0);
    float core = exp(-x * x * 55.0);
    float halo = exp(-x * 6.5) * 0.35;
    float fadeY = smoothstep(0.0, 0.06, vUv.y) * smoothstep(1.0, 0.12, vUv.y);
    float beam = (core * 1.1 + halo) * fadeY;
    if (beam < 0.01) discard;
    vec3 col = mix(vec3(1.05, 0.72, 0.25), vec3(1.35, 1.22, 0.88), core);
    fragColor = vec4(col * vColor.rgb * beam, clamp(beam * vColor.a, 0.0, 1.0));
}
