vec3 lfx_substrate_legacy_eye_normal(vec3 irisNormalPath, vec3 legacyPath) {
#if LFX_LEGACY_IRIS_NORMAL
    return irisNormalPath;
#else
    return legacyPath;
#endif
}
