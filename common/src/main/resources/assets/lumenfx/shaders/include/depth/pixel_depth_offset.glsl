vec3 lfxDepthOffsetWorldPosition;
float lfxMaterialDeviceZ;
float lfxDepthOffsetW = 0.0;
float lfxDepthOffsetZ = 0.0;

float lfx_apply_pixel_depth_offset(float offsetCm, bool alongCameraVector) {
#if LFX_NANITE_MATERIAL
    float deviceZ = lfxNaniteGeometry.surfaceDepth;
#else
    float deviceZ = gl_FragCoord.z;
#endif
    vec3 viewForward = -normalize(cross(CameraRight.xyz, CameraUp.xyz));
    if (ProjMat[3][3] >= 1.0) {
        float shifted = deviceZ + max(offsetCm, 0.0) / DepthTransform.x;
        float offset = deviceZ - shifted;
        lfxDepthOffsetWorldPosition = effectPosition + viewForward * (offset * 0.01);
        lfxDepthOffsetZ = offset * (CameraRight.w > 0.5 ? 2.0 : 1.0);
        return shifted;
    }
    vec3 pixelForward = normalize(effectPosition);
    if (alongCameraVector) offsetCm *= dot(pixelForward, viewForward);
    lfxDepthOffsetWorldPosition = effectPosition + pixelForward * (max(offsetCm, 0.0) * 0.01);
#if LFX_NANITE_MATERIAL
    float w = 1.0 / lfxNaniteGeometry.inverseClipW;
#else
    float w = 1.0 / gl_FragCoord.w;
#endif
    float shifted = min(deviceZ * w / (w + offsetCm * 0.01), deviceZ);
    lfxDepthOffsetW = shifted == 0.0 ? 0.0 : (deviceZ - shifted) * w / shifted;
    return shifted;
}
