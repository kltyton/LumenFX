vec3 lfx_decal_base_color(vec3 baseColor, vec4 decalColor) {
    return baseColor * decalColor.a + decalColor.rgb;
}

vec3 lfx_decal_world_normal(vec3 worldNormal, vec4 decalNormal) {
    vec3 premultiplied = decalNormal.rgb * 2.0 - 256.0 / 255.0;
    return normalize(worldNormal * decalNormal.a + premultiplied);
}

float lfx_decal_screen_occlusion(float screenOcclusion, float decalOcclusion) {
    return screenOcclusion * decalOcclusion;
}
