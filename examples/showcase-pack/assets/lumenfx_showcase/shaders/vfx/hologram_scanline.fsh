void main() {
    // Crisp horizontal hologram scanline stripes across fox height
    float stripe = smoothstep(0.15, 0.55, sin(vWorldPos.y * 48.0 - uTime * 3.0) * 0.5 + 0.5);
    float scanBand = 0.28 + 0.72 * stripe;
    // Crisp wireframe-like edge highlight on each box face
    vec2 edgeD = min(vUv, 1.0 - vUv);
    float edgeGlow = smoothstep(0.12, 0.0, min(edgeD.x, edgeD.y)) * 0.65;
    vec3 deepTeal = vec3(0.06, 0.58, 0.72);
    vec3 brightCyan = vec3(0.45, 0.98, 1.12);
    vec3 col = mix(deepTeal, brightCyan, clamp(edgeGlow + stripe * 0.55, 0.0, 1.0));
    float alpha = clamp((0.28 + scanBand * 0.42 + edgeGlow * 0.35) * vColor.a, 0.0, 0.92);
    fragColor = vec4(col * vColor.rgb * (0.58 + edgeGlow * 0.42), alpha);
}
