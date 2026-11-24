#version 430

layout(std430, binding = 1) readonly buffer LfxTranslucentMotionLog {
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

uniform sampler2D PreColorDepthSampler;
uniform sampler2D ExistingMotionDepthSampler;
layout(location = 0) out vec4 lfxReplayPreviousNdc;
layout(location = 1) out vec2 lfxReplayDepthCoverage;

const uint LFX_LOCAL_SORT_CAP = 32u;

bool lfx_depth_test(uint compareCode, float fragDepth, float targetDepth) {
    if (compareCode == 0u) return true;
    if (compareCode == 1u) return fragDepth > targetDepth;
    if (compareCode == 2u) return fragDepth == targetDepth;
    if (compareCode == 3u) return fragDepth >= targetDepth;
    if (compareCode == 4u) return fragDepth < targetDepth;
    if (compareCode == 5u) return fragDepth != targetDepth;
    if (compareCode == 6u) return fragDepth <= targetDepth;
    return false;
}

bool lfx_key_less(uvec3 a, uvec3 b) {
    if (a.x != b.x) return a.x < b.x;
    if (a.y != b.y) return a.y < b.y;
    return a.z < b.z;
}

uvec3 lfx_node_key(uint pixelBase, uint nodeId) {
    uint base = pixelBase + (nodeId - 1u) * 12u;
    return uvec3(
            lfxMotionLogWords[base + 1u],
            lfxMotionLogWords[base + 2u],
            lfxMotionLogWords[base + 3u]);
}

void lfx_apply_node(uint pixelBase, uint nodeId,
                    inout float colorDepth,
                    inout float motionDepth,
                    inout bool hasNewVelocity,
                    inout bool invalidatedVelocity,
                    inout vec4 newPrevNdc,
                    inout vec2 newDepthCov) {
    uint base = pixelBase + (nodeId - 1u) * 12u;
    float z = uintBitsToFloat(lfxMotionLogWords[base + 4u]);
    uint flags = lfxMotionLogWords[base + 5u];
    uint compareCode = flags & 15u;
    bool writesColorDepth = (flags & 16u) != 0u;
    bool velocityEligible = (flags & 32u) != 0u;

    if (!lfx_depth_test(compareCode, z, colorDepth)) {
        return;
    }
    float oldC = colorDepth;
    if (writesColorDepth) {
        colorDepth = z;
    }
    if (velocityEligible) {
        if (lfx_depth_test(compareCode, z, motionDepth)) {
            motionDepth = z;
            hasNewVelocity = true;
            invalidatedVelocity = false;
            newPrevNdc = vec4(
                    uintBitsToFloat(lfxMotionLogWords[base + 7u]),
                    uintBitsToFloat(lfxMotionLogWords[base + 8u]),
                    uintBitsToFloat(lfxMotionLogWords[base + 9u]),
                    uintBitsToFloat(lfxMotionLogWords[base + 10u]));
            newDepthCov = vec2(uintBitsToFloat(lfxMotionLogWords[base + 6u]), z);
        }
    } else if (writesColorDepth) {
        if (z > oldC) {
            if (z >= motionDepth && z != motionDepth) {
                motionDepth = z;
                hasNewVelocity = false;
                invalidatedVelocity = true;
            }
        } else if (z < oldC) {
            if (motionDepth <= oldC && z != motionDepth) {
                motionDepth = z;
                hasNewVelocity = false;
                invalidatedVelocity = true;
            }
        }
    }
}

void main() {
    // Defense-in-depth: never commit an overflowed log (sticky flag survives 32-bit counter wraparound).
    if (lfxMotionLogOverflow != 0u || lfxMotionLogCounter > lfxMotionLogCapacity) {
        discard;
    }
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    uint width = lfxMotionLogWidth;
    uint height = lfxMotionLogHeight;
    ivec2 local = pixel - ivec2(int(lfxMotionLogOriginX), int(lfxMotionLogOriginY));
    if (local.x < 0 || local.y < 0 || uint(local.x) >= width || uint(local.y) >= height) {
        discard;
    }
    uint pixelIndex = uint(local.y) * width + uint(local.x);
    uint head = lfxMotionLogWords[pixelIndex];
    if (head == 0u) {
        discard;
    }
    uint pixelBase = width * height;
    uint maxNodes = lfxMotionLogCapacity;
    uint localNodes[LFX_LOCAL_SORT_CAP];
    uint count = 0u;
    uint cursor = head;
    while (cursor != 0u && count < maxNodes) {
        if (count < LFX_LOCAL_SORT_CAP) {
            localNodes[count] = cursor;
        }
        count++;
        uint nextBase = pixelBase + (cursor - 1u) * 12u;
        cursor = lfxMotionLogWords[nextBase];
    }

    float colorDepth = texelFetch(PreColorDepthSampler, pixel, 0).r;
    float motionDepth = texelFetch(ExistingMotionDepthSampler, pixel, 0).r;
    bool hasNewVelocity = false;
    bool invalidatedVelocity = false;
    vec4 newPrevNdc = vec4(0.0);
    vec2 newDepthCov = vec2(0.0, -1.0);

    if (count <= LFX_LOCAL_SORT_CAP) {
        for (uint i = 1u; i < count; i++) {
            uint current = localNodes[i];
            uvec3 currentKey = lfx_node_key(pixelBase, current);
            uint j = i;
            while (j > 0u && lfx_key_less(currentKey, lfx_node_key(pixelBase, localNodes[j - 1u]))) {
                localNodes[j] = localNodes[j - 1u];
                j--;
            }
            localNodes[j] = current;
        }
        for (uint i = 0u; i < count; i++) {
            lfx_apply_node(pixelBase, localNodes[i], colorDepth, motionDepth,
                    hasNewVelocity, invalidatedVelocity, newPrevNdc, newDepthCov);
        }
    } else {
        bool hasLast = false;
        uvec3 lastKey = uvec3(0u);
        for (uint step = 0u; step < count; step++) {
            uint bestNode = 0u;
            uvec3 bestKey = uvec3(0u);
            uint scan = head;
            uint visited = 0u;
            while (scan != 0u && visited < count) {
                uvec3 key = lfx_node_key(pixelBase, scan);
                if ((!hasLast || lfx_key_less(lastKey, key))
                        && (bestNode == 0u || lfx_key_less(key, bestKey))) {
                    bestNode = scan;
                    bestKey = key;
                }
                scan = lfxMotionLogWords[pixelBase + (scan - 1u) * 12u];
                visited++;
            }
            if (bestNode == 0u) break;
            lfx_apply_node(pixelBase, bestNode, colorDepth, motionDepth,
                    hasNewVelocity, invalidatedVelocity, newPrevNdc, newDepthCov);
            lastKey = bestKey;
            hasLast = true;
        }
    }

    if (hasNewVelocity) {
        gl_FragDepth = motionDepth;
        lfxReplayPreviousNdc = newPrevNdc;
        lfxReplayDepthCoverage = newDepthCov;
    } else if (invalidatedVelocity) {
        gl_FragDepth = motionDepth;
        lfxReplayPreviousNdc = vec4(0.0);
        lfxReplayDepthCoverage = vec2(0.0, -1.0);
    } else {
        discard;
    }
}
