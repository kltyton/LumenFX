void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    float r = length(uv);
    if (r > 0.98) discard;
    float holeMask = 1.0 - smoothstep(0.52, 0.57, r);
    float ring = exp(-pow((r - 0.57) * 18.0, 2.0)) * 1.45;
    float outerHalo = exp(-max(0.0, r - 0.57) * 7.5) * 0.55 * smoothstep(0.98, 0.70, r);
    vec3 ringCol = mix(vec3(1.15, 0.35, 0.06), vec3(1.55, 1.25, 0.78), clamp(ring * 0.7, 0.0, 1.0));
    vec3 col = ringCol * (ring + outerHalo) * (1.0 - holeMask * 0.98);
    float alpha = clamp(holeMask + (ring + outerHalo) * 0.95, 0.0, 1.0) * vColor.a;
    fragColor = vec4(col, alpha);
}
