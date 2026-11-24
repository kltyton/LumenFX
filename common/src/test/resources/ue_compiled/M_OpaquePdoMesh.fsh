#version 330
#moj_import <lumenfx:material_view.glsl>
#moj_import <lumenfx:material_fog.glsl>
#if LFX_NANITE_MATERIAL
vec3 effectPositionNoOffsets;
#else
in vec3 effectPositionNoOffsets;
#endif
#if LFX_NANITE_MATERIAL
#moj_import <lumenfx:nanite/material_geometry.glsl>
LfxNaniteMaterialGeometry lfxNaniteGeometry;
vec4 lfxNaniteUvDx[4]; vec4 lfxNaniteUvDy[4];
vec3 lfxNaniteNoOffsetDx, lfxNaniteNoOffsetDy;
#else
#endif
#if LFX_NANITE_MATERIAL
vec3 lfx_material_world_dx(bool noOffsets) { return (noOffsets ? lfxNaniteNoOffsetDx : lfxNaniteGeometry.positionDx); }
vec3 lfx_material_world_dy(bool noOffsets) { return (noOffsets ? lfxNaniteNoOffsetDy : lfxNaniteGeometry.positionDy); }
#else
vec3 lfx_material_world_dx(bool noOffsets) { return dFdx(noOffsets ? (effectPosition.xzy * 100.0) : effectPosition.xzy * 100.0); }
vec3 lfx_material_world_dy(bool noOffsets) { return dFdy(noOffsets ? (effectPosition.xzy * 100.0) : effectPosition.xzy * 100.0); }
#endif
#moj_import <minecraft:projection.glsl>
layout(std140) uniform EffectView { vec4 CameraRight; vec4 CameraUp; vec4 Geometry;
#if LFX_MACRO_UV
vec4 MacroUV;
#endif
#if LFX_DEPTH_PROJECTION
vec4 DepthTransform;
#endif
};
float lfx_linear_depth(float d) { return d * DepthTransform.x + DepthTransform.y + 1.0 / (d * DepthTransform.z - DepthTransform.w); }
#if LFX_NANITE_MATERIAL
float lfx_pixel_depth() { return ProjMat[3][3] >= 1.0 ? lfx_linear_depth(lfxNaniteGeometry.surfaceDepth) : 100.0 / lfxNaniteGeometry.inverseClipW; }
#else
float lfx_pixel_depth() { return ProjMat[3][3] >= 1.0 ? lfx_linear_depth(gl_FragCoord.z) : 100.0 / gl_FragCoord.w; }
#endif
#if !LFX_PARTICLE_POINT_SHADOW
#define LFX_MATERIAL_WRITES_DEPTH 1
#moj_import <lumenfx:depth/pixel_depth_offset.glsl>
#endif
vec4 lfx_unpack_normal_rg(vec4 sampleValue) { vec2 xy = sampleValue.rg * 2.0 - 1.0; return vec4(xy, sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0)), 1.0); }
float lfx_positive_pow(float b, float e) { return b <= 2.980233e-8 ? 0.0 : pow(b, e); }
float lfx_fmod(float x, float y) { return x - y * trunc(x / y); }
float lfx_smoothstep(float a, float b, float x) { float t = clamp((x-a)/(b-a), 0.0, 1.0); return t*t*(3.0-2.0*t); }
vec3 lfx_hsv_to_rgb(vec3 hsv) { float h = hsv.r; vec3 rgb = clamp(vec3(abs(h * 6.0 - 3.0) - 1.0, 2.0 - abs(h * 6.0 - 2.0), 2.0 - abs(h * 6.0 - 4.0)), 0.0, 1.0); return ((rgb - 1.0) * hsv.y + 1.0) * hsv.z; }
vec4 lfx_hsv_to_rgb(vec4 hsv) { return vec4(lfx_hsv_to_rgb(hsv.rgb), hsv.a); }
vec3 lfx_rgb_to_hsv(vec3 rgb) { vec4 p = (rgb.g < rgb.b) ? vec4(rgb.bg, -1.0, 2.0 / 3.0) : vec4(rgb.gb, 0.0, -1.0 / 3.0); vec4 q = (rgb.r < p.x) ? vec4(p.xyw, rgb.r) : vec4(rgb.r, p.yzx); float chroma = q.x - min(q.w, q.y); float hue = abs((q.w - q.y) / (6.0 * chroma + 1e-10) + q.z); vec3 hcv = vec3(hue, chroma, q.x); float s = hcv.y / (hcv.z + 1e-10); return vec3(hcv.x, s, hcv.z); }
vec4 lfx_rgb_to_hsv(vec4 rgb) { return vec4(lfx_rgb_to_hsv(rgb.rgb), rgb.a); }
vec2 lfx_positive_pow(vec2 b, vec2 e) { return vec2(lfx_positive_pow(b.x, e.x), lfx_positive_pow(b.y, e.y)); }
vec2 lfx_fmod(vec2 x, vec2 y) { return x - y * trunc(x / y); }
vec2 lfx_smoothstep(vec2 a, vec2 b, vec2 x) { vec2 t = clamp((x-a)/(b-a), 0.0, 1.0); return t*t*(3.0-2.0*t); }
vec3 lfx_positive_pow(vec3 b, vec3 e) { return vec3(lfx_positive_pow(b.x, e.x), lfx_positive_pow(b.y, e.y), lfx_positive_pow(b.z, e.z)); }
vec3 lfx_fmod(vec3 x, vec3 y) { return x - y * trunc(x / y); }
vec3 lfx_smoothstep(vec3 a, vec3 b, vec3 x) { vec3 t = clamp((x-a)/(b-a), 0.0, 1.0); return t*t*(3.0-2.0*t); }
vec4 lfx_positive_pow(vec4 b, vec4 e) { return vec4(lfx_positive_pow(b.x, e.x), lfx_positive_pow(b.y, e.y), lfx_positive_pow(b.z, e.z), lfx_positive_pow(b.w, e.w)); }
vec4 lfx_fmod(vec4 x, vec4 y) { return x - y * trunc(x / y); }
vec4 lfx_smoothstep(vec4 a, vec4 b, vec4 x) { vec4 t = clamp((x-a)/(b-a), 0.0, 1.0); return t*t*(3.0-2.0*t); }
float lfx_fast_acos(float inputValue) {
    float magnitude = abs(inputValue);
    float angle = (1.5707963267948966 - 0.156583 * magnitude) * sqrt(1.0 - magnitude);
    return inputValue >= 0.0 ? angle : 3.141592653589793 - angle;
}
float lfx_fast_asin(float inputValue) {
    return 1.5707963267948966 - lfx_fast_acos(inputValue);
}
float lfx_fast_atan_polynomial(float ratio) {
    float squared = ratio * ratio;
    return ((0.0872929 * squared - 0.301895) * squared + 1.0) * ratio;
}
float lfx_fast_atan(float inputValue) {
    float magnitude = abs(inputValue);
    bool belowOne = magnitude < 1.0;
    float angle = lfx_fast_atan_polynomial(belowOne ? magnitude : 1.0 / magnitude);
    if (!belowOne) angle = 1.5707963267948966 - angle;
    return inputValue < 0.0 ? -angle : angle;
}
float lfx_fast_atan2(float y, float x) {
    float absoluteX = abs(x), absoluteY = abs(y);
    float angle = lfx_fast_atan_polynomial(min(absoluteX, absoluteY) / max(absoluteX, absoluteY));
    if (absoluteY > absoluteX) angle = 1.5707963267948966 - angle;
    if (x < 0.0) angle = 3.141592653589793 - angle;
    return y < 0.0 ? -angle : angle;
}
vec2 lfx_fast_asin(vec2 value) { return vec2(lfx_fast_asin(value.x), lfx_fast_asin(value.y)); }
vec2 lfx_fast_acos(vec2 value) { return vec2(lfx_fast_acos(value.x), lfx_fast_acos(value.y)); }
vec2 lfx_fast_atan(vec2 value) { return vec2(lfx_fast_atan(value.x), lfx_fast_atan(value.y)); }
vec2 lfx_fast_atan2(vec2 y, vec2 x) { return vec2(lfx_fast_atan2(y.x, x.x), lfx_fast_atan2(y.y, x.y)); }
vec3 lfx_fast_asin(vec3 value) { return vec3(lfx_fast_asin(value.x), lfx_fast_asin(value.y), lfx_fast_asin(value.z)); }
vec3 lfx_fast_acos(vec3 value) { return vec3(lfx_fast_acos(value.x), lfx_fast_acos(value.y), lfx_fast_acos(value.z)); }
vec3 lfx_fast_atan(vec3 value) { return vec3(lfx_fast_atan(value.x), lfx_fast_atan(value.y), lfx_fast_atan(value.z)); }
vec3 lfx_fast_atan2(vec3 y, vec3 x) { return vec3(lfx_fast_atan2(y.x, x.x), lfx_fast_atan2(y.y, x.y), lfx_fast_atan2(y.z, x.z)); }
vec4 lfx_fast_asin(vec4 value) { return vec4(lfx_fast_asin(value.x), lfx_fast_asin(value.y), lfx_fast_asin(value.z), lfx_fast_asin(value.w)); }
vec4 lfx_fast_acos(vec4 value) { return vec4(lfx_fast_acos(value.x), lfx_fast_acos(value.y), lfx_fast_acos(value.z), lfx_fast_acos(value.w)); }
vec4 lfx_fast_atan(vec4 value) { return vec4(lfx_fast_atan(value.x), lfx_fast_atan(value.y), lfx_fast_atan(value.z), lfx_fast_atan(value.w)); }
vec4 lfx_fast_atan2(vec4 y, vec4 x) { return vec4(lfx_fast_atan2(y.x, x.x), lfx_fast_atan2(y.y, x.y), lfx_fast_atan2(y.z, x.z), lfx_fast_atan2(y.w, x.w)); }
layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; vec4 EffectTimeData; };
#if LFX_NANITE_MATERIAL
vec2 effectUV;
#else
in vec2 effectUV;
#endif
#if LFX_NANITE_MATERIAL
vec2 effectUV1;
#else
in vec2 effectUV1;
#endif
#if LFX_NANITE_MATERIAL
vec4 effectColor;
#else
in vec4 effectColor;
#endif
#if LFX_NANITE_MATERIAL
vec4 effectParticle;
#else
in vec4 effectParticle;
#endif
#if LFX_NANITE_MATERIAL
vec4 effectCustom;
#else
in vec4 effectCustom;
#endif
#if LFX_NANITE_MATERIAL
float effectDynamicValid;
#else
in float effectDynamicValid;
#endif
#if LFX_NANITE_MATERIAL
vec4 effectMeshUV01;
#else
in vec4 effectMeshUV01;
#endif
#if LFX_NANITE_MATERIAL
vec4 effectMeshUV23;
#else
in vec4 effectMeshUV23;
#endif
#if LFX_NANITE_MATERIAL
vec4 effectMeshUV45;
#else
in vec4 effectMeshUV45;
#endif
#if LFX_NANITE_MATERIAL
vec4 effectMeshUV67;
#else
in vec4 effectMeshUV67;
#endif
#if LFX_NANITE_MATERIAL
vec4 effectVertexColor;
#else
in vec4 effectVertexColor;
#endif
#if LFX_NANITE_MATERIAL
float effectUnmirrored;
#else
in float effectUnmirrored;
#endif
#if LFX_NANITE_MATERIAL
vec4 effectMeshVelocity;
#else
in vec4 effectMeshVelocity;
#endif
vec2 lfx_mesh_coordinate(int channel) {
vec4 coordinates = channel < 2 ? effectMeshUV01 : channel < 4 ? effectMeshUV23 : channel < 6 ? effectMeshUV45 : effectMeshUV67; return (channel & 1) == 0 ? coordinates.xy : coordinates.zw;
}
#if LFX_NANITE_MATERIAL
vec3 effectParticleCenter;
#else
in vec3 effectParticleCenter;
#endif
#if !LFX_NANITE_SOFTWARE_RASTER
out vec4 fragColor;
#endif
#if LFX_NANITE_MATERIAL
float lfx_material_derivative_value(float finite, float analytic) { return analytic; }
float lfx_material_ddx(float value, float analytic) { return analytic; }
float lfx_material_ddy(float value, float analytic) { return analytic; }
#else
float lfx_material_derivative_value(float finite, float analytic) { return finite; }
float lfx_material_ddx(float value, float analytic) { return dFdx(value); }
float lfx_material_ddy(float value, float analytic) { return dFdy(value); }
#endif
#if LFX_NANITE_MATERIAL
vec2 lfx_material_derivative_value(vec2 finite, vec2 analytic) { return analytic; }
vec2 lfx_material_ddx(vec2 value, vec2 analytic) { return analytic; }
vec2 lfx_material_ddy(vec2 value, vec2 analytic) { return analytic; }
#else
vec2 lfx_material_derivative_value(vec2 finite, vec2 analytic) { return finite; }
vec2 lfx_material_ddx(vec2 value, vec2 analytic) { return dFdx(value); }
vec2 lfx_material_ddy(vec2 value, vec2 analytic) { return dFdy(value); }
#endif
#if LFX_NANITE_MATERIAL
vec3 lfx_material_derivative_value(vec3 finite, vec3 analytic) { return analytic; }
vec3 lfx_material_ddx(vec3 value, vec3 analytic) { return analytic; }
vec3 lfx_material_ddy(vec3 value, vec3 analytic) { return analytic; }
#else
vec3 lfx_material_derivative_value(vec3 finite, vec3 analytic) { return finite; }
vec3 lfx_material_ddx(vec3 value, vec3 analytic) { return dFdx(value); }
vec3 lfx_material_ddy(vec3 value, vec3 analytic) { return dFdy(value); }
#endif
#if LFX_NANITE_MATERIAL
vec4 lfx_material_derivative_value(vec4 finite, vec4 analytic) { return analytic; }
vec4 lfx_material_ddx(vec4 value, vec4 analytic) { return analytic; }
vec4 lfx_material_ddy(vec4 value, vec4 analytic) { return analytic; }
#else
vec4 lfx_material_derivative_value(vec4 finite, vec4 analytic) { return finite; }
vec4 lfx_material_ddx(vec4 value, vec4 analytic) { return dFdx(value); }
vec4 lfx_material_ddy(vec4 value, vec4 analytic) { return dFdy(value); }
#endif
#if LFX_NANITE_SOFTWARE_RASTER
bool lfx_nanite_evaluate_coverage() {
#else
void main() {
#endif
#if LFX_NANITE_MATERIAL
#if LFX_NANITE_SOFTWARE_RASTER
    if (!lfx_nanite_material_geometry(lfxNaniteGeometry)) return false;
#else
    if (!lfx_nanite_material_geometry(lfxNaniteGeometry)) discard;
#endif
#endif
#if LFX_NANITE_MATERIAL
    effectPosition = lfxNaniteGeometry.position.xzy * 0.01;
    gl_FragDepth = lfxNaniteGeometry.deviceDepth;
    vec4 lfxNaniteDx, lfxNaniteDy;
    effectPositionNoOffsets = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, 0, lfxNaniteDx, lfxNaniteDy).xyz.xzy * 0.01;
    lfxNaniteNoOffsetDx = lfxNaniteDx.xyz; lfxNaniteNoOffsetDy = lfxNaniteDy.xyz * -NaniteShadingViewport.z;
#if LFX_NANITE_RASTER_MATERIAL && !LFX_NANITE_SOFTWARE_RASTER
    effectMeshUV01 = lfxNaniteCoverageUV01; lfxNaniteUvDx[0] = dFdx(effectMeshUV01); lfxNaniteUvDy[0] = dFdy(effectMeshUV01);
#else
    effectMeshUV01 = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, 1, lfxNaniteUvDx[0], lfxNaniteUvDy[0]);
