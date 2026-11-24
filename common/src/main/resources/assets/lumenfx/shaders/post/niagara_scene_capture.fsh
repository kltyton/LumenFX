#version 330
#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:capture_film_curve.glsl>

uniform sampler2D CaptureColor;
uniform sampler2D CaptureDepth;
uniform sampler2D CaptureBaseColor;
uniform sampler2D CaptureNormal;
uniform sampler2D CaptureGeometry;

layout(std140) uniform CaptureOutput {
    vec4 DepthTransform;
    ivec4 CaptureSettings;
    mat4 CaptureViewToWorld;
};

out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (CaptureSettings.z != 0) pixel.y = CaptureSettings.y - 1 - pixel.y;
    vec4 scene = texelFetch(CaptureColor, pixel, 0);
    float deviceDepth = texelFetch(CaptureDepth, pixel, 0).r;
    float divisor = deviceDepth * DepthTransform.y - DepthTransform.x;
    float depth = divisor == 0.0 ? 3.402823e38 : (DepthTransform.z - deviceDepth * DepthTransform.w) / divisor;
    vec3 linear = srgbToLinear(scene.rgb);
    int mode = CaptureSettings.x;
    if (mode == 0) fragColor = vec4(linear, clamp(1.0 - scene.a, 0.0, 1.0));
    else if (mode == 1) fragColor = vec4(linear, 0.0);
    else if (mode == 3) fragColor = vec4(linear, depth);
    else if (mode == 4) fragColor = vec4(depth, 0.0, 0.0, 0.0);
    else if (mode == 5) {
        vec4 encoded = fract(vec4(1.0, 255.0, 65025.0, 16581375.0) * (1.0 - deviceDepth));
        fragColor = vec4(encoded.xyz - encoded.yzw / 255.0, 1.0);
    } else if (mode == 6 || mode == 7) {
        int flags = int(texelFetch(CaptureGeometry, pixel, 0).z);
        if ((flags & 128) == 0 || (flags & 256) != 0) fragColor = vec4(0.0);
        else if (mode == 6) {
            vec3 normal = (mat3(CaptureViewToWorld) * texelFetch(CaptureNormal, pixel, 0).xyz).xzy;
            float squareLength = dot(normal, normal);
            fragColor = vec4(squareLength > 0.0 ? normal * inversesqrt(squareLength) : vec3(0.0), 0.0);
        } else fragColor = vec4(srgbToLinear(texelFetch(CaptureBaseColor, pixel, 0).rgb), 0.0);
    } else if (mode == 8) fragColor = vec4(linear, 1.0);
    else {
        vec3 tone = ap1ToSrgb() * filmToneMap(srgbToAp1() * linear, 0.88, 0.55, 0.26, 0.0, 0.04);
        fragColor = vec4(mode == 2 ? linearToSrgb(clamp(tone, 0.0, 1.0)) : tone, 1.0);
    }
}
