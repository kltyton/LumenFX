void main() {
    vec4 tex = texture(uTex, vUv);
    float lum = tex.a;
    if (lum < 0.02) discard;
    float lineCore = pow(lum, 1.6);
    vec3 col = mix(vColor.rgb * 0.85, vec3(0.95, 1.18, 1.32), lineCore * 0.58);
    fragColor = vec4(col * lum * 0.95, clamp(lum * vColor.a, 0.0, 1.0));
}
