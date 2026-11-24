#version 430

layout(std140) uniform LfxMotionLogClear {
    uvec4 LfxMotionLogClearRect; // x = originX, y = originY, z = tileWidth, w = tileHeight
    uvec4 LfxMotionLogClearMeta; // x = capacityNodes
};

layout(std430, binding = 1) buffer LfxTranslucentMotionLog {
    uint lfxMotionLogCounter;
    uint lfxMotionLogOriginX;
    uint lfxMotionLogOriginY;
    uint lfxMotionLogWidth;
    uint lfxMotionLogHeight;
    uint lfxMotionLogCapacity;
    uint lfxMotionLogOverflow;
    uint lfxMotionLogPad1;
    uint lfxMotionLogWords[];
};

layout(location = 0) out vec4 lfxLogClearDummy;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    uint originX = LfxMotionLogClearRect.x;
    uint originY = LfxMotionLogClearRect.y;
    uint width = LfxMotionLogClearRect.z;
    uint height = LfxMotionLogClearRect.w;
    ivec2 local = pixel - ivec2(int(originX), int(originY));
    if (local.x == 0 && local.y == 0) {
        lfxMotionLogCounter = 0u;
        lfxMotionLogOriginX = originX;
        lfxMotionLogOriginY = originY;
        lfxMotionLogWidth = width;
        lfxMotionLogHeight = height;
        lfxMotionLogCapacity = LfxMotionLogClearMeta.x;
        lfxMotionLogOverflow = 0u;
        lfxMotionLogPad1 = 0u;
    }
    if (local.x >= 0 && local.y >= 0 && uint(local.x) < width && uint(local.y) < height) {
        lfxMotionLogWords[uint(local.y) * width + uint(local.x)] = 0u;
    }
    lfxLogClearDummy = vec4(0.0);
}
