#ifndef LFX_ATMOSPHERE_TRANSMITTANCE_MATH
#define LFX_ATMOSPHERE_TRANSMITTANCE_MATH

vec2 lfx_atmosphere_ray_sphere_intersections(vec3 origin, vec3 direction, vec3 center, float radius) {
    vec3 localPosition = origin - center;
    float a = dot(direction, direction);
    float b = 2.0 * dot(direction, localPosition);
    float c = dot(localPosition, localPosition) - radius * radius;
    float discriminant = b * b - 4.0 * a * c;
    if (discriminant < 0.0) return vec2(-1.0);
    float root = sqrt(discriminant);
    return (-b + vec2(-root, root)) / (2.0 * a);
}

float lfx_atmosphere_ray_sphere_nearest(vec3 origin, vec3 direction, vec3 center, float radius) {
    vec2 intersections = lfx_atmosphere_ray_sphere_intersections(origin, direction, center, radius);
    if (intersections.x < 0.0) return intersections.y;
    return intersections.x;
}

vec3 lfx_atmosphere_sample_transmittance(sampler2D lut, float viewHeight, float zenithCos,
                                         float bottomRadius, float topRadius) {
    float h = sqrt(max(0.0, topRadius * topRadius - bottomRadius * bottomRadius));
    float rho = sqrt(max(0.0, viewHeight * viewHeight - bottomRadius * bottomRadius));
    float discriminant = viewHeight * viewHeight * (zenithCos * zenithCos - 1.0) + topRadius * topRadius;
    float distance = max(0.0, -viewHeight * zenithCos + sqrt(max(0.0, discriminant)));
    float minimumDistance = topRadius - viewHeight;
    vec2 uv = vec2((distance - minimumDistance) / (rho + h - minimumDistance), rho / h);
    vec3 encoded = textureLod(lut, uv, 0.0).rgb;
    return encoded * encoded;
}

vec3 lfx_atmosphere_transmittance_along_ray(sampler2D lut, vec3 origin, vec3 direction,
                                            float bottomRadius, float topRadius) {
    vec2 ground = lfx_atmosphere_ray_sphere_intersections(origin, direction, vec3(0.0), bottomRadius);
    if (ground.y > 0.0) return vec3(0.0);
    float height = length(origin);
    if (height > topRadius) {
        float entry = lfx_atmosphere_ray_sphere_nearest(origin, direction, vec3(0.0), topRadius);
        if (entry < 0.0) return vec3(1.0);
        origin += direction * entry;
        height = length(origin);
    }
    return lfx_atmosphere_sample_transmittance(lut, height, dot(direction, origin / height), bottomRadius, topRadius);
}

vec3 lfx_atmosphere_transmittance_at_position(sampler2D lut, vec3 origin, vec3 direction,
                                              float bottomRadius, float topRadius) {
    vec2 ground = lfx_atmosphere_ray_sphere_intersections(origin, direction, vec3(0.0), bottomRadius);
    if (ground.y > 0.0) return vec3(0.0);
    float height = length(origin);
    return lfx_atmosphere_sample_transmittance(lut, height, dot(direction, origin / height), bottomRadius, topRadius);
}

#endif
