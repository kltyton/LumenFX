#ifdef LFX_LIGHT_FUNCTION_ATLAS
in vec2 lightFunctionAtlasUv;
const vec4 LightFunctionObjectTile = vec4(0.0);
const vec4 LightFunctionObjectOffset = vec4(0.0);
const vec4 LightFunctionObjectRelative = vec4(0.0);
#elif defined(LFX_LIGHT_FUNCTION_FOG_DIRECTIONAL)
layout(std140) uniform LightFunctionFogView {
    mat4 FogFunctionShadowToWorld;
    mat4 FogFunctionWorldToLight;
    mat4 FogFunctionWorldToClip;
    vec4 FogFunctionParameters;
    vec4 LightFunctionObjectTile;
    vec4 LightFunctionObjectOffset;
    vec4 LightFunctionObjectRelative;
};
uniform sampler2D SceneDepthSampler;
#elif defined(LFX_LIGHT_FUNCTION_VOLUME)
#moj_import <lumenfx:lighting/translucency_volume_grid.glsl>
#ifdef LFX_TLV_DIRECTIONAL_LIGHT
#moj_import <lumenfx:lighting/translucency_volume_directional_light.glsl>
#else
#moj_import <lumenfx:lighting/translucency_volume_local_light.glsl>
#endif
layout(std140) uniform LightFunctionVolumeView {
    mat4 LightFunctionVolumeWorldToLight;
    mat4 LightFunctionVolumeWorldToClip;
    vec4 LightFunctionVolumeDepth;
    vec4 LightFunctionVolumeParameters;
    vec4 LightFunctionObjectTile;
    vec4 LightFunctionObjectOffset;
    vec4 LightFunctionObjectRelative;
};
uniform sampler2D SceneDepthSampler;
#else
layout(std140) uniform LightFunctionView {
    mat4 LightFunctionPixelToLight;
    mat4 LightFunctionPixelToWorld;
    mat4 LightFunctionPixelToView;
    vec4 LightFunctionParameters;
    vec4 LightFunctionFade;
    vec4 LightFunctionViewport;
    vec4 LightFunctionCameraOrigin;
    vec4 LightFunctionViewForward;
    vec4 LightFunctionObjectTile;
    vec4 LightFunctionObjectOffset;
    vec4 LightFunctionObjectRelative;
};
uniform sampler2D SceneDepthSampler;
#endif
layout(location = 0) out vec4 fragColor;

vec2 effectUV, effectUV1;
vec4 effectColor = vec4(1.0), effectParticle = vec4(0.0), effectCustom = vec4(0.0);
vec4 effectCustom1 = vec4(0.0), effectCustom2 = vec4(0.0), effectCustom3 = vec4(0.0);
float effectDynamicValid = 0.0;
vec3 effectPosition, effectParticleCenter = vec3(0.0);
vec4 effectRibbonVelocity = vec4(0.0);
vec2 effectRibbonSize = vec2(0.0);
vec3 lfxLightFunctionTranslatedWorld, lfxLightFunctionVector;
float lfxLightFunctionDepth, lfxLightFunctionPixelDepth;

float lfx_linear_depth(float depth) {
    return depth * DepthTransform.x + DepthTransform.y + 1.0 / (depth * DepthTransform.z - DepthTransform.w);
}
float lfx_pixel_depth() { return lfxLightFunctionPixelDepth; }
vec3 lfx_camera_vector() { return lfxLightFunctionVector; }
#ifdef LFX_LIGHT_FUNCTION_ATLAS
vec2 lfx_depth_viewport_uv() { return vec2(0.0); }
float lfx_scene_depth() { return 0.000001; }
float lfx_scene_depth_at(vec2 uv) { return lfx_scene_depth(); }

