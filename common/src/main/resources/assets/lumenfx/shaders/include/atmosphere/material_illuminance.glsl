#moj_import <lumenfx:atmosphere/material_transmittance.glsl>

vec3 lfx_material_atmosphere_illuminance(vec3 translatedWorldCm, int light) {
    if (MaterialAtmosphereTopAndValid.y == 0.0) return vec3(0.0);
    vec3 outer = light == 0 ? MaterialAtmosphereOuter0.xyz : MaterialAtmosphereOuter1.xyz;
    if (all(equal(outer, vec3(0.0)))) return outer;
    vec3 direction = light == 0 ? MaterialAtmosphereDirection0.xyz : MaterialAtmosphereDirection1.xyz;
    vec3 planetPosition = (translatedWorldCm - MaterialAtmospherePlanet.xyz) * 0.00001;
    return outer * lfx_atmosphere_transmittance_at_position(MaterialAtmosphereTransmittance, planetPosition,
            direction, MaterialAtmospherePlanet.w, MaterialAtmosphereTopAndValid.x);
}
