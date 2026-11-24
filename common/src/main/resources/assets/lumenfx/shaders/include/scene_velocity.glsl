vec4 lfx_encode_scene_velocity(vec3 velocity, uint temporalMask) {
    velocity.xy = sign(velocity.xy) * sqrt(abs(velocity.xy)) * (2.0 / sqrt(2.0));
    vec4 encoded;
    encoded.xy = velocity.xy * (0.499 * 0.5) + 32767.0 / 65535.0;
    uint depthBits = floatBitsToUint(velocity.z);
    encoded.z = clamp(float((depthBits >> 16u) & 0xffffu) / 65535.0 + 0.1 / 65535.0, 0.0, 1.0);
    encoded.w = clamp(float((depthBits & 0xfffcu) | (temporalMask & 3u)) / 65535.0 + 0.1 / 65535.0, 0.0, 1.0);
    return encoded;
}

vec3 lfx_decode_scene_velocity(vec4 encoded) {
    const float inverseScale = 1.0 / (0.499 * 0.5);
    vec3 velocity;
    velocity.xy = encoded.xy * inverseScale - 32767.0 / 65535.0 * inverseScale;
    velocity.xy = (velocity.xy * abs(velocity.xy)) * 0.5;
    uint high = uint(round(encoded.z * 65535.0));
    uint low = uint(round(encoded.w * 65535.0)) & 0xfffcu;
    velocity.z = uintBitsToFloat((high << 16u) | low);
    return velocity;
}

bool lfx_velocity_has_pixel_animation(vec4 encoded) {
    return (uint(round(encoded.w * 65535.0)) & 1u) != 0u;
}
