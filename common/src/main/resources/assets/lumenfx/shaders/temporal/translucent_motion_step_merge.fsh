#version 330

layout(std140) uniform LfxMotionStepMerge {
    uvec4 LfxMotionStepMergeInfo; // x = compareCode, y = writesColorDepth (0 or 1)
};

uniform sampler2D StepPreColorDepthSampler;
uniform sampler2D ColorReplayDepthSampler;
uniform sampler2D ExistingMotionDepthSampler;
uniform sampler2D StepPreviousNdcSampler;
uniform sampler2D StepDepthCoverageSampler;
layout(location = 0) out vec4 lfxStepPreviousNdc;
layout(location = 1) out vec2 lfxStepDepthCoverage;

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

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec2 stepCov = texelFetch(StepDepthCoverageSampler, pixel, 0).rg;
    if (stepCov.y > -1.5 && stepCov.y < 0.0) {
        discard;
    }
    uint compareCode = LfxMotionStepMergeInfo.x;
    bool writesColorDepth = LfxMotionStepMergeInfo.y != 0u;
    float motionDepth = texelFetch(ExistingMotionDepthSampler, pixel, 0).r;

    if (stepCov.y >= 0.0) {
        if (!lfx_depth_test(compareCode, stepCov.y, motionDepth)) {
            discard;
        }
        gl_FragDepth = stepCov.y;
        lfxStepPreviousNdc = texelFetch(StepPreviousNdcSampler, pixel, 0);
        lfxStepDepthCoverage = stepCov;
        return;
    }

    if (!writesColorDepth) {
        discard;
    }
    float oldC = texelFetch(StepPreColorDepthSampler, pixel, 0).r;
    float z = texelFetch(ColorReplayDepthSampler, pixel, 0).r;
    if (z > oldC) {
        if (z >= motionDepth && z != motionDepth) {
            gl_FragDepth = z;
            lfxStepPreviousNdc = vec4(0.0);
            lfxStepDepthCoverage = vec2(0.0, -1.0);
            return;
        }
    } else if (z < oldC) {
        if (motionDepth <= oldC && z != motionDepth) {
            gl_FragDepth = z;
            lfxStepPreviousNdc = vec4(0.0);
            lfxStepDepthCoverage = vec2(0.0, -1.0);
            return;
        }
    }
    discard;
}
