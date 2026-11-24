#moj_import <lumenfx:atmosphere/aerial_sampling.glsl>

layout(std140) uniform ParticleAerialPerspective {
    mat4 AerialWorldToClip;
    mat4 AerialClipToWorld;
    vec4 AerialVolume;
    vec4 AerialOriginShift;
    vec4 AerialCamera;
};
uniform sampler3D ParticleAerialPerspectiveSampler;

vec4 lfx_apply_aerial_perspective(vec4 fog, vec3 cameraRelativePosition) {
    vec4 atmosphere = lfx_sample_aerial_perspective(ParticleAerialPerspectiveSampler, AerialWorldToClip,
            AerialClipToWorld, AerialVolume, AerialOriginShift, AerialCamera, cameraRelativePosition, cameraRelativePosition);
    return vec4(fog.rgb + atmosphere.rgb * fog.a, fog.a * atmosphere.a);
}
