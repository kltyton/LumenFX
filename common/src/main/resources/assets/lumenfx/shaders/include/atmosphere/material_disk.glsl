#moj_import <lumenfx:atmosphere/material_transmittance.glsl>
#moj_import <lumenfx:atmosphere/light_disk.glsl>

vec3 lfx_material_atmosphere_disk(vec3 direction, vec2 viewportUv, int light, float overrideCos) {
    if (MaterialAtmosphereTopAndValid.y == 0.0 || MaterialAtmosphereTopAndValid.z != 0.0) return vec3(0.0);
    vec4 disk = light == 0 ? MaterialAtmosphereDisk0 : MaterialAtmosphereDisk1;
    vec3 lightDirection = light == 0 ? MaterialAtmosphereDirection0.xyz : MaterialAtmosphereDirection1.xyz;
    float cosHalfApex = overrideCos >= 0.0 ? overrideCos : disk.w;
    float edge = lfx_atmosphere_disk_edge(dot(direction, lightDirection), cosHalfApex);
    if (edge == 0.0 || all(equal(disk.rgb, vec3(0.0)))) return vec3(0.0);
    vec3 origin = MaterialAtmosphereDiskOrigin.xyz + viewportUv.x * MaterialAtmosphereDiskViewportX.xyz
            + viewportUv.y * MaterialAtmosphereDiskViewportY.xyz;
    return disk.rgb * edge * lfx_atmosphere_transmittance_at_position(MaterialAtmosphereTransmittance,
            origin, direction, MaterialAtmospherePlanet.w, MaterialAtmosphereTopAndValid.x);
}
