struct LfxRectLight {
    vec3 origin;
    mat3 axes;
    vec2 extent;
    vec2 fullExtent;
    vec2 offset;
};

LfxRectLight lfx_rect_light(vec3 toLight, vec3 axisNormal, vec3 tangent, vec4 shape) {
    mat3 axes = mat3(cross(tangent, axisNormal), tangent, axisNormal);
    LfxRectLight rect = LfxRectLight(toLight, axes, shape.xy, shape.xy, vec2(0.0));
    vec3 shadingPoint = transpose(axes) * toLight;
    if (shadingPoint.z <= 0.001) {
        rect.extent = vec2(0.0);
    } else if (shape.z > 0.035) {
        float maximumDepth = shape.z * shape.w;
        float barnDepth = min(shadingPoint.z, maximumDepth);
        float barnSpread = sqrt(max(0.0, 1.0 - shape.z * shape.z)) * shape.w * barnDepth / max(0.0001, maximumDepth);
        vec2 side = sign(shadingPoint.xy);
        shadingPoint.xy = side * max(abs(shadingPoint.xy), shape.xy + barnSpread);
        vec3 nearestCorner = vec3(side * (shape.xy + barnSpread), barnDepth);
        vec3 relativePoint = shadingPoint - nearestCorner;
        vec2 projectedSpread = barnDepth * abs(relativePoint.xy) / max(relativePoint.z, 0.001);
        vec2 delta = projectedSpread - barnSpread;
        vec2 minimum = clamp(-shape.xy + delta * max(vec2(0.0), -side), -shape.xy, shape.xy);
        vec2 maximum = clamp(shape.xy - delta * max(vec2(0.0), side), -shape.xy, shape.xy);
        vec2 displacement = 0.5 * (minimum + maximum);
        rect.extent = 0.5 * (maximum - minimum);
        rect.origin -= axes[0] * displacement.x + axes[1] * displacement.y;
        rect.offset = -displacement;
    }
    return rect;
}

vec3 lfx_rect_edge(vec3 start, vec3 end) {
    float cosine = dot(start, end);
    float weight = (1.5708 - 0.175 * cosine) * inversesqrt(max(cosine + 1.0, 1e-4));
    return weight * cross(start, end);
}

void lfx_rect_corners(LfxRectLight rect, out vec3 corners[4]) {
    vec3 dx = rect.axes[0] * rect.extent.x, dy = rect.axes[1] * rect.extent.y;
    corners[0] = rect.origin - dx - dy;
    corners[1] = rect.origin + dx - dy;
    corners[2] = rect.origin + dx + dy;
    corners[3] = rect.origin - dx + dy;
}

vec3 lfx_rect_irradiance(LfxRectLight rect, vec3 normal, out float falloff, out float noL) {
    if (any(lessThanEqual(rect.extent, vec2(0.0)))) {
        falloff = noL = 0.0;
        return rect.axes[2];
    }
    vec3 corner[4];
    lfx_rect_corners(rect, corner);
    for (int index = 0; index < 4; ++index) corner[index] = normalize(corner[index]);
    vec3 light = lfx_rect_edge(corner[0], corner[1]) + lfx_rect_edge(corner[1], corner[2])
            + lfx_rect_edge(corner[2], corner[3]) + lfx_rect_edge(corner[3], corner[0]);
    float lengthLight = length(light);
    light /= max(lengthLight, 1e-12);
    falloff = 0.5 * lengthLight;
    noL = lfx_point_sphere_horizon(dot(normal, light), falloff * 0.31830988618);
    return light;
}

LfxPointAreaLight lfx_create_rect_area_light(LfxRectLight rect, vec3 normal) {
    LfxPointAreaLight area;
    area.diffuseDirection = lfx_rect_irradiance(rect, normal, area.falloff, area.normalDotLight);
    area.specularDirection = area.diffuseDirection;
    area.lineCosSubtended = 1.0;
    area.sphereSinAlpha = area.sphereSinAlphaSoft = area.diffuseMicroReflectionWeight = 0.0;
    return area;
}