#endif
    effectMeshUV23 = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, 2, lfxNaniteUvDx[1], lfxNaniteUvDy[1]);
    effectMeshUV45 = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, 3, lfxNaniteUvDx[2], lfxNaniteUvDy[2]);
    effectMeshUV67 = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, 4, lfxNaniteUvDx[3], lfxNaniteUvDy[3]);
    for (int i = 0; i < 4; i++) lfxNaniteUvDy[i] *= -NaniteShadingViewport.z;
    effectUV = effectMeshUV01.xy; effectUV1 = effectMeshUV01.zw;
    effectColor = vec4(1.0); effectVertexColor = lfxNaniteGeometry.color;
    effectParticle = effectCustom = effectMeshVelocity = vec4(0.0); effectDynamicValid = 0.0; effectParticleCenter = vec3(0.0);
    effectUnmirrored = lfxNaniteGeometry.tangentSign;
#endif
#if LFX_MATERIAL_WRITES_DEPTH
#if LFX_NANITE_MATERIAL && !LFX_NANITE_RASTER_MATERIAL
    if (lfx_nanite_fixed_raster()) lfxDepthOffsetWorldPosition = effectPosition; else
#elif LFX_NANITE_SOFTWARE_RASTER
    if (lfx_nanite_shadow_depth()) { lfxDepthOffsetWorldPosition = effectPosition; lfxMaterialDeviceZ = lfxNaniteGeometry.deviceDepth; } else
#endif
    lfxMaterialDeviceZ = lfx_apply_pixel_depth_offset(4.0, false);
#if LFX_NANITE_MATERIAL && !LFX_NANITE_RASTER_MATERIAL
    lfxMaterialDeviceZ = lfxNaniteGeometry.deviceDepth;
#endif
    gl_FragDepth = lfxMaterialDeviceZ;
#endif
#if LFX_NANITE_SOFTWARE_RASTER
    return true;
#else
    vec3 emission = vec3(0.0);
    float rawOpacity = 1.0;
    float opacity = clamp(rawOpacity, 0.0, 1.0);
    opacity = 1.0;
    fragColor = lfx_apply_material_fog(vec4(emission, opacity));
#endif
}