void lfx_initialize_light_function() {
    const float pi = 3.14159265358979323846;
    effectUV = effectUV1 = lightFunctionAtlasUv;
    float theta = effectUV.x * (2.0 * pi) - pi;
    float phi = effectUV.y * pi;
    lfxLightFunctionVector = normalize(vec3(cos(theta) * sin(phi), sin(theta) * sin(phi), cos(phi)));
    lfxLightFunctionTranslatedWorld = effectPosition = vec3(0.0);
    lfxLightFunctionDepth = lfxLightFunctionPixelDepth = 0.000001;
}

void lfx_write_light_function(vec3 color) {
#ifdef LFX_LIGHT_FUNCTION_ATLAS_COLOR
    fragColor = vec4(sqrt(max(vec3(0.0), color)), 0.0);
#else
    fragColor = vec4(sqrt(max(0.0, dot(color, vec3(0.3333)))), 0.0, 0.0, 0.0);
#endif
}
#elif defined(LFX_LIGHT_FUNCTION_FOG_DIRECTIONAL)
vec2 lfx_depth_viewport_uv() {
    vec4 clip = FogFunctionWorldToClip * vec4(effectPosition, 1.0);
    return clip.xy / clip.w * vec2(0.5, -0.5) + 0.5;
}
float lfx_scene_depth_at(vec2 uv) {
    vec2 hostUV = vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y);
    return lfx_linear_depth(texture(SceneDepthSampler, hostUV).r);
}
float lfx_scene_depth() { return lfx_scene_depth_at(lfx_depth_viewport_uv()); }

void lfx_initialize_light_function() {
    vec2 shadow = gl_FragCoord.xy * FogFunctionParameters.x * 2.0 - 1.0;
    vec4 world = FogFunctionShadowToWorld * vec4(shadow, 0.000001, 1.0);
    effectPosition = world.xyz / world.w;
    lfxLightFunctionTranslatedWorld = effectPosition.xzy * 100.0;
    vec4 light = FogFunctionWorldToLight * vec4(lfxLightFunctionTranslatedWorld, 1.0);
    lfxLightFunctionVector = light.zyx / light.w;
    effectUV = effectUV1 = lfxLightFunctionVector.xy;
    lfxLightFunctionPixelDepth = 0.000001;
}

void lfx_write_light_function(vec3 color) {
    float viewDistance = length(lfxLightFunctionTranslatedWorld);
    float fade = clamp((FogFunctionParameters.y - viewDistance) / (FogFunctionParameters.y * 0.2), 0.0, 1.0);
    fragColor = vec4(mix(FogFunctionParameters.z, dot(color, vec3(0.3333)), fade), 0.0, 0.0, 0.0);
}
#elif defined(LFX_LIGHT_FUNCTION_VOLUME)
vec2 lfx_depth_viewport_uv() {
    vec4 clip = LightFunctionVolumeWorldToClip * vec4(effectPosition, 1.0);
    return clip.xy / clip.w * vec2(0.5, -0.5) + 0.5;
}
float lfx_scene_depth_at(vec2 uv) {
    vec2 hostUV = vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y);
    return lfx_linear_depth(texture(SceneDepthSampler, hostUV).r);
}
float lfx_scene_depth() { return lfx_scene_depth_at(lfx_depth_viewport_uv()); }

void lfx_initialize_light_function() {
    vec4 cascade = TlvCascade[TlvAtlas.w];
    vec3 voxel = vec3(lfx_tlv_voxel(ivec2(gl_FragCoord.xy))) + 0.5;
    vec3 position = cascade.xyz + voxel * cascade.w;
#ifdef LFX_TLV_DIRECTIONAL_LIGHT
    position -= TlvDirectionalDirection.xzy * (0.5 * cascade.w);
#else
    vec3 toLight = TlvLocalPositionRadius.xyz * 100.0 - position;
    float distanceToLight = length(toLight);
    if (distanceToLight > 0.0) position -= toLight / distanceToLight * (0.5 * cascade.w);
#endif
    lfxLightFunctionTranslatedWorld = position.xzy;
    effectPosition = position * 0.01;
    lfxLightFunctionPixelDepth = dot(LightFunctionVolumeDepth, vec4(effectPosition, 1.0));
    vec4 light = LightFunctionVolumeWorldToLight * vec4(lfxLightFunctionTranslatedWorld, 1.0);
    lfxLightFunctionVector = light.zyx / light.w;
    effectUV = lfxLightFunctionVector.xy;
    if (LightFunctionVolumeParameters.y > 0.0)
        effectUV = lfxLightFunctionVector.xy / (lfxLightFunctionVector.z * LightFunctionVolumeParameters.x) * 0.5 + 0.5;
    else if (LightFunctionVolumeParameters.z > 0.0) {
        vec3 unitVector = length(lfxLightFunctionVector) > 0.0 ? normalize(lfxLightFunctionVector) : vec3(0.0, 0.0, 1.0);
        effectUV = vec2((atan(unitVector.y, unitVector.x) + 3.14159265358979323846) / 6.28318530717958647692,
                acos(clamp(unitVector.z, -1.0, 1.0)) / 3.14159265358979323846);
    }
    effectUV1 = effectUV;
}

