float lfx_sky_view_acos(float value) {
    float x = clamp(abs(value), 0.0, 1.0);
    float x2 = x * x;
    float angle = (-0.2121144 * x + 1.5707288) + 0.0742610 * x2;
    angle = (angle - 0.0187293 * x2 * x) * sqrt(1.0 - x);
    return value >= 0.0 ? angle : 3.141592653589793 - angle;
}

float lfx_sky_horizon_beta(float height, float bottomRadius) {
    return lfx_sky_view_acos(sqrt(max(0.0, height * height - bottomRadius * bottomRadius)) / height);
}

float lfx_sky_view_atan2(float y, float x) {
    const float pi = 3.141592653589793;
    float magnitude = max(abs(x), abs(y));
    if (magnitude == 0.0) return 0.0;
    float ratio = min(abs(x), abs(y)) / magnitude;
    float ratio2 = ratio * ratio;
    float angle = ((0.0872929 * ratio2 - 0.301895) * ratio2 + 1.0) * ratio;
    if (abs(y) > abs(x)) angle = 0.5 * pi - angle;
    if (x < 0.0) angle = pi - angle;
    return y < 0.0 ? -angle : angle;
}

vec2 lfx_sky_view_uv(vec3 localDirection, float height, float radius, bool intersectsGround, vec2 extent) {
    const float pi = 3.141592653589793;
    float beta = lfx_sky_horizon_beta(height, radius);
    float horizon = pi - beta;
    float zenith = lfx_sky_view_acos(localDirection.z);
    float v = intersectsGround ? 0.5 + 0.5 * sqrt(clamp((zenith - horizon) / beta, 0.0, 1.0))
                               : 0.5 - 0.5 * sqrt(clamp(1.0 - zenith / horizon, 0.0, 1.0));
    float u = (lfx_sky_view_atan2(-localDirection.y, -localDirection.x) + pi) / (2.0 * pi);
    return (vec2(u, v) + 0.5 / extent) * (extent / (extent + 1.0));
}
