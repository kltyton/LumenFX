#version 330
#moj_import <lumenfx:decal_response.glsl>
#moj_import <lumenfx:shaderpack_decal_receiver.glsl>

uniform sampler2D LfxOriginal;
uniform sampler2D LfxDecal;
uniform sampler2D LfxCapturedDepth;
uniform sampler2D LfxCurrentDepth;
uniform sampler2D LfxReceiverKind;
out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    fragColor = texelFetch(LfxOriginal, pixel, 0);
    float capturedDepth = texelFetch(LfxCapturedDepth, pixel, 0).a;
    float currentDepth = texelFetch(LfxCurrentDepth, pixel, 0).r;
    if (!lfx_shaderpack_decal_depth_matches(capturedDepth, currentDepth)) {
#if LFX_PROJECT_NORMAL
        discard;
#else
        return;
#endif
    }
    int receiver = int(texelFetch(LfxReceiverKind, pixel, 0).a);
    vec4 decal = texelFetch(LfxDecal, pixel, 0);
#if LFX_PROJECT_NORMAL
    if ((receiver & 4) == 0 || fragColor.a == 0.0 || dot(fragColor.rgb, fragColor.rgb) == 0.0
            || (decal.a == 1.0 && all(equal(decal.rgb, vec3(128.0 / 255.0))))) discard;
    fragColor.rgb = lfx_decal_world_normal(normalize(fragColor.rgb), decal);
#else
    if ((receiver & 2) == 0 || (decal.a == 1.0 && decal.b == 0.0)) return;
    float roughness = 1.0 - sqrt(clamp(fragColor.r, 0.0, 1.0));
    float smoothness = 1.0 - clamp(roughness * decal.a + decal.b, 0.0, 1.0);
    fragColor.r = smoothness * smoothness;
#endif
}
