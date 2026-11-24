#version 330
#define LFX_VERTEX_FOG
#moj_import <lumenfx:volumetric_common.glsl>
#moj_import <lumenfx:fog_particle_light_uniforms.glsl>

flat out int fogLightIndex;
flat out int fogLightSlice;

void main() {
    int slices = FogParticleLightCount.y;
    fogLightIndex = gl_InstanceID / slices;
    fogLightSlice = gl_InstanceID - fogLightIndex * slices;
#if defined(LFX_FOG_DIRECTIONAL_LIGHT) || defined(LFX_FOG_INDIRECT_LIGHT)
    vec2 minimumUv = vec2(0.0), maximumUv = vec2(1.0);
#else
#ifdef LFX_FOG_LOCAL_LIGHT
    vec4 centre = FogLocalCentre;
#else
    vec4 centre = texelFetch(ParticleLights, fogLightIndex * 3);
#endif
    float radius = centre.w * 0.01;
    vec2 minimumNdc = vec2(1e30), maximumNdc = vec2(-1e30);
    float minimumDepth = 1e30, maximumDepth = -1e30;
    bool crossesEye = false;
    for (int corner = 0; corner < 8; corner++) {
        vec3 direction = vec3((corner & 1) == 0 ? -1.0 : 1.0,
                              (corner & 2) == 0 ? -1.0 : 1.0,
                              (corner & 4) == 0 ? -1.0 : 1.0);
        vec4 clip = FogLightWorldToClip * vec4(centre.xyz + direction * radius, 1.0);
        minimumDepth = min(minimumDepth, clip.w);
        maximumDepth = max(maximumDepth, clip.w);
        if (clip.w <= 0.0) crossesEye = true;
        else {
            vec2 ndc = clip.xy / clip.w;
            minimumNdc = min(minimumNdc, ndc);
            maximumNdc = max(maximumNdc, ndc);
        }
    }
    float depthPadding = FogFrame.z >= 0.0 ? max(FogFrame.w, 0.0) : 0.0;
    float jitterRadius = abs(FogParticleLightExposure.y);
    float sliceNear = lfx_depth_from_slice(max(float(fogLightSlice) - depthPadding - jitterRadius, 0.0)) * 0.01;
    float sliceFar = lfx_depth_from_slice(float(fogLightSlice) + 1.0 + jitterRadius) * 0.01;
    if (maximumDepth < sliceNear || minimumDepth > sliceFar) {
        gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
        return;
    }
    if (crossesEye) { minimumNdc = vec2(-1.0); maximumNdc = vec2(1.0); }
    vec2 minimumUv = vec2(minimumNdc.x, -maximumNdc.y) * 0.5 + 0.5;
    vec2 maximumUv = vec2(maximumNdc.x, -minimumNdc.y) * 0.5 + 0.5;
    vec2 padding = (1.0 + jitterRadius) / FogGrid.xy;
    minimumUv = clamp(minimumUv - padding, vec2(0.0), vec2(1.0));
    maximumUv = clamp(maximumUv + padding, vec2(0.0), vec2(1.0));
#endif
    const vec2 corners[6] = vec2[6](vec2(0,0), vec2(1,0), vec2(1,1), vec2(0,0), vec2(1,1), vec2(0,1));
    vec2 local = mix(minimumUv, maximumUv, corners[gl_VertexID]);
    int columns = int(FogGrid.w + 0.5);
    ivec2 tile = ivec2(fogLightSlice % columns, fogLightSlice / columns);
    vec2 atlasUv = (vec2(tile) + local) * FogGrid.xy / FogAtlas.xy;
    gl_Position = vec4(atlasUv * 2.0 - 1.0, 0.0, 1.0);
}
