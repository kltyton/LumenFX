#version 330
uniform sampler2D InputCount;
out float tileCount;

void main() {
    ivec2 begin = ivec2(gl_FragCoord.xy) * 2;
    ivec2 end = min(begin + 2, textureSize(InputCount, 0));
    float count = 0.0;
    for (int y = begin.y; y < end.y; ++y) for (int x = begin.x; x < end.x; ++x)
        count += texelFetch(InputCount, ivec2(x, y), 0).r;
    tileCount = count;
}
