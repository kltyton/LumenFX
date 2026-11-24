vec3 lfx_subsurface_transmitted_color(vec3 color, float transmittanceDistance) {
    vec3 raw = exp(log(clamp(color, vec3(1e-12), vec3(1.0))) / transmittanceDistance);
    vec4 p = raw.g < raw.b ? vec4(raw.bg, -1.0, 2.0 / 3.0) : vec4(raw.gb, 0.0, -1.0 / 3.0);
    vec4 q = raw.r < p.x ? vec4(p.xyw, raw.r) : vec4(raw.r, p.yzx);
    float chroma = q.x - min(q.w, q.y);
    float hue = abs((q.w - q.y) / (6.0 * chroma + 1e-10) + q.z);
    float saturation = chroma / (q.x + 1e-10);
    float value = max(max(color.r, color.g), color.b);
    float h = hue * 6.0;
    vec3 rgb = clamp(vec3(abs(h - 3.0) - 1.0, 2.0 - abs(h - 2.0), 2.0 - abs(h - 4.0)), 0.0, 1.0);
    return ((rgb - 1.0) * saturation + 1.0) * value;
}

vec3 lfx_subsurface_wrap_light(vec3 color, bool twoSided, float opacity, float materialAo,
                               vec3 normal, vec3 view, vec3 direction, float shadowTransmittance,
                               float transmittanceDistance) {
    float noL = dot(normal, direction);
    float voL = dot(view, direction);
    if (twoSided) {
        float wrapped = clamp((0.5 - noL) / 2.25, 0.0, 1.0);
        float halfAngle = clamp(-voL, 0.0, 1.0);
        const float alphaSquared = 0.36;
        float slope = (halfAngle * alphaSquared - halfAngle) * halfAngle + 1.0;
        return color * (wrapped * alphaSquared / (LFX_BRDF_PI * slope * slope));
    }
    float inScatter = pow(clamp(-voL, 0.0, 1.0), 12.0) * mix(3.0, 0.1, opacity);
    float wrapped = pow(clamp((noL + 0.5) / 1.5, 0.0, 1.0), 1.5) * (2.5 / 1.5);
    float backScatter = materialAo * mix(1.0, wrapped, opacity) / (2.0 * LFX_BRDF_PI);
    vec3 transmitted = lfx_subsurface_transmitted_color(color, transmittanceDistance);
    return mix(transmitted, color, shadowTransmittance) * mix(backScatter, 1.0, inScatter);
}

vec3 lfx_subsurface_transmission(int model, vec4 custom, float materialAo,
                                 vec3 normal, vec3 view, vec3 diffuseDirection, float shadowTransmittance,
                                 float transmittanceDistance) {
    return lfx_subsurface_wrap_light(custom.rgb * custom.rgb, model == LFX_SHADING_MODEL_TWOSIDED_FOLIAGE,
            custom.a, materialAo, normal, view, diffuseDirection, shadowTransmittance, transmittanceDistance);
}
