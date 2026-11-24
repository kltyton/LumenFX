#ifndef LFX_NIAGARA_MESH_SCENE
#define LFX_NIAGARA_MESH_SCENE
#if LFX_MESH_SCENE
layout(std140) uniform NiagaraMeshSceneInfo { ivec4 MeshSceneInfo; };
uniform samplerBuffer NiagaraMeshSceneTransforms;

LfxMeshTransform lfx_mesh_scene_transform(int instance, bool previous) {
    int base = MeshSceneInfo.x + instance * 9;
    vec4 scaleSign = texelFetch(NiagaraMeshSceneTransforms, base + 4);
    int matrix = base + (previous ? 5 : 0);
    LfxMeshTransform result;
    result.linear = mat3(texelFetch(NiagaraMeshSceneTransforms, matrix).xyz,
            texelFetch(NiagaraMeshSceneTransforms, matrix + 1).xyz,
            texelFetch(NiagaraMeshSceneTransforms, matrix + 2).xyz);
    result.position = texelFetch(NiagaraMeshSceneTransforms, matrix + 3).xyz;
    result.inverseScale = scaleSign.xyz;
    result.rotation = mat3(result.linear[0] * scaleSign.x, result.linear[1] * scaleSign.y, result.linear[2] * scaleSign.z);
    result.determinantSign = scaleSign.w;
    return result;
}
#endif
#endif
