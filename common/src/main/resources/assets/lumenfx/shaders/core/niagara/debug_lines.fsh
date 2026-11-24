#version 400

#if LFX_DEBUG_DEPTH
uniform sampler2D DebugDepth;
#endif
in vec4 lineColor;
flat in float occludedScale;
out vec4 fragColor;

void main() {
    float alpha = lineColor.a;
#if LFX_DEBUG_DEPTH
    float sceneDepth = texelFetch(DebugDepth, ivec2(gl_FragCoord.xy), 0).r;
    if (gl_FragCoord.z < sceneDepth) alpha *= occludedScale;
#endif
    fragColor = vec4(lineColor.rgb * alpha, alpha);
}
