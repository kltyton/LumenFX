#version 330

uniform sampler2D FurthestDepth;
struct ComponentQuery {
    vec4 Rectangle;
    vec4 Depth;
};
layout(std140) uniform OcclusionQueries {
    vec4 QueryView;
    ComponentQuery Components[128];
};
out float Visible;

void main() {
    int index = int(gl_FragCoord.x);
    if (index >= int(QueryView.w)) {
        Visible = 0.0;
        return;
    }
    ComponentQuery query = Components[index];
    vec4 pixels = query.Rectangle * QueryView.xyxy;
    ivec2 edge = ivec2(QueryView.xy) - 1;
    ivec2 low = clamp(ivec2(pixels.xy + 0.5), ivec2(0), edge) >> 1;
    ivec2 high = max(low, clamp(ivec2(pixels.zw - 0.5), ivec2(0), edge) >> 1);
    int mip = 0;
    while (mip < int(QueryView.z) && any(greaterThan(high - low, ivec2(3)))) {
        low >>= 1;
        high >>= 1;
        ++mip;
    }
    ivec2 mipEdge = textureSize(FurthestDepth, mip) - 1;
    low = min(low, mipEdge);
    high = min(high, mipEdge);
    float furthest = 1.0;
    for (int y = low.y; y <= high.y; ++y)
        for (int x = low.x; x <= high.x; ++x)
            furthest = min(furthest, texelFetch(FurthestDepth, ivec2(x, y), mip).r);
    Visible = query.Depth.x >= furthest ? 1.0 : 0.0;
}
