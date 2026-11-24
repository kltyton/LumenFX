layout(std140) uniform ExponentialFog {
    vec4 FogPrimary;
    vec4 FogSecondary;
    vec4 FogDensityBounds;
    vec4 FogColorOpacity;
    vec4 FogDirectionalColor;
    vec4 FogLightDirection;
    vec4 FogCamera;
};

float lfx_height_integral(float falloff, float deltaHeight, float originDensity) {
    float x = max(-127.0, falloff * deltaHeight);
    float ln2 = log(2.0);
    float integral = abs(x) > 0.01 ? (1.0 - exp2(-x)) / x : ln2 - 0.5 * ln2 * ln2 * x;
    return originDensity * integral;
}

vec4 lfx_height_fog(vec3 cameraRelativePosition, float excludedCm) {
    vec3 ray = cameraRelativePosition.xzy * 100.0;
    float horizontalSquared = dot(ray.xy, ray.xy);
    if (FogDensityBounds.w > 0.0 && horizontalSquared > FogDensityBounds.w * FogDensityBounds.w)
        ray *= FogDensityBounds.w / sqrt(max(1.0, horizontalSquared));
    float observer = min(FogCamera.x, FogPrimary.z);
    ray.z += FogCamera.x - observer;
    float squaredLength = dot(ray, ray);
    float inverseLength = inversesqrt(max(squaredLength, 0.00000001));
    float distance = squaredLength * inverseLength;
    vec3 direction = ray * inverseLength;
    float lengthToIntegrate = distance;
    float deltaHeight = ray.z;
    float origin0 = FogPrimary.x;
    float origin1 = FogSecondary.x;
    float start = max(excludedCm, FogPrimary.w);
    if (start > 0.0) {
        float fraction = start * inverseLength;
        float excludedHeight = fraction * ray.z;
        float heightAtStart = observer + excludedHeight;
        lengthToIntegrate = (1.0 - fraction) * distance;
        deltaHeight -= excludedHeight;
        origin0 = FogDensityBounds.x * exp2(-max(-127.0, FogPrimary.y * (heightAtStart - FogDensityBounds.y)));
        origin1 = FogSecondary.z * exp2(-max(-127.0, FogSecondary.y * (heightAtStart - FogSecondary.w)));
    }
    float integral = lfx_height_integral(FogPrimary.y, deltaHeight, origin0)
        + lfx_height_integral(FogSecondary.y, deltaHeight, origin1);
    float transmission = max(clamp(exp2(-integral * lengthToIntegrate), 0.0, 1.0), FogColorOpacity.a);
    vec3 directional = vec3(0.0);
    if (FogLightDirection.w >= 0.0) {
        float lobe = pow(clamp(dot(direction, FogLightDirection.xyz), 0.0, 1.0), FogDirectionalColor.w);
        float directionalTransmission = clamp(exp2(-integral * max(lengthToIntegrate - FogLightDirection.w, 0.0)), 0.0, 1.0);
        directional = FogDirectionalColor.rgb * lobe * (1.0 - directionalTransmission);
    }
    if (FogDensityBounds.z > 0.0 && distance > FogDensityBounds.z) {
        transmission = 1.0;
        directional = vec3(0.0);
    }
    return vec4(FogColorOpacity.rgb * (1.0 - transmission) + directional, transmission);
}
