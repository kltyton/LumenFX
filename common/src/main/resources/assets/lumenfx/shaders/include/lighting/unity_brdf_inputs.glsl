#ifndef LFX_UNITY_BRDF_INPUTS
#define LFX_UNITY_BRDF_INPUTS

float lfx_unity_specular_reflectivity(vec3 specular) {
    return max(specular.r, max(specular.g, specular.b));
}

#endif
