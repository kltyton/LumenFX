layout(std140) uniform MaterialView {
    vec4 MaterialTemporalAA;
    vec4 MaterialCamera;
    vec4 MaterialWorldTile;
    vec4 MaterialPreViewTranslation;
    vec4 MaterialWorldViewOrigin;
    mat4 MaterialWorldToView;
    mat4 MaterialViewToWorld;
    mat4 MaterialWorldToCamera;
    mat4 MaterialCameraToWorld;
    vec4 MaterialPreViewTranslationHigh;
    vec4 MaterialPreViewTranslationLow;
    vec4 MaterialFirstPersonFov;
    vec4 MaterialFirstPersonScale;
    vec4 MaterialFirstPersonTransform0;
    vec4 MaterialFirstPersonTransform1;
    vec4 MaterialFirstPersonTransform2;
    vec4 MaterialAtmosphereDirection0;
    vec4 MaterialAtmosphereDirection1;
    vec4 MaterialAtmosphereGroundIlluminance0;
    vec4 MaterialAtmosphereGroundIlluminance1;
    vec4 MaterialAtmosphereDistantSkyLuminance;
    vec4 MaterialAtmospherePlanet;
    vec4 MaterialAtmosphereTopAndValid;
    vec4 MaterialAtmosphereOuter0;
    vec4 MaterialAtmosphereOuter1;
    vec4 MaterialAtmosphereSkyForward;
    vec4 MaterialAtmosphereSkyLeft;
    vec4 MaterialAtmosphereSkyUp;
    vec4 MaterialAtmosphereSkyLuminance;
    vec4 MaterialAtmosphereOrthoHeight;
    vec4 MaterialAtmosphereSkyQuantization;
    vec4 MaterialAtmosphereSkyViewport;
    vec4 MaterialAtmosphereDisk0;
    vec4 MaterialAtmosphereDisk1;
    vec4 MaterialAtmosphereDiskOrigin;
    vec4 MaterialAtmosphereDiskViewportX;
    vec4 MaterialAtmosphereDiskViewportY;
    mat4 MaterialAtmosphereAerialWorldToClip;
    mat4 MaterialAtmosphereAerialClipToWorld;
    vec4 MaterialAtmosphereAerialDepth;
    vec4 MaterialAtmosphereAerialOriginShift;
    vec4 MaterialAtmosphereAerialCamera;
    vec4 MaterialEyeAdaptation;
    ivec4 MaterialPostVolume;
};

float lfx_eye_adaptation() {
    return MaterialEyeAdaptation.x;
}

float lfx_eye_adaptation_inverse_scale(float alpha) {
    float exposure = MaterialEyeAdaptation.x;
    return exposure > 0.0 ? exp(-alpha * log(exposure)) : 1.0;
}

float lfx_post_volume_flag_test(float bitIndex) {
    int index = int(bitIndex);
    if (index < 0 || index >= 32) return 0.0;
    return ((MaterialPostVolume.x >> index) & 1) != 0 ? 1.0 : 0.0;
}
