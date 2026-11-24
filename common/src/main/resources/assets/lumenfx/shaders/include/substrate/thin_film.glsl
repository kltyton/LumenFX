const float lfx_thin_film_pi = 3.141592653589793;

float lfx_thin_film_fifth_power(float x) {
    float squared = x * x;
    return squared * squared * x;
}

vec3 lfx_thin_film_sensitivity(float opticalDistance, float shift) {
    float phase = (2.0 * lfx_thin_film_pi * opticalDistance) * 1.0e-6;
    vec3 amplitude = vec3(5.4856e-13, 4.4201e-13, 5.2481e-13);
    vec3 center = vec3(1.6810e6, 1.7953e6, 2.2084e6);
    vec3 variance = vec3(4.3278e9, 9.3046e9, 6.6121e9);
    vec3 xyz = amplitude * sqrt(2.0 * lfx_thin_film_pi * variance)
            * cos(center * phase + shift) * exp((-variance * phase) * phase);
    xyz.x += 9.7470e-14 * sqrt(2.0 * lfx_thin_film_pi * 4.5282e9)
            * cos(2.2399e6 * phase + shift) * exp((-4.5282e9 * phase) * phase);
    return xyz / 1.0685e-7;
}

vec3 lfx_thin_film_reflectance(float cosine, vec3 baseF0, float normalizedThickness, float filmIor) {
    if (cosine <= 0.0) return vec3(0.0);
    float thickness = normalizedThickness * 10.0;
    float interfaceIor = mix(1.0, filmIor, smoothstep(0.0, 0.03, thickness));
    float relativeIor = 1.0 / interfaceIor;
    float filmCosine = sqrt(1.0 - (relativeIor * relativeIor) * (1.0 - cosine * cosine));
    float reflectanceRoot = (filmIor - 1.0) / (filmIor + 1.0);
    float interfaceF0 = reflectanceRoot * reflectanceRoot;
    float grazing = lfx_thin_film_fifth_power(1.0 - cosine);
    float firstReflection = clamp(50.0 * interfaceF0, 0.0, 1.0) * grazing + (1.0 - grazing) * interfaceF0;
    float firstTransmission = 1.0 - firstReflection;
    vec3 roundTripReflection = firstReflection * baseF0;
    vec3 amplitude = sqrt(roundTripReflection);
    vec3 internalReflection = (firstTransmission * firstTransmission) * baseF0 / (1.0 - roundTripReflection);
    vec3 xyz = (firstReflection + internalReflection) * lfx_thin_film_sensitivity(0.0, 0.0);
    vec3 coefficient = internalReflection - firstTransmission;
    float opticalDistance = thickness * filmCosine;
    for (int harmonic = 1; harmonic <= 3; ++harmonic) {
        coefficient *= amplitude;
        float order = float(harmonic);
        xyz += coefficient * 2.0 * lfx_thin_film_sensitivity(order * opticalDistance, order * lfx_thin_film_pi);
    }
    const mat3 xyzToRgb = mat3(2.3706743, -0.5138850, 0.0052982,
                             -0.9000405, 1.4253036, -0.0146949,
                             -0.4706338, 0.0885814, 1.0093968);
    return clamp(xyzToRgb * xyz, vec3(0.0), vec3(1.0));
}

vec3 lfx_substrate_thin_film_f0(float cosine, vec3 f0, vec3 f90, float thickness, float ior) {
    vec3 reflectance = lfx_thin_film_reflectance(cosine, f0, thickness, ior);
    float reference = min(0.99, lfx_thin_film_fifth_power(1.0 - cosine));
    reflectance = max(reflectance, vec3(reference));
    vec3 thinF0 = (reflectance - f90 * reference) / (1.0 - reference);
    float microOcclusion = clamp(50.0 * max(max(f0.x, f0.y), f0.z), 0.0, 1.0);
    return max(vec3(0.02), thinF0) * microOcclusion;
}
