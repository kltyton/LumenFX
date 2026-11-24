#ifndef LFX_MATERIAL_SG_UV4
#define LFX_MATERIAL_SG_UV4

#ifndef LFX_SG_UV4_MASK
#define LFX_SG_UV4_MASK 0
#endif

#if (LFX_SG_UV4_MASK & 1) != 0
out vec4 lfxSgUV0;
vec4 lfxSgVertexUV0;
#endif
#if (LFX_SG_UV4_MASK & 2) != 0
out vec4 lfxSgUV1;
vec4 lfxSgVertexUV1;
#endif
#if (LFX_SG_UV4_MASK & 4) != 0
out vec4 lfxSgUV2;
vec4 lfxSgVertexUV2;
#endif
#if (LFX_SG_UV4_MASK & 8) != 0
out vec4 lfxSgUV3;
vec4 lfxSgVertexUV3;
#endif
#if (LFX_SG_UV4_MASK & 16) != 0
out vec4 lfxSgUV4;
vec4 lfxSgVertexUV4;
#endif
#if (LFX_SG_UV4_MASK & 32) != 0
out vec4 lfxSgUV5;
vec4 lfxSgVertexUV5;
#endif
#if (LFX_SG_UV4_MASK & 64) != 0
out vec4 lfxSgUV6;
vec4 lfxSgVertexUV6;
#endif
#if (LFX_SG_UV4_MASK & 128) != 0
out vec4 lfxSgUV7;
vec4 lfxSgVertexUV7;
#endif

void lfx_sg_assign_uv4_pair(vec2 uv0, vec2 uv1) {
#if (LFX_SG_UV4_MASK & 1) != 0
    lfxSgVertexUV0 = vec4(uv0, 0.0, 0.0);
    lfxSgUV0 = lfxSgVertexUV0;
#endif
#if (LFX_SG_UV4_MASK & 2) != 0
    lfxSgVertexUV1 = vec4(uv1, 0.0, 0.0);
    lfxSgUV1 = lfxSgVertexUV1;
#endif
#if (LFX_SG_UV4_MASK & 4) != 0
    lfxSgVertexUV2 = vec4(uv0, 0.0, 0.0);
    lfxSgUV2 = lfxSgVertexUV2;
#endif
#if (LFX_SG_UV4_MASK & 8) != 0
    lfxSgVertexUV3 = vec4(uv0, 0.0, 0.0);
    lfxSgUV3 = lfxSgVertexUV3;
#endif
#if (LFX_SG_UV4_MASK & 16) != 0
    lfxSgVertexUV4 = vec4(uv0, 0.0, 0.0);
    lfxSgUV4 = lfxSgVertexUV4;
#endif
#if (LFX_SG_UV4_MASK & 32) != 0
    lfxSgVertexUV5 = vec4(uv0, 0.0, 0.0);
    lfxSgUV5 = lfxSgVertexUV5;
#endif
#if (LFX_SG_UV4_MASK & 64) != 0
    lfxSgVertexUV6 = vec4(uv0, 0.0, 0.0);
    lfxSgUV6 = lfxSgVertexUV6;
#endif
#if (LFX_SG_UV4_MASK & 128) != 0
    lfxSgVertexUV7 = vec4(uv0, 0.0, 0.0);
    lfxSgUV7 = lfxSgVertexUV7;
#endif
}

#endif
