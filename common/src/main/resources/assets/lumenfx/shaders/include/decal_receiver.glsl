#moj_import <lumenfx:decal_response.glsl>
uniform sampler2D LfxDBufferColor;
uniform sampler2D LfxDBufferNormal;
uniform sampler2D LfxDBufferMaterial;
uniform sampler2D LfxDecalAmbientOcclusion;

void lfx_apply_decal_material(ivec2 pixel, int responseMask, inout vec3 baseColor, inout vec3 worldNormal,
                            inout vec3 subsurfaceColor, inout float metallic, inout float specular, inout float roughness) {
    if ((responseMask & 1) != 0) {
        vec4 color = texelFetch(LfxDBufferColor, pixel, 0);
        baseColor = lfx_decal_base_color(baseColor, color);
        subsurfaceColor *= color.a;
    }
    if ((responseMask & 2) != 0) {
        vec4 normal = texelFetch(LfxDBufferNormal, pixel, 0);
        worldNormal = lfx_decal_world_normal(worldNormal, normal);
    }
    if ((responseMask & 4) != 0) {
        vec4 material = texelFetch(LfxDBufferMaterial, pixel, 0);
        metallic = metallic * material.a + material.r;
        specular = specular * material.a + material.g;
        roughness = roughness * material.a + material.b;
    }
}

float lfx_decal_ambient_occlusion(ivec2 pixel, float screenAmbientOcclusion) {
    return lfx_decal_screen_occlusion(screenAmbientOcclusion, texelFetch(LfxDecalAmbientOcclusion, pixel, 0).r);
}
