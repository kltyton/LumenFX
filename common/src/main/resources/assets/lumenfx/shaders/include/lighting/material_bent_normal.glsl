struct LfxShadingOcclusion {
    vec3 bentNormal;
    float diffuse;
    float specular;
};

float lfx_specular_gaussian_log(float roughness, vec3 normal, vec3 view, vec3 axis, float sharpness) {
    float r = max(roughness, 0.02);
    float nu = 1.0 / (r * r * r * r);
    float noV = clamp(abs(dot(normal, view)) + 1e-5, 0.0, 1.0);
    float sx = 2.0 * sharpness * noV * noV;
    float sy = 2.0 * sharpness;
    vec3 halfVector = normalize(view + axis);
    float exponent = (2.0 * nu * sy / (nu + sy)) * (dot(normal, halfVector) - 1.0);
    return exponent - 0.5 * (log(nu + sx) + log(nu + sy));
}

LfxShadingOcclusion lfx_material_shading_occlusion(vec3 normal, vec3 authoredBentNormal, vec3 view,
                                                  float roughness, float materialAo, bool authored) {
    float ao = clamp(materialAo, 0.0, 1.0);
    if (!authored) return LfxShadingOcclusion(normal, ao, ao);
    if (ao == 0.0) return LfxShadingOcclusion(authoredBentNormal, 0.0, 0.0);

    // BasePassCommon and SphericalGaussian: multiply cosine and visible lobes for diffuse,
    // and divide the convolved GGX response by the unoccluded hemisphere for specular.
    float visible = (0.75 + 1.25 * sqrt(1.0 - ao)) / ao;
    const float hemisphere = 0.81;
    const float amplitude = hemisphere / (1.0 - exp(-2.0 * hemisphere));
    vec3 axis = 2.133 * normal + visible * authoredBentNormal;
    float sharpness = length(axis);
    float integral = sharpness < 1e-4 ? 2.0 : (1.0 - exp(-2.0 * sharpness)) / sharpness;
    float diffuse = (6.28318530718 * 1.17 * amplitude * 0.42276995)
            * exp(sharpness - 2.133 - visible) * integral;
    float specular = lfx_specular_gaussian_log(roughness, normal, view, authoredBentNormal, visible)
            - lfx_specular_gaussian_log(roughness, normal, view, normal, hemisphere);
    return LfxShadingOcclusion(sharpness == 0.0 ? normal : axis / sharpness,
            clamp(diffuse, 0.0, 1.0), exp(min(specular, 0.0)));
}
