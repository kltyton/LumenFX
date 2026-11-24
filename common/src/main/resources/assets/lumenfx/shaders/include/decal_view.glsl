#moj_import <lumenfx:decal_response.glsl>
layout(std140) uniform DecalReceiverView {
    mat4 DecalWorldToViewNormal;
    mat4 DecalViewToWorldNormal;
};

vec3 lfx_decal_view_normal(vec3 normal, vec4 decalNormal) {
    float transmission = length(normal);
    if (transmission == 0.0 || (decalNormal.a == 1.0 && all(equal(decalNormal.rgb, vec3(128.0 / 255.0))))) return normal;
    vec3 worldNormal = normalize(mat3(DecalViewToWorldNormal) * normal);
    worldNormal = lfx_decal_world_normal(worldNormal, decalNormal);
    return normalize(mat3(DecalWorldToViewNormal) * worldNormal) * transmission;
}
