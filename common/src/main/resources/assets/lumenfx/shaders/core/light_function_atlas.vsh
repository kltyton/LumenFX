#version 330
layout(std140) uniform LightFunctionAtlasTile {
    vec4 LightFunctionTile;
};

out vec2 lightFunctionAtlasUv;

void main() {
    const vec2 vertices[6] = vec2[6](vec2(0,0), vec2(1,0), vec2(1,1), vec2(0,0), vec2(1,1), vec2(0,1));
    vec2 tile = vertices[gl_VertexID];
    gl_Position = vec4((LightFunctionTile.xy + tile * LightFunctionTile.z) * 2.0 - 1.0, 0.0, 1.0);
    lightFunctionAtlasUv = (tile * LightFunctionTile.w - 0.5) / (LightFunctionTile.w - 1.0);
}
