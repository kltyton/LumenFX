using System.Text;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    // The first output word preserves the position before WPO, followed by four
    // UV pairs and one word for each authored vertex interpolator.
    private string CompileNaniteVertexCompute(string vertexSource)
    {
        int versionEnd = vertexSource.IndexOf('\n');
        if (versionEnd < 0) throw new ConversionException("Vertex material has no shader version");
        var shader = new StringBuilder("#version 450\n#define LFX_NANITE_VERTEX_MATERIAL 1\n#define LFX_PARTICLE_MOTION 1\n");
        shader.AppendLine($"#define LFX_CUSTOM_SCENE_TEXTURES {vertexCustomSceneTextures}");
        shader.AppendLine($"#define LFX_DEFERRED_SCENE_TEXTURES {vertexDeferredSceneTextures}");
        shader.AppendLine("#moj_import <lumenfx:niagara_mesh_transform.glsl>");
        shader.AppendLine("#moj_import <lumenfx:nanite/instance_data.glsl>");
        shader.AppendLine("#moj_import <lumenfx:particle_motion_view.glsl>");
        shader.AppendLine("layout(local_size_x = 64) in;");
        shader.AppendLine("layout(std430) readonly buffer NaniteVertexPrimitives { uvec4 lfxNanitePrimitives[]; };");
        shader.AppendLine("layout(std430) readonly buffer NaniteRasterCommands { uvec4 lfxNaniteRasterCommands[]; };");
        shader.AppendLine("layout(std430) buffer NaniteVertexGeometry { vec4 lfxNaniteVertices[]; };");
        shader.AppendLine("layout(std430) writeonly buffer NaniteVertexInterpolants { vec4 lfxNaniteVertexOutputs[]; };");
        shader.AppendLine("layout(std140) uniform NaniteVertexDispatch { uvec4 NaniteVertexLayout; };");
        shader.Append("layout(std140) uniform EffectView { vec4 CameraRight; vec4 CameraUp; vec4 Geometry;");
        if (macroUVUsed) shader.Append(" vec4 MacroUV;");
        if (depthProjectionUsed) shader.Append(" vec4 DepthTransform;");
        shader.AppendLine(" };");
        shader.AppendLine("layout(std140) uniform Projection { mat4 ProjMat; };");
        shader.AppendLine("layout(std140) uniform NaniteSceneViewProjection { mat4 lfxNaniteSceneViewProjection; };");
        shader.AppendLine("vec2 effectUV, effectUV1; vec4 effectMeshUV01, effectMeshUV23, effectMeshUV45, effectMeshUV67;");
        shader.AppendLine("vec4 effectColor, effectVertexColor, effectParticle, effectCustom, effectCustom1, effectCustom2, effectCustom3, effectMeshVelocity;");
        shader.AppendLine("float effectDynamicValid, effectUnmirrored, effectSubImageLerp; vec2 effectMeshSubUV0, effectMeshSubUV1; vec3 effectParticleCenter;");
        shader.Append(vertexSource[(versionEnd + 1)..]);
        shader.AppendLine("void main() {");
        shader.AppendLine("    uint vertexIndex = gl_GlobalInvocationID.x + gl_GlobalInvocationID.y * NaniteVertexLayout.z;");
        shader.AppendLine("    uvec4 lastBin = lfxNaniteRasterCommands[127u];");
        shader.AppendLine("    uint triangleCount = min(NaniteVertexLayout.x, (lastBin.x + lastBin.z) / 3u);");
        shader.AppendLine("    if (vertexIndex >= triangleCount * 3u) return;");
        shader.AppendLine("    uvec4 primitive = lfxNanitePrimitives[vertexIndex / 3u];");
        shader.AppendLine("    if (primitive.y != NaniteVertexLayout.y) return;");
        shader.AppendLine("    uint source = vertexIndex * 9u;");
        shader.AppendLine("    vec3 position = lfxNaniteVertices[source].xyz;");
        shader.AppendLine("    vec3 previous = lfxNaniteVertices[source + 1u].xyz;");
        shader.AppendLine("    vec4 normal = lfxNaniteVertices[source + 2u];");
        shader.AppendLine("    vec3 tangent = lfxNaniteVertices[source + 3u].xyz;");
        shader.AppendLine("    effectVertexColor = lfxNaniteVertices[source + 4u];");
        shader.AppendLine("    effectMeshUV01 = lfxNaniteVertices[source + 5u]; effectMeshUV23 = lfxNaniteVertices[source + 6u];");
        shader.AppendLine("    effectMeshUV45 = effectMeshUV67 = effectMeshUV23.zwzw; effectUV = effectMeshUV01.xy; effectUV1 = effectMeshUV01.zw;");
        shader.AppendLine("    effectColor = vec4(1.0); effectParticle = effectCustom = effectCustom1 = effectCustom2 = effectCustom3 = effectMeshVelocity = vec4(0.0);");
        shader.AppendLine("    effectDynamicValid = effectSubImageLerp = 0.0; effectMeshSubUV0 = effectMeshSubUV1 = effectUV; effectParticleCenter = vec3(0.0);");
        shader.AppendLine("    effectUnmirrored = normal.w * (primitive.w != 0u ? -1.0 : 1.0);");
        shader.AppendLine("    mat3 instanceLinear; vec3 instanceCenter, inverseScale;");
        shader.AppendLine("    lfx_nanite_instance_transform(primitive.x, instanceLinear, instanceCenter, inverseScale);");
        if (vertexNaniteInstanceDataUsed) shader.AppendLine("    lfxVertexNaniteInstanceId = primitive.x;");
        if (meshMaterialMatricesUsed) {
            shader.AppendLine("    lfxVertexInstanceLinear = instanceLinear; lfxVertexInstanceCenter = instanceCenter; lfxVertexInstanceInverseScale = inverseScale;");
            shader.AppendLine("    lfxVertexPreviousInstanceLinear = mat3(lfx_nanite_transform_word(primitive.x, 5).xyz, lfx_nanite_transform_word(primitive.x, 6).xyz, lfx_nanite_transform_word(primitive.x, 7).xyz);");
            shader.AppendLine("    lfxVertexPreviousInstanceCenter = lfx_nanite_transform_word(primitive.x, 8).xyz;");
        }
        if (vertexBasisUsed) shader.AppendLine("    lfxVertexMaterialNormal = normal.xyz; lfxVertexMaterialTangent = tangent; lfxVertexMaterialBinormal = cross(normal.xyz, tangent) * effectUnmirrored; lfxVertexMaterialBasisDeterminant = effectUnmirrored;");
        if ((preSkinnedInputs & 1) != 0) shader.AppendLine("    lfxVertexPreSkinnedPosition = lfxNaniteVertices[source + 7u].xyz;");
        if ((preSkinnedInputs & 2) != 0) shader.AppendLine("    lfxVertexPreSkinnedNormal = lfxNaniteVertices[source + 8u].xyz;");
        shader.AppendLine("    vec3 currentHost = position.xzy * 0.01; vec3 previousHost = previous.xzy * 0.01;");
        shader.AppendLine("    vec3 currentOffset = lfx_material_vertex_current(currentHost, effectParticleCenter);");
        shader.AppendLine("    vec3 previousOffset = lfx_material_vertex_previous(previousHost, effectParticleCenter);");
        shader.AppendLine("    vec3 currentResult = currentHost; vec3 previousResult = previousHost;");
        shader.AppendLine("    if (!lfx_nanite_fixed_raster()) { currentResult += currentOffset; previousResult += previousOffset; }");
        if (firstPersonRootInputs != 0) shader.AppendLine("    currentResult = lfx_material_first_person_current(currentResult); previousResult = lfx_material_first_person_previous(previousResult);");
        shader.AppendLine("    lfxNaniteVertices[source] = vec4(currentResult.xzy * 100.0, 1.0); lfxNaniteVertices[source + 1u] = vec4(previousResult.xzy * 100.0, 1.0);");
        int stride = 5 + vertexInterpolators.Count;
        shader.AppendLine($"    uint outputBase = vertexIndex * {stride}u;");
        shader.AppendLine("    lfxNaniteVertexOutputs[outputBase] = vec4(position, 1.0);");
        for (int pair = 0; pair < 4; pair++) {
            string coordinate(int index) => (customizedUVMask & (1 << index)) != 0 ? $"lfxCustomizedUV{index}" : $"lfx_mesh_coordinate({index})";
            shader.AppendLine($"    lfxNaniteVertexOutputs[outputBase + {pair + 1}u] = vec4({coordinate(pair * 2)}, {coordinate(pair * 2 + 1)});");
        }
        int output = 5;
        foreach (var interpolator in vertexInterpolators.Values) {
            string pad = interpolator.Width switch { 1 => ", 0.0, 0.0, 0.0", 2 => ", 0.0, 0.0", 3 => ", 0.0", _ => "" };
            shader.AppendLine($"    lfxNaniteVertexOutputs[outputBase + {output++}u] = vec4({interpolator.Name}{pad});");
        }
        shader.AppendLine("}");
        return shader.ToString();
    }
}
