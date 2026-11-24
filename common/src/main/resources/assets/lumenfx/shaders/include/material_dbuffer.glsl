#ifndef LFX_DBUFFER_LOOKUP_ACTIVE
#define LFX_DBUFFER_LOOKUP_ACTIVE 0
#endif
#ifndef LFX_DBUFFER_LOOKUP_MASK
#define LFX_DBUFFER_LOOKUP_MASK 0
#endif

#if LFX_DBUFFER_LOOKUP_ACTIVE && (LFX_DBUFFER_LOOKUP_MASK & 1)
uniform sampler2D LfxDBufferA;
#endif
#if LFX_DBUFFER_LOOKUP_ACTIVE && (LFX_DBUFFER_LOOKUP_MASK & 2)
uniform sampler2D LfxDBufferB;
#endif
#if LFX_DBUFFER_LOOKUP_ACTIVE && (LFX_DBUFFER_LOOKUP_MASK & 4)
uniform sampler2D LfxDBufferC;
#endif

vec2 lfx_material_dbuffer_default_uv() { return gl_FragCoord.xy * vec2(Geometry.w, CameraUp.w); }

vec2 lfx_material_dbuffer_uv(vec2 viewportUV) {
    vec2 hostUV = vec2(viewportUV.x, CameraRight.w > 0.5 ? 1.0 - viewportUV.y : viewportUV.y);
    vec2 halfTexel = 0.5 * vec2(Geometry.w, CameraUp.w);
    return clamp(hostUV, halfTexel, vec2(1.0) - halfTexel);
}

vec4 lfx_material_dbuffer_0(vec2 uv) {
#if LFX_DBUFFER_LOOKUP_ACTIVE && (LFX_DBUFFER_LOOKUP_MASK & 1)
    return textureLod(LfxDBufferA, uv, 0.0);
#else
    return vec4(0.0, 0.0, 0.0, 1.0);
#endif
}

vec4 lfx_material_dbuffer_1(vec2 uv) {
#if LFX_DBUFFER_LOOKUP_ACTIVE && (LFX_DBUFFER_LOOKUP_MASK & 2)
    vec4 value = textureLod(LfxDBufferB, uv, 0.0);
    vec3 normal = value.rgb * 2.0 - 256.0 / 255.0;
    return vec4(normal.xzy, value.a);
#else
    return vec4(0.0, 0.0, 0.0, 1.0);
#endif
}

vec4 lfx_material_dbuffer_2(vec2 uv) {
#if LFX_DBUFFER_LOOKUP_ACTIVE && (LFX_DBUFFER_LOOKUP_MASK & 4)
    return textureLod(LfxDBufferC, uv, 0.0).brga;
#else
    return vec4(0.0, 0.0, 0.0, 1.0);
#endif
}
