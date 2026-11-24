#version 330
#moj_import <lumenfx:volumetric_common.glsl>
#moj_import <lumenfx:height_fog.glsl>
#moj_import <lumenfx:fog_material_position.glsl>

layout(location=0) out vec4 fragColor;
#ifdef LFX_FOG_SCATTERING_OUTPUT
layout(location=1) out vec4 mediumScattering;
#endif

void main() {
    ivec3 cell;
    if (!lfx_atlas_cell(cell)) {
        fragColor = vec4(0.0);
#ifdef LFX_FOG_SCATTERING_OUTPUT
        mediumScattering = vec4(0.0);
#endif
        return;
    }

    float zSlice;
    vec3 worldPosition = lfx_material_cell_world(cell, zSlice);

    float absoluteZ = worldPosition.y * 100.0 + FogFrame.x;
    float densityFirst = FogDensityBounds.x
            * exp2(-FogPrimary.y * (absoluteZ - FogDensityBounds.y));
    float densitySecond = FogSecondary.z
            * exp2(-FogSecondary.y * (absoluteZ - FogSecondary.w));
    float globalDensity = (densityFirst + densitySecond) * 0.5;
    float extinction = max(globalDensity * FogMaterial.x, 0.0);

    vec3 emissive = FogEmissive.rgb;
    if (FogEmissive.w > 0.5) {
        emissive *= globalDensity;
    }

    fragColor = vec4(emissive, extinction);
#ifdef LFX_FOG_SCATTERING_OUTPUT
    mediumScattering = vec4(FogAlbedoPhase.rgb * extinction, extinction);
#endif
}
