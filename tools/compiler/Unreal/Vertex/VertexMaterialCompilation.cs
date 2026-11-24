using System.Text;
using LumenFx.Compiler.Unreal.Attributes;

namespace LumenFx.Compiler.Unreal;

public sealed record CompiledVertexMaterial(string Source, float MaximumDisplacement, int CustomizedUVMask,
    bool WorldPositionOffset, bool VertexBasis, bool SphericalParticleNormals, bool PreviousView, bool PreviousMatrices, bool PrimitiveMatrices,
    bool PreviousFirstPerson, int FirstPersonInputs, bool PreviousClock)
{
    public string? NaniteComputeSource { get; init; }
    public IReadOnlyList<int> NaniteInterpolatorWidths { get; init; } = Array.Empty<int>();
    public CompiledVertexMaterial(string source, float maximumDisplacement, int customizedUVMask,
            bool worldPositionOffset, bool vertexBasis, bool sphericalParticleNormals, bool previousView, bool previousMatrices,
            bool primitiveMatrices, bool previousFirstPerson)
        : this(source, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals,
                previousView, previousMatrices, primitiveMatrices, previousFirstPerson, 0, worldPositionOffset) { }
    public CompiledVertexMaterial(string source, float maximumDisplacement, int customizedUVMask,
            bool worldPositionOffset, bool vertexBasis, bool sphericalParticleNormals, bool previousView, bool previousMatrices,
            bool primitiveMatrices)
        : this(source, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals, previousView, previousMatrices, primitiveMatrices, false) { }
    public CompiledVertexMaterial(string source, float maximumDisplacement, int customizedUVMask,
            bool worldPositionOffset, bool vertexBasis, bool sphericalParticleNormals, bool previousView, bool previousMatrices)
        : this(source, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals, previousView, previousMatrices, false) { }
    public CompiledVertexMaterial(string source, float maximumDisplacement, int customizedUVMask,
            bool worldPositionOffset, bool vertexBasis, bool sphericalParticleNormals, bool previousView)
        : this(source, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals, previousView, false) { }
    public CompiledVertexMaterial(string source, float maximumDisplacement, int customizedUVMask,
            bool worldPositionOffset, bool vertexBasis, bool sphericalParticleNormals)
        : this(source, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals, false) { }
}

