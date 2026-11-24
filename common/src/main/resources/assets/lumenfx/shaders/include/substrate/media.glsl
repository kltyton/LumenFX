vec3 lfx_substrate_transmittance_mfp(vec3 transmission, float thicknessCm) {
    float thicknessMeters = max(thicknessCm * 0.01, 1.0e-12);
    vec3 extinction = -log(clamp(transmission, vec3(1.0e-12), vec3(1.0))) / thicknessMeters;
    return (1.0 / max(vec3(1.0e-12), extinction)) * 100.0;
}
