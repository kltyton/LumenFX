#version 450
#define LFX_VOLUME_COMPUTE 1
#moj_import <lumenfx:niagara_volume_material.glsl>

uniform sampler3D VolumeBakedExtinction;
uniform sampler3D VolumeBakedEmission;
uniform sampler3D VolumeBakedAlbedo;

LfxVolumeMaterial lfx_evaluate_volume(vec3 localPosition, vec3 translatedPosition) {
    vec3 uvw = clamp((localPosition - VolumeBoundsMin.xyz) / (VolumeBoundsMax.xyz - VolumeBoundsMin.xyz), 0.0, 1.0);
    return LfxVolumeMaterial(textureLod(VolumeBakedExtinction, uvw, 0.0).rgb,
                             textureLod(VolumeBakedEmission, uvw, 0.0).rgb,
                             textureLod(VolumeBakedAlbedo, uvw, 0.0).rgb);
}

#moj_import <lumenfx:niagara_volume_integrate.glsl>