public sealed partial class MaterialCompiler
{
    private CompiledVertexMaterial? CompileVertexMaterial(Context pixelContext, SourceObject data, int coordinates,
        bool runtimeVirtualTextureWriter = false)
    {
        for (int highest = 7; highest >= 0; highest--) if ((coordinates & (1 << highest)) != 0) {
            coordinates = (1 << (highest + 1)) - 1;
            break;
        }
        var stageFunctions = new List<string>();
        var stages = new List<string>();
        bool worldPositionOffset = false;
        int configuredUVs = compiledMaterialProperties.Value<int?>("NumCustomizedUVs") ?? 0;
        bool attributes = compiledMaterialProperties.Value<bool?>("bUseMaterialAttributes") == true;
        if (configuredUVs is < 0 or > 8) throw new ConversionException("Material customized UV count must be in 0..8");
        float maximum = (float)SourceAsset.Number(compiledMaterialProperties["MaxWorldPositionOffsetDisplacement"]);
        if (!float.IsFinite(maximum))
            throw new ConversionException("Material maximum world position offset must be finite");
        string domain = (compiledMaterialProperties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last();
        firstPersonRootInputs = !runtimeVirtualTextureWriter && domain is ("MD_Surface" or "MD_Volume") ? 128 | 2048 : 0;
        SourceObject? firstPersonOutput = firstPersonRootInputs == 0 ? null : FindFirstPersonOutput(pixelContext, data);
        try {
            compilingVertex = true;
            foreach (bool previous in new[] { false, true }) {
                ResetExpressionGraph();
                compilingPreviousFrame = previous;
                Context context = pixelContext with { Id = nextContext++ };
                ResolvedAttribute input = runtimeVirtualTextureWriter ? new(Constant(0), false)
                        : MaterialInput(context, data, MaterialAttribute.WorldPositionOffset, () => Constant(0));
                worldPositionOffset |= input.Connected && !input.Value.ZeroConstant;
                Expr offset = Cast(input.Value, 3);
                var uvValues = new List<(int Index, Expr Value)>();
                if (!previous) for (int coordinate = 0; coordinate < 8; coordinate++) {
                    if ((coordinates & (1 << coordinate)) == 0 || !attributes && coordinate >= configuredUVs) continue;
                    int index = coordinate;
                    ResolvedAttribute uv = MaterialInput(context, data, MaterialAttribute.CustomizedUV0 + coordinate,
                            () => new Expr(RawCoordinate(index), 2));
                    if (!uv.Connected) continue;
                    customizedUVMask |= 1 << coordinate;
                    uvValues.Add((coordinate, Cast(uv.Value, 2)));
                }
                stageFunctions.AddRange(functions);
                var stage = new StringBuilder();
                if (previous) stage.AppendLine("#if LFX_PARTICLE_MOTION");
                stage.AppendLine($"vec3 lfx_material_vertex_{(previous ? "previous" : "current")}(vec3 position, vec3 particleCenter) {{");
                stage.AppendLine("lfxVertexMaterialPosition = position; lfxVertexMaterialParticleCenter = particleCenter;");
                if (!previous && pixelVertexNormalUsed) stage.AppendLine("effectMaterialVertexNormal = lfxVertexMaterialNormal;");
                if (!previous && pixelVertexTangentUsed) stage.AppendLine("effectMaterialVertexTangent = lfxVertexMaterialTangent;");
                if (!previous && pixelBasisMatrixUsed) stage.AppendLine("effectMaterialBasisDeterminant = lfxVertexMaterialBasisDeterminant;");
                if (!previous && pixelMeshMaterialMatricesUsed)
                    stage.AppendLine("effectMaterialInstanceLinear = lfxVertexInstanceLinear; effectMaterialInstanceCenter = lfxVertexInstanceCenter; effectMaterialInstanceInverseScale = lfxVertexInstanceInverseScale;");
                foreach (string expression in expressions) stage.AppendLine(expression);
                foreach (var uv in uvValues) stage.AppendLine($"lfxCustomizedUV{uv.Index} = {uv.Value.Code};");
                if (!previous) foreach (var interpolator in vertexInterpolators.Values)
                    stage.AppendLine($"{interpolator.Name} = {interpolator.Name}_vertex();");
                stage.AppendLine($"vec3 offset = {offset.Code};");
                if (naniteCoordinates) {
                    stage.AppendLine("#if LFX_NANITE_VERTEX_MATERIAL");
                    stage.AppendLine("float maximumWpoExtent = uintBitsToFloat(NaniteVertexLayout.w);");
                    stage.AppendLine("if (maximumWpoExtent > 0.0) offset = clamp(offset, vec3(-maximumWpoExtent), vec3(maximumWpoExtent));");
                    stage.AppendLine("#else");
                }
                if (maximum > 0) stage.AppendLine($"offset = clamp(offset, vec3(-{Number(maximum)}), vec3({Number(maximum)}));");
                if (naniteCoordinates) stage.AppendLine("#endif");
                stage.AppendLine("return offset.xzy * 0.01;");
                stage.AppendLine("}");
                AppendFirstPersonRootStage(stage, stageFunctions, pixelContext, firstPersonOutput, previous);
                if (previous) stage.AppendLine("#endif");
                stages.Add(stage.ToString());
            }
            if (!worldPositionOffset && customizedUVMask == 0 && !vertexBasisUsed && !UsesSphericalParticleNormals()
                    && vertexInterpolators.Count == 0 && preSkinnedInputs == 0 && !meshMaterialMatricesUsed && !primitiveMaterialMatricesUsed
                    && firstPersonRootInputs == 0) return null;
            var source = new StringBuilder(MaterialShaderHeader());
            if (firstPersonRootInputs != 0) source.AppendLine("#define LFX_MATERIAL_FIRST_PERSON_ROOT 1");
            AppendPreSkinnedInputs(source);
            AppendNaniteInstanceMaterialData(source, true);
            AppendMeshMaterialMatrices(source, true);
            AppendPrimitiveMaterialMatrices(source);
            source.AppendLine("layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; vec4 EffectTimeData; };");
            AppendMaterialCollections(source, true);
            source.AppendLine("vec3 lfxVertexMaterialPosition; vec3 lfxVertexMaterialParticleCenter;");
            if (vertexBasisUsed) source.AppendLine("vec3 lfxVertexMaterialNormal; vec3 lfxVertexMaterialTangent; vec3 lfxVertexMaterialBinormal; float lfxVertexMaterialBasisDeterminant;");
            if (pixelVertexNormalUsed) AppendVertexOutput(source, "vec3", "effectMaterialVertexNormal");
            if (pixelVertexTangentUsed) AppendVertexOutput(source, "vec3", "effectMaterialVertexTangent");
            if (pixelBasisMatrixUsed) AppendVertexOutput(source, "float", "effectMaterialBasisDeterminant");
            foreach (var interpolator in vertexInterpolators.Values)
                AppendVertexOutput(source, Type(interpolator.Width), interpolator.Name);
            for (int coordinate = 0; coordinate < 8; coordinate++)
                if ((customizedUVMask & (1 << coordinate)) != 0) AppendVertexOutput(source, "vec2", $"lfxCustomizedUV{coordinate}");
            if (meshCoordinates)
                source.AppendLine("vec2 lfx_mesh_coordinate(int channel) { vec4 uv = channel < 2 ? effectMeshUV01 : channel < 4 ? effectMeshUV23 : channel < 6 ? effectMeshUV45 : effectMeshUV67; return (channel & 1) == 0 ? uv.xy : uv.zw; }");
            AppendMaterialView(source);
            AppendMaterialPassSwitchHelpers(source, vertex: true);
            AppendVertexSceneTextures(source);
            AppendVirtualTextureReadDeclarations(source, vertex: true);
            if (virtualTexturePageViewUsed) AppendVirtualTexturePageView(source);
            if (cameraVectorUsed)
                source.AppendLine("vec3 lfx_camera_vector() { vec3 v = ProjMat[3][3] >= 1.0 ? normalize(cross(CameraRight.xyz, CameraUp.xyz)) : normalize(-lfxVertexMaterialPosition); return v.xzy; }");
            AppendTextureSamplers(source);
            MaterialShaderMath.Append(source);
            AppendMaterialNoise(source, vertex: true);
            AppendLargeWorldMath(source);
            AppendPeriodicWorldMath(source);
            foreach (string sampler in UsedTextureSamplers())
                source.AppendLine($"vec4 lfx_sample_{sampler}({Type(TextureCoordinateWidth(TextureShape(sampler)))} uv) {{ return textureLod({sampler}, uv, 0.0); }}");
            foreach (string function in stageFunctions) source.AppendLine(function);
            foreach (var interpolator in vertexInterpolators.Values) source.AppendLine(interpolator.Functions);
            foreach (string stage in stages) source.AppendLine(stage);
            string vertexSource = source.ToString();
            return new CompiledVertexMaterial(vertexSource, maximum, customizedUVMask, worldPositionOffset, vertexBasisUsed,
                    UsesSphericalParticleNormals(), previousViewUsed, previousMatricesUsed, primitiveMaterialMatricesUsed, previousFirstPersonUsed,
                    firstPersonRootInputs, previousClockUsed) {
                NaniteComputeSource = naniteCoordinates ? CompileNaniteVertexCompute(vertexSource) : null,
                NaniteInterpolatorWidths = vertexInterpolators.Values.Select(value => value.Width).ToArray()
            };
        } finally {
            compilingVertex = compilingPreviousFrame = false;
            ResetExpressionGraph();
        }
    }

    private static void AppendVertexOutput(StringBuilder shader, string type, string name)
    {
        shader.AppendLine("#if LFX_NANITE_VERTEX_MATERIAL");
        shader.AppendLine($"{type} {name};");
        shader.AppendLine("#else");
        shader.AppendLine($"out {type} {name};");
        shader.AppendLine("#endif");
    }

    private void ResetExpressionGraph()
    {
        cache.Clear(); visiting.Clear(); attributeCache.Clear(); attributeVisiting.Clear();
        functionScopes.Clear(); functions.Clear(); expressions.Clear(); expressionValues.Clear();
        ResetCustomExpressionState();
    }

    private string RawCoordinate(int index) => lightFunctionCoordinates ? "effectUV" : meshCoordinates ? $"lfx_mesh_coordinate({index})" : index switch {
        0 => "effectUV", 1 => spriteCoordinates ? "effectNextUV" : "effectUV1",
        2 when spriteCoordinates => "effectUnflippedUV", 3 when spriteCoordinates => "effectNextUnflippedUV",
        _ => "vec2(0.0)"
    };

    private Expr VertexBasis(bool tangent)
    {
        if (lightFunctionCoordinates || volumeCoordinates)
            return Vector(tangent ? new[] { 1d, 0d, 0d } : new[] { 0d, 0d, 1d });
        if (decalCoordinates) return new Expr(tangent ? "lfx_decal_tangent_normal(vec3(1.0, 0.0, 0.0)).xzy"
                : "lfx_decal_tangent_normal(vec3(0.0, 0.0, 1.0)).xzy", 3);
        vertexBasisUsed = true;
        if (compilingVertex) {
            Expr basis = new(tangent ? "lfxVertexMaterialTangent" : "lfxVertexMaterialNormal", 3);
            return meshCoordinates ? basis : PrimitiveMaterialTransform(MaterialSpace.Local, basis, true, false, false);
        }
        if (tangent) pixelVertexTangentUsed = true; else pixelVertexNormalUsed = true;
        if (UsesSphericalParticleNormals()) {
            viewUniformUsed = true;
            return new Expr(tangent ? "lfxMaterialSphericalBasis[0]" : "lfxMaterialSphericalBasis[2]", 3);
        }
        return new Expr(tangent ? "effectMaterialVertexTangent" : "effectMaterialVertexNormal", 3);
    }

    private bool UsesSphericalParticleNormals() => spriteCoordinates
            && compiledMaterialProperties.Value<bool?>("bGenerateSphericalParticleNormals") == true;

    private bool UsesSphericalPixelBasis() => UsesSphericalParticleNormals() && (pixelVertexNormalUsed || pixelVertexTangentUsed);

    private void AppendSphericalPixelBasis(StringBuilder shader)
    {
        if (!UsesSphericalPixelBasis()) return;
        shader.AppendLine("in vec3 effectMaterialSphereCenter; flat in float effectMaterialSphereRadius;");
        shader.AppendLine("mat3 lfxMaterialSphericalBasis;");
        shader.AppendLine("mat3 lfx_spherical_particle_basis() { vec3 position = effectPosition.xzy * 100.0; vec3 delta = position - effectMaterialSphereCenter; float distanceToParticle = length(delta); float determinant = effectMaterialSphereRadius * effectMaterialSphereRadius - distanceToParticle * distanceToParticle; vec3 normal = vec3(0.0, 0.0, 1.0); if (determinant > 0.0) { vec3 forward = -cross(CameraRight.xyz, CameraUp.xyz); normal = normalize(position - forward.xzy * sqrt(determinant) - effectMaterialSphereCenter); } vec3 tangent = normalize(cross(CameraUp.xzy, normal)); return mat3(tangent, cross(normal, tangent), normal); }");
    }

    private void AppendPixelCoordinates(StringBuilder shader)
    {
        if (pixelCoordinateMask == 0) return;
        shader.AppendLine("vec2 lfx_material_coordinate(int coordinate) { switch (coordinate) {");
        for (int index = 0; index < 8; index++) {
            if ((pixelCoordinateMask & (1 << index)) == 0) continue;
            int channel = meshCoordinates && subUVUsed ? 0 : index;
            string value = (customizedUVMask & (1 << channel)) != 0 ? $"lfxCustomizedUV{channel}" : RawCoordinate(channel);
            shader.AppendLine($"case {index}: return {value};");
        }
        shader.AppendLine("default: return vec2(0.0); } }");
        if (naniteCoordinates) {
            shader.AppendLine("vec2 lfx_material_coordinate_dx(int coordinate) {");
            shader.AppendLine("#if LFX_NANITE_MATERIAL");
            if (subUVUsed) shader.AppendLine("coordinate = 0;");
            shader.AppendLine("vec4 value = lfxNaniteUvDx[coordinate / 2]; return (coordinate & 1) == 0 ? value.xy : value.zw;");
            shader.AppendLine("#else\nreturn dFdx(lfx_material_coordinate(coordinate));\n#endif\n}");
            shader.AppendLine("vec2 lfx_material_coordinate_dy(int coordinate) {");
            shader.AppendLine("#if LFX_NANITE_MATERIAL");
            if (subUVUsed) shader.AppendLine("coordinate = 0;");
            shader.AppendLine("vec4 value = lfxNaniteUvDy[coordinate / 2]; return (coordinate & 1) == 0 ? value.xy : value.zw;");
            shader.AppendLine("#else\nreturn dFdy(lfx_material_coordinate(coordinate));\n#endif\n}");
            shader.AppendLine("float lfx_material_unmirrored_dx() {\n#if LFX_NANITE_MATERIAL\nreturn lfxNaniteGeometry.tangentSignDx;\n#else\nreturn dFdx(effectUnmirrored);\n#endif\n}");
            shader.AppendLine("float lfx_material_unmirrored_dy() {\n#if LFX_NANITE_MATERIAL\nreturn lfxNaniteGeometry.tangentSignDy;\n#else\nreturn dFdy(effectUnmirrored);\n#endif\n}");
        }
    }
}
