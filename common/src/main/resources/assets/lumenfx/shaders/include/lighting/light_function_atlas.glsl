#moj_import <lumenfx:lighting/light_function_sampling.glsl>

layout(std140) uniform LocalLightFunction {
    mat4 LightFunctionTranslatedWorldToLight;
    vec4 LightFunctionAtlasParameters;
    vec4 LightFunctionAtlasTile;
};
uniform sampler2D LocalLightFunctionAtlas;

vec3 lfx_local_light_function(vec3 position, float viewDistance) {
    return lfx_sample_light_function(LocalLightFunctionAtlas, LightFunctionTranslatedWorldToLight,
            LightFunctionAtlasParameters, LightFunctionAtlasTile, position, viewDistance);
}
