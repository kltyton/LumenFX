#moj_import <lumenfx:decal_uniforms.glsl>
#moj_import <lumenfx:scene_surface.glsl>
#ifdef LFX_DECAL_NORMAL_REPROJECTION
#moj_import <lumenfx:decal/normal_reprojection.glsl>
#endif
uniform sampler2D SceneDepthSampler;
#ifdef LFX_DECAL_CUSTOM_DEPTH
uniform sampler2D DecalCustomDepth;
#endif
#ifdef LFX_DECAL_CUSTOM_STENCIL
uniform usampler2D DecalCustomStencil;
#endif
#ifdef LFX_DECAL_CAPTURED_SURFACE
uniform sampler2D DecalSurfaceMask;
#endif
flat in vec3 decalFaceNormal;

vec2 effectUV, effectUV1;
vec4 effectColor = vec4(1.0), effectParticle = vec4(0.0), effectCustom = vec4(0.0);
vec4 effectCustom1 = vec4(0.0), effectCustom2 = vec4(0.0), effectCustom3 = vec4(0.0);
float effectDynamicValid = 0.0;
vec3 effectPosition, effectParticleCenter = vec3(0.0);
vec4 effectRibbonVelocity = vec4(0.0);
vec2 effectRibbonSize = vec2(0.0);
vec3 lfxDecalLocalPosition, lfxDecalWorldPosition, lfxDecalLightVector;
float lfxDecalPhysicalDepth;

#ifndef LFX_DECAL_STAGE
#define LFX_DECAL_STAGE 0
#endif
#if LFX_DECAL_STAGE == 0
layout(location = 0) out vec4 decalBaseColor;
layout(location = 1) out vec4 decalNormal;
layout(location = 2) out vec4 decalSurface;
#else
layout(location = 0) out vec4 fragColor;
#ifdef LFX_DECAL_CAPTURED_SURFACE
uniform sampler2D DecalSceneNormal;
uniform sampler2D DecalSceneGeometry;
#endif
#endif

float lfx_linear_depth(float depth) {
    return depth * DepthTransform.x + DepthTransform.y + 1.0 / (depth * DepthTransform.z - DepthTransform.w);
}

float lfx_pixel_depth() { return lfx_linear_depth(lfxDecalPhysicalDepth); }

vec2 lfx_depth_viewport_uv() {
    vec2 uv = gl_FragCoord.xy * DecalViewport.xy;
    return vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y);
}

float lfx_scene_depth() { return lfx_linear_depth(lfxDecalPhysicalDepth); }

float lfx_decal_depth_at(vec2 uv) {
#ifdef LFX_DECAL_CAPTURED_SURFACE
    return texture(SceneDepthSampler, uv).a;
#else
    return texture(SceneDepthSampler, uv).r;
#endif
}

vec2 lfx_decal_scene_uv(vec2 viewportUV) {
    return vec2(viewportUV.x, CameraRight.w > 0.5 ? 1.0 - viewportUV.y : viewportUV.y);
}

float lfx_decal_custom_depth(vec2 uv) {
#ifdef LFX_DECAL_CUSTOM_DEPTH
    return lfx_linear_depth(texture(DecalCustomDepth, uv).r);
#else
    return lfx_linear_depth(0.0);
#endif
}

float lfx_decal_custom_stencil(vec2 uv) {
#ifdef LFX_DECAL_CUSTOM_STENCIL
    ivec2 size = textureSize(DecalCustomStencil, 0);
    ivec2 pixel = ivec2(trunc(uv * vec2(size)));
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, size))) return 0.0;
    return float(texelFetch(DecalCustomStencil, pixel, 0).g);
#else
    return 0.0;
#endif
}

float lfx_decal_device_z(ivec2 pixel) {
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, textureSize(SceneDepthSampler, 0)))) return 0.0;
#ifdef LFX_DECAL_CAPTURED_SURFACE
    return texelFetch(SceneDepthSampler, pixel, 0).a;
#else
    return texelFetch(SceneDepthSampler, pixel, 0).r;
#endif
}

