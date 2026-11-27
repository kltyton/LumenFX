void main() {
    vec2 edge = min(vUv, 1.0 - vUv);
    float facetRim = smoothstep(0.11, 0.0, min(edge.x, edge.y));
    float tipGlow = smoothstep(0.55, 1.0, vUv.y);
    vec3 deepIce = vec3(0.09, 0.46, 0.88);
    vec3 brightFrost = vec3(0.62, 1.08, 1.35);
    vec3 col = mix(deepIce, brightFrost, clamp(facetRim * 0.82 + tipGlow * 0.55, 0.0, 1.0));
    float alpha = clamp(0.58 + facetRim * 0.34 + tipGlow * 0.18, 0.0, 0.92) * vColor.a;
    fragColor = vec4(col * vColor.rgb, alpha);
}
