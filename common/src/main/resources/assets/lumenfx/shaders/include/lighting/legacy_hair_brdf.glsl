// Opaque hair cards with the default deferred light transmittance state.
float lfx_hair_asin(float value) {
    float x = abs(value);
    float angle = (0.5 * LFX_BRDF_PI - 0.156583 * x) * sqrt(1.0 - x);
    return 0.5 * LFX_BRDF_PI - (value >= 0.0 ? angle : LFX_BRDF_PI - angle);
}

float lfx_hair_fresnel(float cosine) {
    const float ratio = (1.0 - 1.55) / (1.0 + 1.55);
    const float f0 = ratio * ratio;
    float x = 1.0 - cosine;
    float x2 = x * x;
    return f0 + (1.0 - f0) * x2 * x2 * x;
}

float lfx_hair_longitudinal(float width, float angle) {
    return exp(-0.5 * angle * angle / (width * width)) / (sqrt(2.0 * LFX_BRDF_PI) * max(width, 0.01));
}

vec3 lfx_hair_legacy_light(vec3 color, float scatter, float specular, float roughness, vec3 fiber, vec3 view,
                          vec3 light, float surfaceVisibility, float transmissionVisibility, float area, float backlit) {
    roughness = clamp(roughness, 1.0 / 255.0, 1.0);
    float width = roughness * roughness;
    float voL = dot(view, light);
    float sinL = clamp(dot(fiber, light), -1.0, 1.0);
    float sinV = clamp(dot(fiber, view), -1.0, 1.0);
    float angle = sinL + sinV;
    float cosDifference = cos(0.5 * abs(lfx_hair_asin(sinV) - lfx_hair_asin(sinL)));
    vec3 projectedLight = light - sinL * fiber;
    vec3 projectedView = view - sinV * fiber;
    float cosPhi = dot(projectedLight, projectedView)
        * inversesqrt(dot(projectedLight, projectedLight) * dot(projectedView, projectedView) + 1e-4);
    float cosHalfPhi = sqrt(clamp(0.5 + 0.5 * cosPhi, 0.0, 1.0));
    const float shift = 0.035;
    const float reflectionShift = -2.0 * shift;
    float sa = sin(reflectionShift);
    float ca = cos(reflectionShift);
    float shiftR = 2.0 * sa * (ca * cosHalfPhi * sqrt(1.0 - sinV * sinV) + sa * sinV);
    float reflection = 0.0;
    if (cosHalfPhi > 0.0) {
        reflection = lfx_hair_longitudinal((area + width) * sqrt(2.0) * cosHalfPhi, angle - shiftR)
            * (0.25 * cosHalfPhi) * lfx_hair_fresnel(sqrt(clamp(0.5 + 0.5 * voL, 0.0, 1.0))) * (specular * 2.0)
            * mix(1.0, backlit, clamp(-voL, 0.0, 1.0));
    }
    float n = 1.19 / cosDifference + 0.36 * cosDifference;
    float a = 1.0 / n;
    float h = cosHalfPhi * (1.0 + a * (0.6 - 0.8 * cosPhi));
    float ah = h * a;
    float ft = 1.0 - lfx_hair_fresnel(cosDifference * sqrt(clamp(1.0 - h * h, 0.0, 1.0)));
    float tt = lfx_hair_longitudinal(area + width * 0.5, angle - shift) * exp(-3.65 * cosPhi - 3.98) * ft * ft * backlit;
    vec3 transmission = tt * pow(abs(color), vec3(0.5 * sqrt(1.0 - ah * ah) / cosDifference));
    float fr = lfx_hair_fresnel(cosDifference * 0.5);
    float trt = lfx_hair_longitudinal(area + width * 2.0, angle - 4.0 * shift)
        * exp(17.0 * cosPhi - 16.78) * (1.0 - fr) * (1.0 - fr) * fr;
    vec3 internalReflection = trt * pow(abs(color), vec3(0.8 / cosDifference));

    vec3 fakeNormal = normalize(view - fiber * dot(view, fiber));
    float wrapped = clamp((dot(fakeNormal, light) + 1.0) * 0.25, 0.0, 1.0);
    float kajiya = 1.0 - abs(dot(fiber, light));
    float diffuseScatter = mix(wrapped, kajiya, 0.33) * scatter / LFX_BRDF_PI;
    float luma = dot(color, vec3(0.2126390059, 0.7151686788, 0.0721923154));
    vec3 tint = transmissionVisibility < 1.0
        ? pow(abs(color / max(luma, 0.0001)), vec3(1.0 - transmissionVisibility)) : vec3(1.0);
    vec3 lobes = (vec3(reflection) + transmission + internalReflection) * surfaceVisibility;
    return max(lobes + sqrt(abs(color)) * diffuseScatter * tint, vec3(0.0));
}

vec3 lfx_hair_legacy_light(vec3 color, float scatter, float specular, float roughness, vec3 fiber, vec3 view,
                          vec3 light, float surfaceVisibility, float transmissionVisibility) {
    return lfx_hair_legacy_light(color, scatter, specular, roughness, fiber, view, light,
            surfaceVisibility, transmissionVisibility, 0.0, 1.0);
}

vec3 lfx_hair_environment(vec3 color, float scatter, float specular, float roughness, vec3 fiber, vec3 view,
                         out vec3 lookupDirection) {
    lookupDirection = normalize(view - fiber * dot(view, fiber));
    return min(vec3(1.0), (2.0 * LFX_BRDF_PI) * lfx_hair_legacy_light(color, scatter, specular, roughness,
            fiber, view, lookupDirection, 1.0, 1.0, 0.2, 0.0));
}
