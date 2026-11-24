uniform sampler2D ConservativeDepthSampler;
uniform sampler2D PreviousConservativeDepthSampler;

float lfx_current_conservative_depth(ivec2 coord) {
    ivec2 size = ivec2(FogGrid.xy + 0.5);
    return texelFetch(ConservativeDepthSampler, clamp(coord, ivec2(0), size - 1), 0).r;
}

vec4 lfx_previous_conservative_gather(ivec2 base) {
    ivec2 size = ivec2(FogPreviousGrid.xy + 0.5);
    ivec2 last = size - 1;
    vec4 depths;
    depths.x = texelFetch(PreviousConservativeDepthSampler, clamp(base + ivec2(0, 1), ivec2(0), last), 0).r;
    depths.y = texelFetch(PreviousConservativeDepthSampler, clamp(base + ivec2(1, 1), ivec2(0), last), 0).r;
    depths.z = texelFetch(PreviousConservativeDepthSampler, clamp(base + ivec2(1, 0), ivec2(0), last), 0).r;
    depths.w = texelFetch(PreviousConservativeDepthSampler, clamp(base + ivec2(0, 0), ivec2(0), last), 0).r;
    return depths;
}

bool lfx_fixup_history_uv(inout vec2 historyUV, float previousCellWindowDepth) {
    vec2 size = FogPreviousGrid.xy;
    vec2 fullResUV = historyUV * size;
    vec2 screenCoord = floor(fullResUV - 0.5);
    vec2 gatherUV = (screenCoord + 1.0) / size;
    vec2 mappedGatherUV = min(gatherUV / FogVolumeRange.zw, FogUvBounds.zw);
    ivec2 base = ivec2(floor(mappedGatherUV * size - 0.5));
    vec4 previousDepths = lfx_previous_conservative_gather(base);
    bvec4 validSamples = lessThan(previousDepths, vec4(previousCellWindowDepth));
    vec2 fullResOffset = fullResUV - screenCoord;

    if (all(validSamples)) {
        return true;
    }
    if (all(validSamples.wz)) {
        historyUV = (screenCoord + vec2(fullResOffset.x, 0.5)) / size;
        return true;
    }
    if (all(validSamples.xy)) {
        historyUV = (screenCoord + vec2(fullResOffset.x, 1.5)) / size;
        return true;
    }
    if (all(validSamples.wx)) {
        historyUV = (screenCoord + vec2(0.5, fullResOffset.y)) / size;
        return true;
    }
    if (all(validSamples.zy)) {
        historyUV = (screenCoord + vec2(1.5, fullResOffset.y)) / size;
        return true;
    }
    if (validSamples.x) {
        historyUV = (screenCoord + vec2(0.5, 1.5)) / size;
        return true;
    }
    if (validSamples.y) {
        historyUV = (screenCoord + vec2(1.5, 1.5)) / size;
        return true;
    }
    if (validSamples.w) {
        historyUV = (screenCoord + vec2(0.5, 0.5)) / size;
        return true;
    }
    if (validSamples.z) {
        historyUV = (screenCoord + vec2(1.5, 0.5)) / size;
        return true;
    }
    return false;
}

bool lfx_reproject_fog_history(ivec3 cell, out vec3 historyUV, out float historyAlpha) {
    vec2 gridXY = vec2(cell.xy) + 0.5;
    vec3 centerWorld = lfx_cell_world_at_slice(gridXY, float(cell.z) + 0.5);
    vec4 previousCenterClip = FogPreviousWorldToClip * vec4(centerWorld, 1.0);
    historyUV.xy = lfx_volume_uv(previousCenterClip) * FogVolumeRange.zw;
    historyUV.z = min(lfx_slice_from_depth(previousCenterClip.w * 100.0) / FogGrid.z, 1.0);
    historyUV = min(historyUV, vec3(FogHistoryBounds.xy, 1.0));

    bool hasValidHistory = true;
    if (FogProjection.w > 0.5) {
        vec3 farWorld = lfx_cell_world_at_slice(gridXY, max(float(cell.z) - 0.5, 0.0));
        vec4 currentFarClip = FogWorldToClip * vec4(farWorld, 1.0);
        float currentCellWindowDepth = lfx_ndc_to_window_z(currentFarClip.z / currentFarClip.w);
        float conservativeDepth = lfx_current_conservative_depth(cell.xy);
        if (conservativeDepth > currentCellWindowDepth) {
            historyAlpha = 0.0;
            return false;
        }

        vec4 previousFarClip = FogPreviousWorldToClip * vec4(farWorld, 1.0);
        float previousCellWindowDepth = lfx_ndc_to_window_z(previousFarClip.z / previousFarClip.w);
        hasValidHistory = lfx_fixup_history_uv(historyUV.xy, previousCellWindowDepth);
    }

    historyAlpha = FogFrame.y;
    if (any(lessThan(historyUV, vec3(0.0)))
            || any(greaterThanEqual(historyUV,
                    vec3(FogHistoryBounds.x, FogHistoryBounds.y, 1.0)))
            || !hasValidHistory) {
        historyAlpha = 0.0;
    }

    return true;
}
