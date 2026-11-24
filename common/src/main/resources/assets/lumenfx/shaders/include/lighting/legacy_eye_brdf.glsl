void lfx_legacy_eye_light(vec4 material, vec4 customData, vec2 sourceMetalSpecular, vec3 diffuseColor,
                          vec3 normal, vec3 view, vec3 light, float noL, vec3 area,
                          out vec3 transmission, out vec3 specular) {
    float irisMask = 1.0 - customData.a;
    vec3 irisNormal, causticNormal;
#if LFX_LEGACY_IRIS_NORMAL
    vec2 worldOct = lfx_clear_coat_octahedron(normal);
    causticNormal = lfx_clear_coat_unit_vector(worldOct + sourceMetalSpecular * 2.0 - 256.0 / 255.0);
    irisNormal = lfx_clear_coat_unit_vector(worldOct + customData.yz * 2.0 - 256.0 / 255.0);
#else
    irisNormal = lfx_clear_coat_unit_vector(customData.yz * 2.0 - 1.0);
    causticNormal = normalize(mix(irisNormal, -normal, irisMask * sourceMetalSpecular.x));
#endif
    float rawNoV = dot(normal, view);
    float noV = clamp(abs(rawNoV) + 1e-5, 0.0, 1.0);
    vec2 halfVector = lfx_default_lit_half_vector(rawNoV, dot(normal, light), dot(view, light), area.x, false);
    float fresnel = mix(material.r, 1.0, pow(1.0 - halfVector.y, 5.0));
    LfxReflectionEnergy energy = lfx_legacy_reflection_energy(material.w, noV, vec3(material.r));
    specular = noL > 0.0 ? energy.multiScatter
            * (fresnel * lfx_default_lit_ggx(material.w, noV, noL, halfVector, area.x, area.y, area.z)) : vec3(0.0);

    float irisNoL = clamp(dot(irisNormal, light), 0.0, 1.0);
    float power = mix(12.0, 1.0, irisNoL);
    float caustic = 0.8 + 0.2 * (power + 1.0) * pow(clamp(dot(causticNormal, light), 0.0, 1.0), power);
#if LFX_LEGACY_ENERGY
    float preservation = energy.diffuseTransmission;
#else
    float preservation = 1.0 - fresnel;
#endif
    transmission = diffuseColor * (preservation * mix(noL, irisNoL * caustic, irisMask) / LFX_BRDF_PI);
}
