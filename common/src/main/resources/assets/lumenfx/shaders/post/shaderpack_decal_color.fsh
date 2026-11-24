#version 330
#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:shaderpack_decal_receiver.glsl>

uniform sampler2D LfxEncodedScene;
uniform sampler2D LfxReceiverBaseDepth;
uniform sampler2D LfxReceiverDiffuse;
uniform sampler2D LfxReceiverDepth;
uniform sampler2D LfxDecalColor;
#if LFX_DECAL_AMBIENT_ONLY
uniform sampler2D LfxDecalAmbient;
#endif
out vec4 fragColor;

vec3 lfx_decode_receiver(vec3 encoded, int encoding) {
    if (encoding == 0) return encoded;
    if (encoding == 2) return pow(max(encoded, vec3(0.0)), vec3(2.2));
    return srgbToLinear(encoded);
}

vec3 lfx_encode_receiver(vec3 linear, int encoding) {
    if (encoding == 0) return linear;
    if (encoding == 2) return pow(max(linear, vec3(0.0)), vec3(1.0 / 2.2));
    return linearToSrgb(linear);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 response = texelFetch(LfxReceiverDiffuse, pixel, 0);
    vec4 base = texelFetch(LfxReceiverBaseDepth, pixel, 0);
    float sceneDepth = texelFetch(LfxReceiverDepth, pixel, 0).r;
    if (response.a == 0.0 || !lfx_shaderpack_decal_depth_matches(base.a, sceneDepth)) discard;
    vec4 decal = texelFetch(LfxDecalColor, pixel, 0);
    vec3 scene = texelFetch(LfxEncodedScene, pixel, 0).rgb;
    vec3 material = lfx_decode_receiver(base.rgb, LFX_DECAL_MATERIAL_ENCODING);
    vec3 layered = material * decal.a + decal.rgb;
    vec3 after = scene + (lfx_encode_receiver(layered, LFX_DECAL_MATERIAL_ENCODING) - base.rgb) * response.rgb;
#if LFX_DECAL_AMBIENT_ONLY
    float ao = texelFetch(LfxDecalAmbient, pixel, 0).r;
    if (ao == 1.0) discard;
    vec3 before = after;
    after = scene + (lfx_encode_receiver(layered * ao, LFX_DECAL_MATERIAL_ENCODING) - base.rgb) * response.rgb;
#else
    if (decal.a == 1.0 && all(equal(decal.rgb, vec3(0.0)))) discard;
    vec3 before = scene;
#endif
    fragColor = vec4(lfx_decode_receiver(after, LFX_DECAL_SCENE_ENCODING)
                   - lfx_decode_receiver(before, LFX_DECAL_SCENE_ENCODING), 0.0);
}
