#moj_import <lumenfx:scene_velocity.glsl>
layout(std140) uniform DecalReprojectionView {
    mat4 DecalClipToPreviousClip;
    vec4 DecalNormalThresholds;
};
uniform sampler2D DecalPreviousNormal;
uniform sampler2D DecalEarlyVelocity;

vec3 lfx_decal_reproject_normal(vec2 uv, ivec2 pixel, float deviceZ, vec3 faceted) {
    ivec2 extent = textureSize(DecalEarlyVelocity, 0);
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, extent))) return faceted;
    vec4 encoded = texelFetch(DecalEarlyVelocity, pixel, 0);
    vec2 previousUv;
    if (encoded.x > 0.0) {
        vec2 velocity = lfx_decode_scene_velocity(encoded).xy;
        previousUv = uv - velocity * vec2(0.5, -0.5);
    } else {
        float clipZ = DecalNormalThresholds.z > 0.5 ? deviceZ : deviceZ * 2.0 - 1.0;
        vec4 previousClip = DecalClipToPreviousClip * vec4(uv * 2.0 - 1.0, clipZ, 1.0);
        if (previousClip.w <= 0.0) return faceted;
        previousUv = previousClip.xy / previousClip.w * 0.5 + 0.5;
    }
    if (any(lessThan(previousUv, vec2(0.0))) || any(greaterThanEqual(previousUv, vec2(1.0)))) return faceted;
    vec4 sampleNormal = textureLod(DecalPreviousNormal, previousUv, 0.0);
    vec3 decoded = sampleNormal.rgb * 2.0 - 1.0;
    if (sampleNormal.a <= 0.0 || dot(decoded, decoded) == 0.0) return faceted;
    vec3 historyNormal = normalize(decoded);
    float agreement = clamp(dot(historyNormal, faceted), 0.0, 1.0);
    float influence = clamp((agreement - DecalNormalThresholds.x) * DecalNormalThresholds.y, 0.0, 1.0);
    return normalize(mix(faceted, historyNormal, influence));
}