float lfx_decal_smaller_depth_delta(float before, float current, float after) {
    float first = current - before, second = after - current;
    return abs(first) < abs(second) ? first : second;
}

vec3 lfx_decal_unproject(vec2 pixel, float depth) {
    vec4 position = DecalPixelToWorld * vec4(pixel, depth, 1.0);
    return position.xyz / position.w;
}

vec4 lfx_decal_scene_world_normal(vec2 uv) {
    ivec2 pixel = ivec2(trunc(uv * vec2(textureSize(SceneDepthSampler, 0))));
    float depth = lfx_decal_device_z(pixel);
#if LFX_DECAL_STAGE != 0 && defined(LFX_DECAL_CAPTURED_SURFACE)
    if (all(greaterThanEqual(pixel, ivec2(0))) && all(lessThan(pixel, textureSize(DecalSceneGeometry, 0)))) {
        vec4 geometry = texelFetch(DecalSceneGeometry, pixel, 0);
        vec3 normal = texelFetch(DecalSceneNormal, pixel, 0).xyz;
        if (geometry.w == depth && dot(normal, normal) > 0.0) {
            mat3 viewToWorld = mat3(DecalPixelToWorld * inverse(DecalPixelToView));
            return vec4(normalize(transpose(inverse(viewToWorld)) * normal).xzy, 0.0);
        }
    }
#endif
    ivec2 right = ivec2(1, 0), down = ivec2(0, int(DecalViewport.w));
    float dx = lfx_decal_smaller_depth_delta(lfx_decal_device_z(pixel - right), depth, lfx_decal_device_z(pixel + right));
    float dy = lfx_decal_smaller_depth_delta(lfx_decal_device_z(pixel - down), depth, lfx_decal_device_z(pixel + down));
    vec2 centre = vec2(pixel) + vec2(0.5);
    vec3 position = lfx_decal_unproject(centre, depth);
    vec3 tangent = lfx_decal_unproject(centre + vec2(right), depth + dx) - position;
    vec3 bitangent = lfx_decal_unproject(centre + vec2(down), depth + dy) - position;
    vec3 faceted = normalize(cross(tangent.xzy, bitangent.xzy));
#ifdef LFX_DECAL_NORMAL_REPROJECTION
    faceted = lfx_decal_reproject_normal(uv, pixel, depth, faceted);
#endif
    return vec4(faceted, LFX_DECAL_STAGE == 0 ? 1.0 : 0.0);
}

float lfx_scene_depth_at(vec2 uv) {
    vec2 hostUV = vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y);
    return lfx_linear_depth(lfx_decal_depth_at(hostUV));
}

vec3 lfx_camera_vector() { return normalize(-lfxDecalWorldPosition).xzy; }

vec3 lfx_decal_local_position(vec2 pixel) {
    float depth = lfx_decal_depth_at(pixel * DecalViewport.xy);
    vec4 local = DecalPixelToLocal * vec4(pixel, depth, 1.0);
    return local.xyz / local.w;
}

vec2 lfx_decal_uv_at(vec2 pixel) { return lfx_decal_local_position(pixel).zy * 0.5 + 0.5; }

vec2 lfx_decal_ddx() {
#if LFX_DECAL_TEXCOORDS
    vec2 delta = vec2(DecalViewport.z, 0.0);
    vec2 a = effectUV - lfx_decal_uv_at(gl_FragCoord.xy - delta);
    vec2 b = lfx_decal_uv_at(gl_FragCoord.xy + delta) - effectUV;
    return dot(a, a) < dot(b, b) ? a : b;
#else
    return vec2(0.0);
#endif
}

vec2 lfx_decal_ddy() {
#if LFX_DECAL_TEXCOORDS
    vec2 delta = vec2(0.0, DecalViewport.w);
    vec2 a = effectUV - lfx_decal_uv_at(gl_FragCoord.xy - delta);
    vec2 b = lfx_decal_uv_at(gl_FragCoord.xy + delta) - effectUV;
    return dot(a, a) < dot(b, b) ? a : b;
#else
    return vec2(0.0);
#endif
}

