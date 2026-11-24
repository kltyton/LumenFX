#version 330
#moj_import <lumenfx:lighting/translucency_volume_grid.glsl>

#if defined(LFX_TLV_LOCAL_LIGHT)
#moj_import <lumenfx:lighting/translucency_volume_local_light.glsl>
#elif !defined(LFX_TLV_DIRECTIONAL_LIGHT)
uniform samplerBuffer ParticleLights;
flat out int TlvLightIndex;
#endif

void main() {
    int dimension = TlvAtlas.x;
    int slice = gl_InstanceID % dimension;
    vec4 grid = TlvCascade[TlvAtlas.w];
    if (grid.w <= 0.0) {
        gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
        return;
    }
#ifdef LFX_TLV_DIRECTIONAL_LIGHT
    vec3 lower = vec3(0.0);
    vec3 upper = vec3(float(dimension));
#else
#ifdef LFX_TLV_LOCAL_LIGHT
    vec4 light = TlvLocalPositionRadius;
#else
    TlvLightIndex = gl_InstanceID / dimension;
    vec4 light = texelFetch(ParticleLights, TlvLightIndex * 3);
#endif
    vec3 centre = (light.xyz * 100.0 - grid.xyz) / grid.w;
    float radius = light.w / grid.w;
    vec3 lower = max(trunc(centre - radius), vec3(0.0));
    vec3 upper = min(trunc(centre + radius) + 1.0, vec3(float(dimension)));
#endif
    if (any(lessThanEqual(upper, lower)) || float(slice) < lower.z || float(slice) >= upper.z) {
        gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
        return;
    }
    const vec2 corners[6] = vec2[6](vec2(0,0), vec2(1,0), vec2(1,1), vec2(0,0), vec2(1,1), vec2(0,1));
    vec2 position = mix(lower.xy, upper.xy, corners[gl_VertexID]);
    vec2 tile = vec2(slice % TlvAtlas.y, slice / TlvAtlas.y);
    vec2 uv = (tile + position / float(dimension)) / vec2(TlvAtlas.yz);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
