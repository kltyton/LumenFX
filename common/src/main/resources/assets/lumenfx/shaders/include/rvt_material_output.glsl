#moj_import <lumenfx:srgb.glsl>

// Material attributes and MRT channels follow the source runtime virtual texture pass.
struct LfxRvtMaterial {
    vec3 baseColor;
    float specular;
    float roughness;
    vec3 normal;
    float height;
    float opacity;
    float mask;
    float displacement;
    vec4 mask4;
};

vec3 lfx_rvt_ycocg(vec3 rgb) {
    return vec3((rgb.r + 2.0 * rgb.g + rgb.b) * 0.25,
                (2.0 * rgb.r - 2.0 * rgb.b) * 0.25 + 128.0 / 255.0,
                (-rgb.r + 2.0 * rgb.g - rgb.b) * 0.25 + 128.0 / 255.0);
}

vec4 lfx_rvt_material_raster(LfxRvtMaterial surface, int material, int layer) {
    float normalSquared = dot(surface.normal, surface.normal);
    vec3 packedValue = (normalSquared > 0.0 ? surface.normal * inversesqrt(normalSquared) : vec3(0.0))
        * (127.0 / 255.0) + (127.0 / 255.0);
    if (material == 0) return vec4(surface.baseColor, 1.0) * surface.opacity;
    if (material == 1)
        return (layer == 0 ? vec4(surface.mask4.xyz, 1.0) : vec4(surface.mask4.w, 0.0, 0.0, 1.0)) * surface.opacity;
    if (material == 2)
        return (layer == 0 ? vec4(linearToSrgb(surface.baseColor), 1.0)
            : vec4(packedValue.x, surface.roughness, packedValue.y, 1.0)) * surface.opacity;
    if (material >= 3 && material <= 5) {
        if (layer == 0) return vec4(surface.baseColor, 1.0) * surface.opacity;
        if (layer == 1) return vec4(packedValue.xy, surface.mask, 1.0) * surface.opacity;
        return vec4(surface.specular, surface.roughness, packedValue.z, 1.0) * surface.opacity;
    }
    if (material == 6) return vec4(surface.height, 0.0, 0.0, 1.0);
    return vec4(surface.displacement, 0.0, 0.0, 1.0) * surface.opacity;
}
