#moj_import <lumenfx:atmosphere/aerial_sampling.glsl>

uniform sampler3D MaterialAtmosphereAerialVolume;

vec4 lfx_material_atmosphere_aerial(vec3 positionCm, vec3 surfaceCm) {
    return lfx_sample_aerial_perspective(MaterialAtmosphereAerialVolume, MaterialAtmosphereAerialWorldToClip,
            MaterialAtmosphereAerialClipToWorld, MaterialAtmosphereAerialDepth, MaterialAtmosphereAerialOriginShift,
            MaterialAtmosphereAerialCamera, positionCm.xzy * 0.01, surfaceCm.xzy * 0.01);
}
