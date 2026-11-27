void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    float r = length(uv);
    float ringDist = abs(r - 0.68);
    float core = smoothstep(0.11, 0.035, ringDist);
    float halo = exp(-ringDist * 11.0) * 0.38;
    float intensity = core * 1.05 + halo;
    if (intensity < 0.015) discard;
    vec3 col = mix(vec3(0.72, 0.86, 1.0), vec3(1.25, 1.28, 1.32), core);
    fragColor = vec4(col * vColor.rgb * intensity, clamp(intensity * vColor.a, 0.0, 1.0));
}
