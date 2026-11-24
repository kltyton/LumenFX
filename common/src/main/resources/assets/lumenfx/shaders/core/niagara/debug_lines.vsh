#version 400
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

uniform usamplerBuffer DebugLines;
out vec4 lineColor;
flat out float occludedScale;

void main() {
    int line = gl_InstanceID * 7;
    int vertex = line + 3 * gl_VertexID;
    vec3 source = uintBitsToFloat(uvec3(texelFetch(DebugLines, vertex).r,
            texelFetch(DebugLines, vertex + 1).r, texelFetch(DebugLines, vertex + 2).r));
    uint color = texelFetch(DebugLines, line + 6).r;
    lineColor = vec4(uvec4(color >> 24u, color >> 16u, color >> 8u, color) & uvec4(255u)) / 255.0;
    occludedScale = ColorModulator.x;
    gl_Position = ProjMat * ModelViewMat * vec4(source.xzy * 0.01 + ModelOffset, 1.0);
}
