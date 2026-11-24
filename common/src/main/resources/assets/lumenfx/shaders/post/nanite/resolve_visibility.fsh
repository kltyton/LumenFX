#version 330

uniform usampler2D HardwareVisibility;
uniform sampler2D HardwareDepth;
uniform usamplerBuffer NaniteSoftwareDepth;
uniform usamplerBuffer NaniteSoftwarePrimitives;

layout(std140) uniform NaniteResolveOffsets {
    ivec4 ResolveOffsets;
};

out uint primitiveId;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int index = pixel.y * ResolveOffsets.z + pixel.x;
    uint hardwareId = texelFetch(HardwareVisibility, pixel, 0).r;
    float hardwareZ = texelFetch(HardwareDepth, pixel, 0).r;
    uint softwareId = texelFetch(NaniteSoftwarePrimitives, ResolveOffsets.y + index).r;
    float softwareZ = uintBitsToFloat(texelFetch(NaniteSoftwareDepth, ResolveOffsets.x + index).r);
    bool softwareWins = softwareId != 0u && (softwareZ > hardwareZ || (softwareZ == hardwareZ && softwareId > hardwareId));
    primitiveId = softwareWins ? softwareId : hardwareId;
    gl_FragDepth = softwareWins ? softwareZ : hardwareZ;
}
