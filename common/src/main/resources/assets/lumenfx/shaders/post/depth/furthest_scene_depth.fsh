#version 330
uniform sampler2D ParentDepth;
out float FurthestDepth;

void main() {
    ivec2 edge = textureSize(ParentDepth, 0) - ivec2(1);
    ivec2 first = min(ivec2(gl_FragCoord.xy) * 2, max(edge - 1, ivec2(0)));
    ivec2 next = min(first + 1, edge);
    float a = texelFetch(ParentDepth, first, 0).r;
    float b = texelFetch(ParentDepth, ivec2(next.x, first.y), 0).r;
    float c = texelFetch(ParentDepth, ivec2(first.x, next.y), 0).r;
    float d = texelFetch(ParentDepth, next, 0).r;
    FurthestDepth = min(min(a, b), min(c, d));
}