float lfx_decal_mipmap_level(vec2 textureSize) {
#if LFX_DECAL_TEXCOORDS
    vec2 a = lfx_decal_ddx() * textureSize, b = lfx_decal_ddy() * textureSize;
    return 0.5 * log2(max(dot(a, a), dot(b, b)));
#else
    return 0.0;
#endif
}

vec3 lfx_decal_tangent_normal(vec3 normal) {
    vec3 scaled = vec3(-normal.z, normal.y, normal.x) * DecalInverseScale.xyz;
    return normalize((DecalLocalToWorld * vec4(scaled, 0.0)).xyz);
}

void lfx_initialize_decal() {
#ifdef LFX_DECAL_CAPTURED_SURFACE
    if (texelFetch(DecalSurfaceMask, ivec2(gl_FragCoord.xy), 0).a == 0.0) discard;
#if LFX_DECAL_STAGE != 0
    if (!lfx_surface_receives_decals(texelFetch(SceneDepthSampler, ivec2(gl_FragCoord.xy), 0))) discard;
#endif
#endif
    vec4 farPoint = DecalPixelToWorld * vec4(gl_FragCoord.xy, 0.0, 1.0);
    vec4 nearPoint = DecalPixelToWorld * vec4(gl_FragCoord.xy, 1.0, 1.0);
    vec3 ray = farPoint.xyz * nearPoint.w - nearPoint.xyz * farPoint.w;
    float facing = dot(decalFaceNormal, ray);
    if (DecalOrientationValue.w > 0.5 ? facing <= 0.0 : facing >= 0.0) discard;
    lfxDecalPhysicalDepth = lfx_decal_depth_at(gl_FragCoord.xy * DecalViewport.xy);
    vec4 viewPosition = DecalPixelToView * vec4(gl_FragCoord.xy, lfxDecalPhysicalDepth, 1.0);
    if (viewPosition.w == 0.0) discard;
    effectPosition = viewPosition.xyz / viewPosition.w;
    vec4 pixel = vec4(gl_FragCoord.xy, lfxDecalPhysicalDepth, 1.0);
    vec4 local = DecalPixelToLocal * pixel;
    lfxDecalLocalPosition = local.xyz / local.w;
    if (any(lessThan(lfxDecalLocalPosition, vec3(-1.0))) || any(greaterThan(lfxDecalLocalPosition, vec3(1.0)))) discard;
    vec4 worldPosition = DecalPixelToWorld * pixel;
    lfxDecalWorldPosition = worldPosition.xyz / worldPosition.w;
    lfxDecalLightVector = (lfxDecalLocalPosition * 0.5 + 0.5).zyx;
    effectUV = lfxDecalLightVector.xy;
    effectUV1 = effectUV;
}

void lfx_write_decal(vec3 baseColor, vec3 worldNormal, vec3 material, vec3 emission, float coverage,
                     float backgroundCoverage, float ao, int outputs) {
    float edgeFade = clamp(4.0 - 4.0 * abs(lfxDecalLocalPosition.x), 0.0, 1.0) * DecalFadeParameters.x;
#if LFX_DECAL_STAGE == 0
    float opacity = backgroundCoverage * edgeFade;
    decalBaseColor = vec4(baseColor, (outputs & 1) != 0 ? opacity : 0.0);
    decalNormal = vec4(worldNormal * 0.5 + 128.0 / 255.0, (outputs & 2) != 0 ? opacity : 0.0);
    decalSurface = vec4(material, (outputs & 4) != 0 ? opacity : 0.0);
#elif LFX_DECAL_STAGE == 1
    fragColor = vec4(emission * DecalFadeParameters.z, coverage * edgeFade);
#elif LFX_DECAL_STAGE == 2
    float opacity = coverage * edgeFade;
    fragColor = vec4(vec3(mix(1.0, ao, opacity)), opacity);
#endif
}

void lfx_write_decal(vec3 baseColor, vec3 worldNormal, vec3 material, vec3 emission, float opacity, float ao, int outputs) {
    lfx_write_decal(baseColor, worldNormal, material, emission, opacity, opacity, ao, outputs);
}
