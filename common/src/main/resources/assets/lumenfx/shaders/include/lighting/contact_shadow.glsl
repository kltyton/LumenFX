#moj_import <lumenfx:math/interleaved_gradient_noise.glsl>

layout(std140) uniform PointContactView {
    mat4 PointWorldToContactClip;
    vec4 PointContactDepthDirection;
    vec4 PointContactScreenScale;
};

float lfx_contact_dither(vec2 pixel) {
    return lfx_interleaved_gradient_noise(pixel, PointContactScreenScale.z);
}

float lfx_cast_contact_ray(vec3 origin, vec3 direction, float rayLength, float dither, bool allowStartDepth, out vec2 hitUV) {
    vec4 startClip = PointWorldToContactClip * vec4(origin, 1.0);
    vec4 directionClip = PointWorldToContactClip * vec4(direction * rayLength, 0.0);
    vec4 endClip = startClip + directionClip;
    vec3 start = startClip.xyz / startClip.w;
    vec3 end = endClip.xyz / endClip.w;
    vec3 rayStep = end - start;
    vec4 depthClip = startClip + PointContactDepthDirection * rayLength;
    vec3 depth = depthClip.xyz / depthClip.w;
    const float stepSize = 1.0 / 8.0;
    float tolerance = abs(depth.z - start.z) * stepSize * 2.0;
    float startDepth = textureLod(SceneDepth, start.xy * 0.5 + 0.5, 0.0).r;
    float sampleTime = (dither - 0.5) * stepSize + stepSize;
#ifdef LFX_CONTACT_SUBSTRATE_TRACE
    vec3 samplePosition = start + rayStep * sampleTime;
    vec2 sampleUV = samplePosition.xy * 0.5 + 0.5;
    float sampleZ = samplePosition.z + tolerance;
    float result = rayLength * sampleTime;
    vec2 uvStep = rayStep.xy * stepSize * 0.5;
    float zStep = rayStep.z * stepSize;
    float resultStep = rayLength * stepSize;
    for (int i = 0; i < 8; ++i) {
        float sampled = textureLod(SceneDepth, sampleUV, 0.0).r;
        if (abs(sampleZ - sampled) < tolerance && (sampled != startDepth || allowStartDepth)) {
            hitUV = sampleUV;
            return all(lessThan(abs(sampleUV - 0.5), vec2(0.5))) ? result : -1.0;
        }
        sampleUV += uvStep; sampleZ += zStep; result += resultStep;
    }
#else
    for (int i = 0; i < 8; ++i) {
        vec3 samplePosition = start + rayStep * sampleTime;
        vec2 sampleUV = samplePosition.xy * 0.5 + 0.5;
        float sampled = textureLod(SceneDepth, sampleUV, 0.0).r;
        if ((sampled != startDepth || allowStartDepth) && abs(samplePosition.z - sampled + tolerance) < tolerance) {
            hitUV = sampleUV;
            bool inside = all(greaterThan(samplePosition.xy, vec2(-1.0))) && all(lessThan(samplePosition.xy, vec2(1.0)));
            return inside ? rayLength * sampleTime : -1.0;
        }
        sampleTime += stepSize;
    }
#endif
    return -1.0;
}
