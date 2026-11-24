float lfx_atmosphere_disk_edge(float viewDotLight, float cosHalfApex) {
    if (viewDotLight <= cosHalfApex) return 0.0;
    return clamp(2.0 * (viewDotLight - cosHalfApex) / (1.0 - cosHalfApex), 0.0, 1.0);
}
