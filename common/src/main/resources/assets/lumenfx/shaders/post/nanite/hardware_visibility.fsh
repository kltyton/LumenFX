#version 330

flat in uvec2 primitiveData;
noperspective in float lfxNaniteUnclippedDepth;
out uint primitiveId;

void main() {
    if ((primitiveData.y & 4u) != 0u || !gl_FrontFacing && (primitiveData.y & 1u) == 0u) discard;
    if ((primitiveData.y & 8u) != 0u) {
        if (lfxNaniteUnclippedDepth < 0.0) discard;
        gl_FragDepth = min(lfxNaniteUnclippedDepth, 1.0);
    } else gl_FragDepth = gl_FragCoord.z;
    primitiveId = primitiveData.x;
}
