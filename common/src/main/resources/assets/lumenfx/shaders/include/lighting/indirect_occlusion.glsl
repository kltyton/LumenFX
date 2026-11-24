vec3 lfx_indirect_ao_multibounce(vec3 baseColor, float ao) {
    ao = clamp(ao, 0.0, 1.0);
    vec3 a = 2.0404 * baseColor - 0.3324;
    vec3 b = -4.7951 * baseColor + 0.6417;
    vec3 c = 2.7552 * baseColor + 0.6903;
    return max(vec3(ao), ((ao * a + b) * ao + c) * ao);
}
