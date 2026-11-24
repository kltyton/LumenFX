#version 330
#moj_import <lumenfx:decal_uniforms.glsl>

const vec3 lfxDecalCorners[8] = vec3[8](
    vec3(-1, -1, -1), vec3(1, -1, -1), vec3(1, 1, -1), vec3(-1, 1, -1),
    vec3(-1, -1, 1), vec3(1, -1, 1), vec3(1, 1, 1), vec3(-1, 1, 1));
const int lfxDecalIndices[36] = int[36](
    0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7,
    0, 1, 5, 0, 5, 4, 3, 7, 6, 3, 6, 2,
    0, 4, 7, 0, 7, 3, 1, 2, 6, 1, 6, 5);
flat out vec3 decalFaceNormal;

void main() {
    int first = (gl_VertexID / 3) * 3;
    vec3 a = lfxDecalCorners[lfxDecalIndices[first]];
    vec3 edgeA = (DecalLocalToWorld * vec4(lfxDecalCorners[lfxDecalIndices[first + 1]] - a, 0.0)).xyz;
    vec3 edgeB = (DecalLocalToWorld * vec4(lfxDecalCorners[lfxDecalIndices[first + 2]] - a, 0.0)).xyz;
    decalFaceNormal = cross(edgeA, edgeB) * DecalFadeParameters.w;
    gl_Position = DecalLocalToClip * vec4(lfxDecalCorners[lfxDecalIndices[gl_VertexID]], 1.0);
}
