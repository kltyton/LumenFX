#version 330
layout(std140) uniform ParticleLightView {
    mat4 LightProjection;
    vec4 LightViewportExposure;
    vec4 LightMaterialSettings;
};
uniform samplerBuffer ParticleLights;
#ifdef LFX_POINT_COMPONENT
#moj_import <lumenfx:lighting/point_light_uniforms.glsl>
#endif
flat out int lightIndex;

const vec2 LightCorners[6] = vec2[6](vec2(0,0), vec2(1,0), vec2(1,1), vec2(0,0), vec2(1,1), vec2(0,1));

void main() {
    lightIndex = gl_InstanceID;
#ifdef LFX_POINT_COMPONENT
    lightIndex += int(PointAreaAndIndex.y);
#endif
    vec4 centreRadius = texelFetch(ParticleLights, lightIndex * 3);
    float radius = centreRadius.w * 0.01;
    vec2 minimum = vec2(1.0), maximum = vec2(-1.0);
    bool crossesEye = false;
    for (int corner = 0; corner < 8; ++corner) {
        vec3 direction = vec3((corner & 1) == 0 ? -1.0 : 1.0, (corner & 2) == 0 ? -1.0 : 1.0, (corner & 4) == 0 ? -1.0 : 1.0);
        vec4 projected = LightProjection * vec4(centreRadius.xyz + direction * radius, 1.0);
        if (projected.w <= 0.0) crossesEye = true;
        else { minimum = min(minimum, projected.xy / projected.w); maximum = max(maximum, projected.xy / projected.w); }
    }
    if (crossesEye) { minimum = vec2(-1.0); maximum = vec2(1.0); }
    else {
        minimum = max(vec2(-1.0), minimum - 2.0 / LightViewportExposure.xy);
        maximum = min(vec2(1.0), maximum + 2.0 / LightViewportExposure.xy);
    }
    if (any(greaterThan(minimum, maximum))) gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
    else gl_Position = vec4(mix(minimum, maximum, LightCorners[gl_VertexID]), 0.0, 1.0);
}