void lfx_write_light_function(vec3 color) {
    fragColor = vec4(dot(color, vec3(0.3333)), 0.0, 0.0, 0.0);
}
#else
vec2 lfx_depth_viewport_uv() {
    vec2 uv = gl_FragCoord.xy * LightFunctionViewport.xy;
    return vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y);
}
float lfx_scene_depth() { return lfx_linear_depth(lfxLightFunctionDepth); }
float lfx_scene_depth_at(vec2 uv) {
    vec2 hostUV = vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y);
    return lfx_linear_depth(texture(SceneDepthSampler, hostUV).r);
}

void lfx_initialize_light_function() {
    lfxLightFunctionDepth = texelFetch(SceneDepthSampler, ivec2(gl_FragCoord.xy), 0).r;
    vec4 pixel = vec4(gl_FragCoord.xy, lfxLightFunctionDepth, 1.0);
    if (LightFunctionViewport.z > 0.5) pixel.y = LightFunctionViewport.w - pixel.y;
    vec4 light = LightFunctionPixelToLight * pixel;
    lfxLightFunctionVector = (light.xyz / light.w).zyx;
    effectUV = lfxLightFunctionVector.xy;
    if (LightFunctionParameters.z > 0.0) {
        effectUV = lfxLightFunctionVector.xy / (lfxLightFunctionVector.z * LightFunctionParameters.x) * 0.5 + 0.5;
    } else if (LightFunctionParameters.w > 0.0) {
        vec3 unitVector = normalize(lfxLightFunctionVector);
        effectUV = vec2((atan(unitVector.y, unitVector.x) + 3.14159265358979323846) / (2.0 * 3.14159265358979323846),
                acos(unitVector.z) / 3.14159265358979323846);
    }
    effectUV1 = effectUV;
    vec4 world = LightFunctionPixelToWorld * pixel;
    lfxLightFunctionTranslatedWorld = world.xyz / world.w;
    effectPosition = lfxLightFunctionTranslatedWorld.xzy * 0.01;
    vec4 view = LightFunctionPixelToView * pixel;
    lfxLightFunctionPixelDepth = view.z / view.w;
}

void lfx_write_light_function(vec3 color) {
    vec3 distanceVector = LightFunctionCameraOrigin.xyz - lfxLightFunctionTranslatedWorld;
    float distanceToCamera = length(distanceVector);
    if (LightFunctionFade.w > 0.5) distanceToCamera *= distanceToCamera / dot(distanceVector, LightFunctionViewForward.xyz);
    float grey = dot(color, vec3(0.3333));
    float distanceFade = clamp((LightFunctionFade.x - distanceToCamera) / (LightFunctionFade.x * 0.2), 0.0, 1.0);
    grey = mix(LightFunctionFade.y, grey, distanceFade);
    grey = mix(LightFunctionFade.y, grey, LightFunctionParameters.y);
    float encoded = sqrt(grey);
    fragColor = mix(vec4(1.0, 1.0, encoded, encoded), vec4(encoded), LightFunctionFade.z);
}
#endif
