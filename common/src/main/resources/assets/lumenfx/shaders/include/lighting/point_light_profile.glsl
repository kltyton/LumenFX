vec2 lfx_light_profile_coordinates(vec3 worldPosition, vec3 lightPosition, vec3 lightDirection, vec3 lightTangent) {
    vec3 lightBitangent = normalize(cross(lightTangent, lightDirection));
    vec3 toLight = normalize(lightPosition - worldPosition);
    vec3 localToLight = vec3(dot(toLight, lightDirection), dot(toLight, lightBitangent), dot(toLight, lightTangent));
    float vertical = asin(dot(toLight, lightDirection)) / 3.14159265358979323846 + 0.5;
    float horizontal = atan(-localToLight.z, -localToLight.y) / (3.14159265358979323846 * 2.0) + 0.5;
    return vec2(vertical, horizontal);
}

float lfx_light_profile(sampler2DArray profiles, float index, vec3 worldPosition, vec3 lightPosition,
                       vec3 lightDirection, vec3 lightTangent) {
    if (index < 0.0) return 1.0;
    vec2 uv = lfx_light_profile_coordinates(worldPosition, lightPosition, lightDirection, lightTangent);
    return textureLod(profiles, vec3(uv, index), 0.0).r;
}

float lfx_light_profile_standalone(sampler2D profile, vec3 worldPosition, vec3 lightPosition,
                                  vec3 lightDirection, vec3 lightTangent) {
    vec2 uv = lfx_light_profile_coordinates(worldPosition, lightPosition, lightDirection, lightTangent);
    return textureLod(profile, uv, 0.0).r;
}
