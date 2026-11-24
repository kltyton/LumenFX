struct LfxPointCapsule {
    vec3 line0;
    vec3 line1;
    float sourceLength;
    float radius;
    float softRadius;
    float distanceBiasSquared;
};

struct LfxPointAreaLight {
    vec3 diffuseDirection;
    vec3 specularDirection;
    float normalDotLight;
    float falloff;
    float lineCosSubtended;
    float sphereSinAlpha;
    float sphereSinAlphaSoft;
    float diffuseMicroReflectionWeight;
};

float lfx_point_radial_mask(vec3 toLight, float inverseRadius, float falloffExponent, bool inverseSquared) {
    if (inverseSquared) {
        float distanceSquared = dot(toLight, toLight);
        float normalizedSquared = distanceSquared * (inverseRadius * inverseRadius);
        float mask = clamp(1.0 - normalizedSquared * normalizedSquared, 0.0, 1.0);
        return mask * mask;
    }
    vec3 normalizedVector = toLight * inverseRadius;
    float mask = 1.0 - clamp(dot(normalizedVector, normalizedVector), 0.0, 1.0);
    return pow(mask, falloffExponent);
}

float lfx_spot_attenuation(vec3 directionToLight, vec3 negativeSpotDirection, vec2 angles) {
    float cone = clamp((dot(directionToLight, negativeSpotDirection) - angles.x) * angles.y, 0.0, 1.0);
    return cone * cone;
}

LfxPointCapsule lfx_point_capsule(vec3 toLight, vec3 tangent, float sourceLength, float sourceRadius, float softRadius) {
    LfxPointCapsule result;
    result.sourceLength = sourceLength;
    result.radius = sourceRadius;
    result.softRadius = softRadius;
    result.distanceBiasSquared = 1.0;
    result.line0 = toLight - 0.5 * sourceLength * tangent;
    result.line1 = toLight + 0.5 * sourceLength * tangent;
    return result;
}

vec3 lfx_point_closest_line_to_point(vec3 line0, vec3 line1, float lineLength) {
    vec3 delta = line1 - line0;
    return line0 + delta * clamp(-dot(delta, line0) / (lineLength * lineLength), 0.0, 1.0);
}

vec3 lfx_point_closest_line_to_ray(vec3 line0, vec3 line1, float lineLength, vec3 ray) {
    vec3 delta = line1 - line0;
    float a = lineLength * lineLength;
    float b = dot(ray, delta);
    float t = clamp(dot(line0, b * ray - delta) / (a - b * b), 0.0, 1.0);
    return line0 + t * delta;
}

void lfx_point_line_irradiance(vec3 line0, vec3 line1, float biasSquared,
                              out float cosSubtended, out float falloff, out vec3 direction) {
    float inverseLength0 = inversesqrt(dot(line0, line0));
    float inverseLength1 = inversesqrt(dot(line1, line1));
    float inverseLength01 = inverseLength0 * inverseLength1;
    cosSubtended = dot(line0, line1) * inverseLength01;
    falloff = inverseLength01 / (cosSubtended * 0.5 + 0.5 + biasSquared * inverseLength01);
    direction = 0.5 * (line0 * inverseLength0 + line1 * inverseLength1);
}

float lfx_point_sphere_horizon(float normalDotLight, float sinAlphaSquared) {
    float sinAlpha = sqrt(sinAlphaSquared);
    if (normalDotLight < sinAlpha) {
        normalDotLight = max(normalDotLight, -sinAlpha);
        float wrapped = sinAlpha + normalDotLight;
        normalDotLight = wrapped * wrapped / (4.0 * sinAlpha);
    }
    return normalDotLight;
}

float lfx_integrate_point_light(LfxPointCapsule capsule, bool inverseSquared) {
    float falloff;
    if (capsule.sourceLength > 0.0) {
        float cosSubtended;
        vec3 direction;
        lfx_point_line_irradiance(capsule.line0, capsule.line1, capsule.distanceBiasSquared, cosSubtended, falloff, direction);
    } else falloff = 1.0 / (dot(capsule.line0, capsule.line0) + capsule.distanceBiasSquared);
    return inverseSquared ? falloff : 1.0;
}

LfxPointAreaLight lfx_create_point_area_light(float roughness, vec3 normal, vec3 view, LfxPointCapsule capsule, bool inverseSquared) {
    LfxPointAreaLight result;
    result.lineCosSubtended = 1.0;
    if (capsule.sourceLength > 0.0) {
        lfx_point_line_irradiance(capsule.line0, capsule.line1, capsule.distanceBiasSquared,
                result.lineCosSubtended, result.falloff, result.diffuseDirection);
        result.normalDotLight = dot(normal, result.diffuseDirection);
        result.diffuseDirection = normalize(result.diffuseDirection);
    } else {
        float distanceSquared = dot(capsule.line0, capsule.line0);
        result.falloff = 1.0 / (distanceSquared + capsule.distanceBiasSquared);
        result.diffuseDirection = capsule.line0 * inversesqrt(distanceSquared);
        result.normalDotLight = dot(normal, result.diffuseDirection);
    }
    if (capsule.radius > 0.0) {
        float sinAlphaSquared = clamp(capsule.radius * capsule.radius * result.falloff, 0.0, 1.0);
        result.normalDotLight = lfx_point_sphere_horizon(result.normalDotLight, sinAlphaSquared);
    }
    result.normalDotLight = clamp(result.normalDotLight, 0.0, 1.0);
    result.falloff = inverseSquared ? result.falloff : 1.0;
    vec3 toLight = capsule.line0;
    if (capsule.sourceLength > 0.0) toLight = lfx_point_closest_line_to_ray(capsule.line0, capsule.line1, capsule.sourceLength, reflect(-view, normal));
    float inverseDistance = inversesqrt(dot(toLight, toLight));
    result.specularDirection = toLight * inverseDistance;
    float a = roughness * roughness;
    result.sphereSinAlpha = clamp(capsule.radius * inverseDistance * (1.0 - a), 0.0, 1.0);
    result.sphereSinAlphaSoft = clamp(capsule.softRadius * inverseDistance, 0.0, 1.0);
    result.diffuseMicroReflectionWeight = clamp(1.0 - max(capsule.sourceLength, capsule.radius) / 20.0, 0.0, 1.0);
    return result;
}
