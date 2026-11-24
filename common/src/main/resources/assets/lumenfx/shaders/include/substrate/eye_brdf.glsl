vec3 lfx_substrate_eye_normal(float packedValue) {
    uint bytes = uint(packedValue);
    vec2 oct = vec2(bytes & 255u, bytes >> 8) * (2.0 / 255.0) - 1.0;
    return lfx_clear_coat_unit_vector(oct);
}

void lfx_substrate_eye_light(vec4 material, vec2 irisMaskDistance, vec3 diffuseColor, vec3 normal, vec3 irisNormal,
                             vec3 irisPlane, vec3 view, vec3 centreDirection, vec3 specularDirection, float noL,
                             vec3 area, out vec3 diffuse, out vec3 specular) {
    float roughness = max(material.w, 0.02);
    float rawNoV = dot(normal, view);
    float noV = clamp(max(abs(rawNoV), 1e-10), 0.0, 1.0);
    vec2 halfVector = lfx_default_lit_half_vector(rawNoV, dot(normal, specularDirection),
            dot(view, specularDirection), area.x, false);
    float edge = 1.0 - halfVector.y;
    float fresnel = mix(material.r, 1.0, edge * edge * edge * edge * edge);
#if LFX_SUBSTRATE_CONSERVATION || LFX_SUBSTRATE_PRESERVATION
    LfxReflectionEnergy energy = lfx_ggx_reflection_energy(roughness, noV, vec3(material.r), vec3(1.0),
            LFX_SUBSTRATE_CONSERVATION != 0, LFX_SUBSTRATE_PRESERVATION != 0);
    float preservation = energy.diffuseTransmission;
    vec3 scatter = energy.multiScatter;
#else
    float preservation = 1.0 - fresnel;
    vec3 scatter = vec3(1.0);
#endif
    float irisNoL = clamp(dot(irisNormal, centreDirection), 0.0, 1.0);
    vec3 causticNormal = normalize(mix(irisPlane, -normal, irisMaskDistance.x * irisMaskDistance.y));
    float power = mix(12.0, 1.0, irisNoL);
    float caustic = 0.8 + 0.2 * (power + 1.0) * pow(clamp(dot(causticNormal, centreDirection), 0.0, 1.0), power);
    diffuse = diffuseColor * (preservation * mix(noL, irisNoL * caustic, irisMaskDistance.x) / LFX_BRDF_PI);
    specular = noL > 0.0 ? scatter * fresnel * lfx_ggx_area(roughness, noV, noL, halfVector, area.x, area.y, area.z,
            true, LFX_SUBSTRATE_ACCURATE != 0) : vec3(0.0);
}
