#version 330
#moj_import <lumenfx:volumetric_common.glsl>
#moj_import <lumenfx:fog_history_reprojection.glsl>

uniform sampler2D MaterialSampler;
uniform sampler2D HistorySampler;

out vec4 fragColor;

void main() {
    ivec3 cell;
    if (!lfx_atlas_cell(cell)) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 historyUV;
    float historyAlpha;
    if (!lfx_reproject_fog_history(cell, historyUV, historyAlpha)) {
        fragColor = vec4(0.0);
        return;
    }
    vec2 gridXY = vec2(cell.xy) + 0.5;

    vec4 material = lfx_sample_atlas(MaterialSampler,
            vec3(gridXY, float(cell.z) + 0.5), FogGrid, FogAtlas);
    float extinction = material.a;
    vec4 current = vec4(
            FogHistoryBounds.w * material.rgb,
            extinction);

    if (historyAlpha > 0.0) {
        vec3 sampleUV = min(historyUV, vec3(FogUvBounds.z, FogUvBounds.w, 1.0));
        vec3 previousLogical = vec3(sampleUV.xy * FogPreviousGrid.xy,
                sampleUV.z * FogPreviousGrid.z);
        vec4 history = lfx_sample_atlas(HistorySampler, previousLogical,
                FogPreviousGrid, FogPreviousAtlas);
        history.rgb *= FogHistoryBounds.z;
        current = mix(current, history, historyAlpha);
    }

    fragColor = lfx_positive_finite_vec(current);
}
