using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal.Attributes;

namespace LumenFx.Compiler.Unreal.Expressions;

internal static partial class CustomHlslTranslator
{
    internal enum OutputKind { Float1, Float2, Float3, Float4, UInt1, UInt2, UInt3, UInt4, Int1, Int2, Int3, Int4, Bool1, Bool2, Bool3, Bool4, MaterialAttributes }

    internal sealed record PinParameter(
        string Name,
        OutputKind Kind,
        bool IsSampler = false,
        string? SamplerType = null,
        bool IsVirtualTexture = false,
        bool IsSceneTexture = false,
        int? SceneTextureId = null,
        bool HasLwc = false,
        string? LwcType = null);

    internal sealed record OutputPinSpec(string Name, OutputKind Kind, bool IsAdditionalOutput);

    internal interface IEnvironment
    {
        bool IsVertexStage { get; }
        int ShaderVersion { get; }
        string ResolveSceneTextureFetch(string inputName, string uvGlsl);
        string ResolveVirtualTextureSample(string inputName, string operation, IReadOnlyList<string> args);
        (string GlslType, string GlslInitExpr) ResolveParameterField(string fieldAccess);
        (string GlslType, string GlslExpr) ResolveViewField(string fieldName);
        string ResolveGetWorldPosition();
        void EnsureLargeWorldMath();
        void MarkGatherUsed();
        void MarkQueryLevelsUsed();
        void MarkEyeAdaptationUsed();
        void MarkPostVolumeFlagsUsed();
        void MarkNaniteReplaceUsed();
        void MarkShadowReplaceUsed();
        void MarkDerivativesUsed();
    }

    internal sealed class ResultData
    {
        internal List<string> SupportDeclarations { get; } = new();
        internal string FunctionDefinition { get; init; } = "";
        internal string FunctionName { get; init; } = "";
        internal bool UsesMaterialAttributes { get; init; }
        internal bool EmitsDiscard { get; init; }
        internal HashSet<string> BareSceneTextureInputs { get; } = new(StringComparer.Ordinal);
        internal HashSet<string> UsedLwcInputs { get; } = new(StringComparer.Ordinal);
    }

    private enum TokKind { Ident, Number, String, Symbol, Newline }

    private sealed class Tok
    {
        internal TokKind Kind;
        internal string Text;
        internal Tok(TokKind kind, string text) { Kind = kind; Text = text; }
    }

    private sealed class DeclFunction
    {
        internal string Name = "";
        internal string ReturnType = "";
        internal List<DeclParam> Params = new();
        internal List<Tok> BodyTokens = new();
        internal HashSet<int> SamplerStateParamIndices = new();
    }

    private sealed class DeclParam
    {
        internal string Qualifiers = "";
        internal string Type = "";
        internal string Name = "";
        internal string ArraySuffix = "";
        internal bool IsSamplerState;
    }

    private sealed class DeclStruct
    {
        internal string Name = "";
        internal List<(string Type, string Name, string ArraySuffix)> Fields = new();
        internal List<Tok> RawBodyTokens = new();
    }

    private sealed class DeclGlobal
    {
        internal string Type = "";
        internal string Name = "";
        internal string ArraySuffix = "";
        internal bool IsConst;
        internal List<Tok> InitTokens = new();
        internal List<Tok> AllTokens = new();
    }

    private static readonly Dictionary<string, string> ScalarVectorTypeMap = new(StringComparer.Ordinal)
    {
        ["float"] = "float", ["float1"] = "float", ["half"] = "float", ["half1"] = "float",
        ["fixed"] = "float", ["fixed1"] = "float", ["min16float"] = "float", ["min10float"] = "float",
        ["MaterialFloat"] = "float", ["MaterialFloat1"] = "float",
        ["float2"] = "vec2", ["half2"] = "vec2", ["fixed2"] = "vec2", ["min16float2"] = "vec2", ["MaterialFloat2"] = "vec2",
        ["float3"] = "vec3", ["half3"] = "vec3", ["fixed3"] = "vec3", ["min16float3"] = "vec3", ["MaterialFloat3"] = "vec3",
        ["float4"] = "vec4", ["half4"] = "vec4", ["fixed4"] = "vec4", ["min16float4"] = "vec4", ["MaterialFloat4"] = "vec4",
        ["int1"] = "int", ["int2"] = "ivec2", ["int3"] = "ivec3", ["int4"] = "ivec4",
        ["min16int"] = "int", ["min16int2"] = "ivec2", ["min16int3"] = "ivec3", ["min16int4"] = "ivec4",
        ["uint1"] = "uint", ["uint2"] = "uvec2", ["uint3"] = "uvec3", ["uint4"] = "uvec4", ["dword"] = "uint",
        ["min16uint"] = "uint", ["min16uint2"] = "uvec2", ["min16uint3"] = "uvec3", ["min16uint4"] = "uvec4",
        ["bool1"] = "bool", ["bool2"] = "bvec2", ["bool3"] = "bvec3", ["bool4"] = "bvec4",
        ["float1x1"] = "float", ["half1x1"] = "float", ["min16float1x1"] = "float", ["MaterialFloat1x1"] = "float",
        ["float1x2"] = "vec2", ["float2x1"] = "vec2", ["half1x2"] = "vec2", ["half2x1"] = "vec2", ["min16float1x2"] = "vec2", ["min16float2x1"] = "vec2", ["MaterialFloat1x2"] = "vec2", ["MaterialFloat2x1"] = "vec2",
        ["float1x3"] = "vec3", ["float3x1"] = "vec3", ["half1x3"] = "vec3", ["half3x1"] = "vec3", ["min16float1x3"] = "vec3", ["min16float3x1"] = "vec3", ["MaterialFloat1x3"] = "vec3", ["MaterialFloat3x1"] = "vec3",
        ["float1x4"] = "vec4", ["float4x1"] = "vec4", ["half1x4"] = "vec4", ["half4x1"] = "vec4", ["min16float1x4"] = "vec4", ["min16float4x1"] = "vec4", ["MaterialFloat1x4"] = "vec4", ["MaterialFloat4x1"] = "vec4",
        ["int1x1"] = "int", ["int1x2"] = "ivec2", ["int2x1"] = "ivec2", ["int1x3"] = "ivec3", ["int3x1"] = "ivec3", ["int1x4"] = "ivec4", ["int4x1"] = "ivec4",
        ["uint1x1"] = "uint", ["uint1x2"] = "uvec2", ["uint2x1"] = "uvec2", ["uint1x3"] = "uvec3", ["uint3x1"] = "uvec3", ["uint1x4"] = "uvec4", ["uint4x1"] = "uvec4",
        ["bool1x1"] = "bool", ["bool1x2"] = "bvec2", ["bool2x1"] = "bvec2", ["bool1x3"] = "bvec3", ["bool3x1"] = "bvec3", ["bool1x4"] = "bvec4", ["bool4x1"] = "bvec4",
        ["float2x2"] = "mat2", ["half2x2"] = "mat2", ["MaterialFloat2x2"] = "mat2",
        ["float3x3"] = "mat3", ["half3x3"] = "mat3", ["MaterialFloat3x3"] = "mat3",
        ["float4x4"] = "mat4", ["half4x4"] = "mat4", ["MaterialFloat4x4"] = "mat4",
        ["float2x3"] = "mat2x3", ["half2x3"] = "mat2x3", ["float3x2"] = "mat3x2", ["half3x2"] = "mat3x2",
        ["float2x4"] = "mat2x4", ["half2x4"] = "mat2x4", ["float4x2"] = "mat4x2", ["half4x2"] = "mat4x2",
        ["float3x4"] = "mat3x4", ["half3x4"] = "mat3x4", ["MaterialFloat3x4"] = "mat3x4",
        ["float4x3"] = "mat4x3", ["half4x3"] = "mat4x3", ["MaterialFloat4x3"] = "mat4x3",
        ["FDFScalar"] = "LfxLWC1", ["FLWCScalar"] = "LfxLWC1", ["FWSScalar"] = "LfxLWC1",
        ["FDFVector2"] = "LfxLWC2", ["FLWCVector2"] = "LfxLWC2", ["FWSVector2"] = "LfxLWC2",
        ["FDFVector3"] = "LfxLWC3", ["FLWCVector3"] = "LfxLWC3", ["FWSVector3"] = "LfxLWC3",
        ["FDFVector4"] = "LfxLWC4", ["FLWCVector4"] = "LfxLWC4", ["FWSVector4"] = "LfxLWC4",
        ["Texture2D"] = "sampler2D", ["Texture2DArray"] = "sampler2DArray",
        ["TextureCube"] = "samplerCube", ["TextureCubeArray"] = "samplerCubeArray", ["Texture3D"] = "sampler3D"
    };

    private static readonly HashSet<string> GlslBuiltinTypes = new(StringComparer.Ordinal)
    {
        "void", "bool", "int", "uint", "float", "double",
        "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4",
        "mat2", "mat3", "mat4", "mat2x3", "mat3x2", "mat2x4", "mat4x2", "mat3x4", "mat4x3",
        "sampler2D", "sampler2DArray", "samplerCube", "samplerCubeArray", "sampler3D",
        "LfxLWC1", "LfxLWC2", "LfxLWC3", "LfxLWC4", "FMaterialAttributes"
    };

    private static readonly HashSet<string> IntrinsicMapDirect = new(StringComparer.Ordinal)
    {
        // Only simple identifier renames handled carefully in expression pass
    };

    private static readonly Dictionary<string, string> TextureSampleMacros = new(StringComparer.Ordinal)
    {
        ["Texture2DSample"] = "Sample",
        ["Texture2DSampleLevel"] = "SampleLevel",
        ["Texture2DSampleBias"] = "SampleBias",
        ["Texture2DSampleGrad"] = "SampleGrad",
        ["Texture2DArraySample"] = "Sample",
        ["Texture2DArraySampleLevel"] = "SampleLevel",
        ["Texture2DArraySampleBias"] = "SampleBias",
        ["Texture2DArraySampleGrad"] = "SampleGrad",
        ["TextureCubeSample"] = "Sample",
        ["TextureCubeSampleLevel"] = "SampleLevel",
        ["TextureCubeSampleBias"] = "SampleBias",
        ["TextureCubeSampleGrad"] = "SampleGrad",
        ["TextureCubeArraySample"] = "Sample",
        ["TextureCubeArraySampleLevel"] = "SampleLevel",
        ["Texture3DSample"] = "Sample",
        ["Texture3DSampleLevel"] = "SampleLevel",
        ["Texture3DSampleBias"] = "SampleBias",
        ["Texture3DSampleGrad"] = "SampleGrad",
        ["MaterialStoreTexCoordScale"] = "IgnoreScale",
        ["MaterialStoreTexSample"] = "IgnoreScale"
    };

    internal static string GlslTypeName(OutputKind kind) => kind switch
    {
        OutputKind.Float1 => "float",
        OutputKind.Float2 => "vec2",
        OutputKind.Float3 => "vec3",
        OutputKind.Float4 => "vec4",
        OutputKind.UInt1 => "uint",
        OutputKind.UInt2 => "uvec2",
        OutputKind.UInt3 => "uvec3",
        OutputKind.UInt4 => "uvec4",
        OutputKind.Int1 => "int",
        OutputKind.Int2 => "ivec2",
        OutputKind.Int3 => "ivec3",
        OutputKind.Int4 => "ivec4",
        OutputKind.Bool1 => "bool",
        OutputKind.Bool2 => "bvec2",
        OutputKind.Bool3 => "bvec3",
        OutputKind.Bool4 => "bvec4",
        OutputKind.MaterialAttributes => "FMaterialAttributes",
        _ => throw new ConversionException($"Unsupported custom material output kind {kind}")
    };

    internal static string ZeroInitializer(OutputKind kind) => kind switch
    {
        OutputKind.Float1 => "0.0",
        OutputKind.Float2 => "vec2(0.0)",
        OutputKind.Float3 => "vec3(0.0)",
        OutputKind.Float4 => "vec4(0.0)",
        OutputKind.UInt1 => "0u",
        OutputKind.UInt2 => "uvec2(0u)",
        OutputKind.UInt3 => "uvec3(0u)",
        OutputKind.UInt4 => "uvec4(0u)",
        OutputKind.Int1 => "0",
        OutputKind.Int2 => "ivec2(0)",
        OutputKind.Int3 => "ivec3(0)",
        OutputKind.Int4 => "ivec4(0)",
        OutputKind.Bool1 => "false",
        OutputKind.Bool2 => "bvec2(false)",
        OutputKind.Bool3 => "bvec3(false)",
        OutputKind.Bool4 => "bvec4(false)",
        OutputKind.MaterialAttributes => "lfx_zero_FMaterialAttributes()",
        _ => throw new ConversionException($"Unsupported custom material output kind {kind}")
    };

    internal static string MaterialAttributesStructDefinition()
    {
        StringBuilder sb = new();
        sb.AppendLine("struct FMaterialAttributes {");
        sb.AppendLine("    vec3 BaseColor;");
        sb.AppendLine("    float Metallic;");
        sb.AppendLine("    float Specular;");
        sb.AppendLine("    float Roughness;");
        sb.AppendLine("    float Anisotropy;");
        sb.AppendLine("    vec3 EmissiveColor;");
        sb.AppendLine("    float Opacity;");
        sb.AppendLine("    float OpacityMask;");
        sb.AppendLine("    vec3 Normal;");
        sb.AppendLine("    vec3 Tangent;");
        sb.AppendLine("    vec3 WorldPositionOffset;");
        sb.AppendLine("    vec3 SubsurfaceColor;");
        sb.AppendLine("    float ClearCoat;");
        sb.AppendLine("    float ClearCoatRoughness;");
        sb.AppendLine("    float AmbientOcclusion;");
        sb.AppendLine("    vec2 Refraction;");
        sb.AppendLine("    vec2 CustomizedUV0;");
        sb.AppendLine("    vec2 CustomizedUV1;");
        sb.AppendLine("    vec2 CustomizedUV2;");
        sb.AppendLine("    vec2 CustomizedUV3;");
        sb.AppendLine("    vec2 CustomizedUV4;");
        sb.AppendLine("    vec2 CustomizedUV5;");
        sb.AppendLine("    vec2 CustomizedUV6;");
        sb.AppendLine("    vec2 CustomizedUV7;");
        sb.AppendLine("    float PixelDepthOffset;");
        sb.AppendLine("    int ShadingModel;");
        sb.AppendLine("    float Displacement;");
        sb.AppendLine("    float SurfaceThickness;");
        sb.AppendLine("    vec3 ClearCoatBottomNormal;");
        sb.AppendLine("    vec3 CustomEyeTangent;");
        sb.AppendLine("    vec3 BentNormal;");
        sb.AppendLine("    float CustomData0;");
        sb.AppendLine("    float CustomData1;");
        sb.AppendLine("    vec3 TransmittanceColor;");
        sb.AppendLine("    float ThinTranslucentSurfaceCoverage;");
        sb.AppendLine("};");
        sb.AppendLine("FMaterialAttributes lfx_zero_FMaterialAttributes() {");
        sb.AppendLine("    return FMaterialAttributes(");
        sb.AppendLine("        vec3(0.0), 0.0, 0.5, 0.5, 0.0, vec3(0.0), 1.0, 1.0,");
        sb.AppendLine("        vec3(0.0, 0.0, 1.0), vec3(1.0, 0.0, 0.0), vec3(0.0), vec3(1.0),");
        sb.AppendLine("        1.0, 0.1, 1.0, vec2(1.0, 0.0),");
        sb.AppendLine("        vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0),");
        sb.AppendLine("        0.0, 1, -1.0, 0.01, vec3(0.0, 0.0, 1.0), vec3(1.0, 0.0, 0.0), vec3(0.0, 0.0, 1.0),");
        sb.AppendLine("        0.0, 0.1, vec3(1.0), 1.0);");
        sb.AppendLine("}");
        return sb.ToString();
    }

    internal static string MaterialAttributeField(MaterialAttribute attribute) => attribute switch
    {
        MaterialAttribute.BaseColor => "BaseColor",
        MaterialAttribute.Metallic => "Metallic",
        MaterialAttribute.Specular => "Specular",
        MaterialAttribute.Roughness => "Roughness",
        MaterialAttribute.Anisotropy => "Anisotropy",
        MaterialAttribute.EmissiveColor => "EmissiveColor",
        MaterialAttribute.Opacity => "Opacity",
        MaterialAttribute.OpacityMask => "OpacityMask",
        MaterialAttribute.Normal => "Normal",
        MaterialAttribute.Tangent => "Tangent",
        MaterialAttribute.WorldPositionOffset => "WorldPositionOffset",
        MaterialAttribute.SubsurfaceColor => "SubsurfaceColor",
        MaterialAttribute.AmbientOcclusion => "AmbientOcclusion",
        MaterialAttribute.Refraction => "Refraction",
        MaterialAttribute.CustomizedUV0 => "CustomizedUV0",
        MaterialAttribute.CustomizedUV1 => "CustomizedUV1",
        MaterialAttribute.CustomizedUV2 => "CustomizedUV2",
        MaterialAttribute.CustomizedUV3 => "CustomizedUV3",
        MaterialAttribute.CustomizedUV4 => "CustomizedUV4",
        MaterialAttribute.CustomizedUV5 => "CustomizedUV5",
        MaterialAttribute.CustomizedUV6 => "CustomizedUV6",
        MaterialAttribute.CustomizedUV7 => "CustomizedUV7",
        MaterialAttribute.PixelDepthOffset => "PixelDepthOffset",
        MaterialAttribute.ShadingModel => "ShadingModel",
        MaterialAttribute.Displacement => "Displacement",
        MaterialAttribute.SurfaceThickness => "SurfaceThickness",
        MaterialAttribute.ClearCoatBottomNormal => "ClearCoatBottomNormal",
        MaterialAttribute.CustomEyeTangent => "CustomEyeTangent",
        MaterialAttribute.BentNormal => "BentNormal",
        MaterialAttribute.CustomData0 => "CustomData0",
        MaterialAttribute.CustomData1 => "CustomData1",
        MaterialAttribute.TransmittanceColor => "TransmittanceColor",
        MaterialAttribute.ThinTranslucentSurfaceCoverage => "ThinTranslucentSurfaceCoverage",
        _ => throw new ConversionException($"Unsupported MaterialAttributes field {attribute} for Custom node output")
    };

    internal static ResultData Translate(
        int nodeId,
        string preprocessedPreamble,
        string preprocessedCode,
        IReadOnlyList<PinParameter> inputs,
        OutputPinSpec mainOutput,
        IReadOnlyList<OutputPinSpec> additionalOutputs,
        IEnvironment environment)
    {
        TranslatorInstance instance = new(nodeId, inputs, mainOutput, additionalOutputs, environment);
        return instance.Execute(preprocessedPreamble, preprocessedCode);
    }

    private sealed partial class TranslatorInstance
    {
        private readonly int nodeId;
        private readonly string prefix;
        private readonly IReadOnlyList<PinParameter> inputs;
        private readonly Dictionary<string, PinParameter> inputByName;
        private readonly OutputPinSpec mainOutput;
        private readonly IReadOnlyList<OutputPinSpec> additionalOutputs;
        private readonly IEnvironment env;

        private readonly List<DeclStruct> allStructs = new();
        private readonly List<DeclGlobal> allGlobals = new();
        private readonly List<DeclFunction> allFunctions = new();
        private readonly HashSet<string> usedSupportHelpers = new(StringComparer.Ordinal);
        private readonly Dictionary<(string SamplerType, string Offset), string> loadOffsetHelpers = new();
        private readonly HashSet<string> zeroHelperStructs = new(StringComparer.Ordinal);
        private readonly HashSet<string> usedParameterFields = new(StringComparer.Ordinal);
        private readonly HashSet<int> usedTexCoordIndices = new();
        private readonly Dictionary<string, (string GlslType, string GlslExpr)> resolvedViewFields = new(StringComparer.Ordinal);
        private readonly HashSet<string> bareSceneTextureInputs = new(StringComparer.Ordinal);
        private readonly HashSet<string> usedLwcInputs = new(StringComparer.Ordinal);
        private bool usesParametersObject;
        private bool usesWorldPositionHelper;
        private bool usesPiConstant;
        private bool emitsDiscard;
        private bool usesMaterialAttributes;

        internal TranslatorInstance(
            int nodeId,
            IReadOnlyList<PinParameter> inputs,
            OutputPinSpec mainOutput,
            IReadOnlyList<OutputPinSpec> additionalOutputs,
            IEnvironment env)
        {
            this.nodeId = nodeId;
            prefix = $"lfx_c{nodeId}_";
            this.inputs = inputs;
            inputByName = new Dictionary<string, PinParameter>(StringComparer.Ordinal);
            foreach (PinParameter p in inputs) inputByName[p.Name] = p;
            this.mainOutput = mainOutput;
            this.additionalOutputs = additionalOutputs;
            this.env = env;
            if (mainOutput.Kind == OutputKind.MaterialAttributes || additionalOutputs.Any(o => o.Kind == OutputKind.MaterialAttributes))
                usesMaterialAttributes = true;
        }

        internal ResultData Execute(string preambleSource, string codeSource)
        {
            List<Tok> preambleTokens = NormalizeMatrixTypeTokens(StripHlslDecorators(Tokenize(preambleSource)));
            ParseTopLevelDeclarations(preambleTokens);

            List<Tok> rawCodeTokens = NormalizeMatrixTypeTokens(StripHlslDecorators(Tokenize(codeSource)));
            List<Tok> hoistedCodeTokens = ExtractStructsFromFunctionBody(rawCodeTokens);

            // Track scene textures used barely vs only .ID / .Fetch() before pruning helpers/signature
            PreScanSpecialInputs(hoistedCodeTokens);
            foreach (DeclFunction fn in allFunctions) PreScanSpecialInputs(fn.BodyTokens);

            // Compute reachable declarations starting from Code tokens
            (HashSet<string> reachFuncs, HashSet<string> reachStructs, HashSet<string> reachGlobals) =
                ComputeReachable(hoistedCodeTokens);

            // Check if FMaterialAttributes appears anywhere reachable
            if (UsesIdentifier(hoistedCodeTokens, "FMaterialAttributes")
                    || allFunctions.Where(f => reachFuncs.Contains(f.Name)).Any(f =>
                        f.ReturnType == "FMaterialAttributes"
                        || f.Params.Any(p => p.Type == "FMaterialAttributes")
                        || UsesIdentifier(f.BodyTokens, "FMaterialAttributes"))
                    || allStructs.Where(s => reachStructs.Contains(s.Name)).Any(s =>
                        s.Fields.Any(f => f.Type == "FMaterialAttributes")))
                usesMaterialAttributes = true;

            HashSet<string> renamedSymbols = new(StringComparer.Ordinal);
            foreach (DeclStruct st in allStructs)
                if (reachStructs.Contains(st.Name) && st.Name != "FMaterialAttributes")
                    renamedSymbols.Add(st.Name);
            foreach (DeclGlobal gl in allGlobals)
                if (reachGlobals.Contains(gl.Name))
                    renamedSymbols.Add(gl.Name);
            foreach (DeclFunction fn in allFunctions)
                if (reachFuncs.Contains(fn.Name))
                    renamedSymbols.Add(fn.Name);

            Dictionary<string, HashSet<int>> helperSamplerIndices = new(StringComparer.Ordinal);
            foreach (DeclFunction fn in allFunctions)
                if (reachFuncs.Contains(fn.Name) && fn.SamplerStateParamIndices.Count > 0)
                    helperSamplerIndices[prefix + fn.Name] = fn.SamplerStateParamIndices;

            HashSet<string> knownStructNames = new(StringComparer.Ordinal);
            if (usesMaterialAttributes) knownStructNames.Add("FMaterialAttributes");
            foreach (DeclStruct st in allStructs)
                if (reachStructs.Contains(st.Name))
                    knownStructNames.Add(st.Name == "FMaterialAttributes" ? "FMaterialAttributes" : prefix + st.Name);

            // Inspect Parameters.* / View.* / LWC* across code and reachable helpers first
            InspectEnvironmentUsage(hoistedCodeTokens, new Dictionary<string, string>(StringComparer.Ordinal));
            foreach (DeclFunction fn in allFunctions.Where(f => reachFuncs.Contains(f.Name))) {
                Dictionary<string, string> paramTypes = new(StringComparer.Ordinal);
                foreach (DeclParam p in fn.Params) paramTypes[p.Name] = p.Type;
                InspectEnvironmentUsage(fn.BodyTokens, paramTypes);
            }
            foreach (DeclGlobal gl in allGlobals.Where(g => reachGlobals.Contains(g.Name)))
                InspectEnvironmentUsage(gl.InitTokens, new Dictionary<string, string>(StringComparer.Ordinal));

            // Translate reachable structs
            List<string> emittedStructs = new();
            List<DeclStruct> orderedStructs = TopologicalStructs(allStructs.Where(s => reachStructs.Contains(s.Name) && s.Name != "FMaterialAttributes").ToList());
            foreach (DeclStruct st in orderedStructs)
                emittedStructs.Add(EmitStruct(st, renamedSymbols));

            // Determine if Parameters struct is needed (either referenced in code or helpers)
            string paramsStructName = prefix + "Params";
            if (usesParametersObject || usesWorldPositionHelper) {
                emittedStructs.Add(EmitParametersStruct(paramsStructName));
            }

            // Translate reachable globals
            List<string> emittedGlobals = new();
            foreach (DeclGlobal gl in allGlobals) {
                if (!reachGlobals.Contains(gl.Name)) continue;
                string emittedGlobal = EmitGlobal(gl, renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName);
                if (!string.IsNullOrWhiteSpace(emittedGlobal))
                    emittedGlobals.Add(emittedGlobal);
            }

            // Translate reachable helper functions
            List<string> emittedFunctions = new();
            foreach (DeclFunction fn in allFunctions) {
                if (!reachFuncs.Contains(fn.Name)) continue;
                emittedFunctions.Add(EmitHelperFunction(fn, renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName));
            }

            // Translate main Custom function
            string funcName = $"lfx_custom_{nodeId}";
            Dictionary<string, string> mainParamTypes = new(StringComparer.Ordinal);
            foreach (PinParameter p in inputs) {
                if (p.IsSampler || p.IsVirtualTexture) mainParamTypes[p.Name] = p.SamplerType ?? "sampler2D";
                else mainParamTypes[p.Name] = GlslTypeName(p.Kind);
            }
            foreach (OutputPinSpec o in additionalOutputs)
                mainParamTypes[o.Name] = GlslTypeName(o.Kind);
            string funcBody = TranslateFunctionBody(
                hoistedCodeTokens,
                GlslTypeName(mainOutput.Kind),
                ensureReturnWrapper: true,
                renamedSymbols,
                knownStructNames,
                helperSamplerIndices,
                paramsStructName,
                mainParamTypes);

            // Emit zero-struct helpers used for `(Struct)0` casts BEFORE globals and helper functions
            List<string> emittedZeroHelpers = new();
            foreach (string zeroStruct in zeroHelperStructs.OrderBy(s => s, StringComparer.Ordinal)) {
                DeclStruct? decl = allStructs.FirstOrDefault(s => (prefix + s.Name) == zeroStruct);
                if (decl is not null)
                    emittedZeroHelpers.Add(EmitZeroStructHelper(decl, renamedSymbols));
            }

            // Emit small self-contained math/texture/clip/view support helpers BEFORE user globals/functions
            List<string> orderedSupport = new();
            EmitSupportHelpers(orderedSupport, zeroHelperStructs);
            orderedSupport.AddRange(emittedStructs);
            orderedSupport.AddRange(emittedZeroHelpers);
            orderedSupport.AddRange(emittedGlobals);
            orderedSupport.AddRange(EmitResolvedViewHelpers());
            if (usesWorldPositionHelper && usesParametersObject)
                orderedSupport.Add(EmitGetWorldPositionHelper(paramsStructName));
            orderedSupport.AddRange(matrixArgumentWrappers.Values.Select(wrapper => wrapper.Prototype));
            orderedSupport.AddRange(emittedFunctions);
            orderedSupport.AddRange(matrixArgumentWrappers.Values.Select(wrapper => wrapper.Definition));

            StringBuilder fnBuilder = new();
            List<string> sigParams = new();
            foreach (PinParameter p in inputs) {
                if (p.IsVirtualTexture) continue;
                if (p.IsSceneTexture && !bareSceneTextureInputs.Contains(p.Name)) continue;
                string paramType = p.IsSampler ? p.SamplerType! : GlslTypeName(p.Kind);
                sigParams.Add($"{paramType} {p.Name}");
                if (p.HasLwc && usedLwcInputs.Contains(p.Name)) {
                    env.EnsureLargeWorldMath();
                    sigParams.Add($"{p.LwcType!} LWC{p.Name}");
                }
            }
            foreach (OutputPinSpec outPin in additionalOutputs)
                sigParams.Add($"inout {GlslTypeName(outPin.Kind)} {outPin.Name}");

            fnBuilder.AppendLine($"{GlslTypeName(mainOutput.Kind)} {funcName}({string.Join(", ", sigParams)}) {{");
            if (usesParametersObject || usesWorldPositionHelper)
                AppendParametersInitialization(fnBuilder, paramsStructName);
            fnBuilder.AppendLine(IndentBlock(funcBody));
            fnBuilder.Append('}');

            ResultData result = new() {
                FunctionDefinition = fnBuilder.ToString(),
                FunctionName = funcName,
                UsesMaterialAttributes = usesMaterialAttributes,
                EmitsDiscard = emitsDiscard
            };
            result.SupportDeclarations.AddRange(orderedSupport);
            foreach (string s in bareSceneTextureInputs) result.BareSceneTextureInputs.Add(s);
            foreach (string s in usedLwcInputs) result.UsedLwcInputs.Add(s);
            return result;
        }

        private void ParseTopLevelDeclarations(List<Tok> tokens)
        {
            int i = 0;
            while (i < tokens.Count) {
                if (tokens[i].Kind == TokKind.Newline) { i++; continue; }
                string word = tokens[i].Text;
                if (word is "cbuffer" or "ConstantBuffer" or "struct" && word == "cbuffer") {
                    i = SkipBlockOrStmt(tokens, i);
                    continue;
                }
                if (word == "struct") {
                    if (TryParseStruct(tokens, i, out DeclStruct st, out int next)) {
                        allStructs.Add(st);
                        i = next;
                        continue;
                    }
                }
                // Check function vs global declaration by looking ahead for '(' before '{' / '=' / ';' at bracket depth 0
                if (TryParseFunctionOrGlobal(tokens, ref i))
                    continue;
                i++;
            }
        }

        private bool TryParseFunctionOrGlobal(List<Tok> tokens, ref int index)
        {
            int i = index;
            // Collect leading tokens up to ';', '=', '{' or top-level '('
            int parenPos = -1;
            int depth = 0;
            int j = i;
            while (j < tokens.Count) {
                string t = tokens[j].Text;
                if (t is "(" or "[" or "{") {
                    if (depth == 0 && t == "(" && parenPos < 0) { parenPos = j; }
                    if (t == "{" && depth == 0) break;
                    depth++;
                } else if (t is ")" or "]" or "}") {
                    depth = Math.Max(0, depth - 1);
                } else if (depth == 0 && t is ";" or "=") {
                    break;
                }
                j++;
            }

            if (parenPos > i) {
                // Find matching ')'
                int closeParen = FindMatching(tokens, parenPos, "(", ")");
                if (closeParen > parenPos) {
                    int after = NextNonNewline(tokens, closeParen + 1);
                    // Skip optional HLSL semantic `: SV_Target`
                    if (after < tokens.Count && tokens[after].Text == ":") {
                        after = NextNonNewline(tokens, after + 1);
                        while (after < tokens.Count && tokens[after].Text is not "{" and not ";") after++;
                    }
                    if (after < tokens.Count && tokens[after].Text == "{") {
                        int closeBrace = FindMatching(tokens, after, "{", "}");
                        if (closeBrace > after) {
                            DeclFunction fn = BuildFunctionDecl(tokens, i, parenPos, closeParen, after, closeBrace);
                            allFunctions.Add(fn);
                            index = closeBrace + 1;
                            return true;
                        }
                    } else if (after < tokens.Count && tokens[after].Text == ";") {
                        // Prototype: skip cleanly
                        index = after + 1;
                        return true;
                    }
                }
            }

            // Global variable / declaration up to ';'
            int stmtEnd = FindStatementEnd(tokens, i);
            if (stmtEnd > i) {
                TryParseGlobalDecl(tokens, i, stmtEnd);
                index = stmtEnd + 1;
                return true;
            }
            return false;
        }

        private DeclFunction BuildFunctionDecl(List<Tok> tokens, int start, int openParen, int closeParen, int openBrace, int closeBrace)
        {
            List<string> headerParts = tokens.GetRange(start, openParen - start)
                .Where(t => t.Kind != TokKind.Newline).Select(t => t.Text).ToList();
            string name = headerParts.Count >= 1 ? headerParts[^1] : "fn";
            string rawRet = headerParts.Count >= 2 ? headerParts[^2] : "void";
            DeclFunction fn = new() {
                Name = name,
                ReturnType = MapTypeToken(rawRet)
            };

            List<Tok> paramTokens = tokens.GetRange(openParen + 1, closeParen - openParen - 1)
                .Where(t => t.Kind != TokKind.Newline).ToList();
            List<List<Tok>> paramGroups = SplitByTopLevelComma(paramTokens);
            for (int pIdx = 0; pIdx < paramGroups.Count; pIdx++) {
                List<Tok> pt = paramGroups[pIdx];
                if (pt.Count == 0 || (pt.Count == 1 && pt[0].Text == "void")) continue;
                // Strip default assignment `= expr`
                int eq = pt.FindIndex(t => t.Text == "=");
                if (eq >= 0) pt = pt.GetRange(0, eq);
                // Strip semantic `: TEXCOORD0`
                int colon = pt.FindIndex(t => t.Text == ":");
                if (colon >= 0) pt = pt.GetRange(0, colon);

                string arraySuffix = "";
                if (pt.Count >= 3 && pt[^1].Text == "]") {
                    int openBr = pt.FindLastIndex(t => t.Text == "[");
                    if (openBr > 0) {
                        arraySuffix = string.Concat(pt.GetRange(openBr, pt.Count - openBr).Select(t => t.Text));
                        pt = pt.GetRange(0, openBr);
                    }
                }

                if (pt.Count < 2) continue;
                string paramName = pt[^1].Text;
                string rawType = pt[^2].Text;
                List<string> quals = pt.GetRange(0, pt.Count - 2).Select(t => t.Text).ToList();
                bool isSamplerState = rawType is "SamplerState" or "SamplerComparisonState";
                if (isSamplerState) fn.SamplerStateParamIndices.Add(pIdx);
                string mappedQual = string.Join(" ", quals.Select(q => q == "in" || q == "out" || q == "inout" || q == "const" ? q : "")
                    .Where(q => q.Length > 0));
                if (quals.Contains("in") && quals.Contains("out")) mappedQual = "inout";
                fn.Params.Add(new DeclParam {
                    Qualifiers = mappedQual,
                    Type = isSamplerState ? "SamplerState" : MapTypeToken(rawType),
                    Name = paramName,
                    ArraySuffix = arraySuffix,
                    IsSamplerState = isSamplerState
                });
            }

            fn.BodyTokens = tokens.GetRange(openBrace + 1, closeBrace - openBrace - 1);
            return fn;
        }

        private void TryParseGlobalDecl(List<Tok> tokens, int start, int end)
        {
            List<Tok> clean = tokens.GetRange(start, end - start).Where(t => t.Kind != TokKind.Newline).ToList();
            if (clean.Count < 2) return;
            if (clean[0].Text is "Texture2D" or "Texture2DArray" or "TextureCube" or "TextureCubeArray"
                    or "Texture3D" or "SamplerState" or "SamplerComparisonState" or "typedef")
                return;

            bool isConst = clean.Any(t => t.Text == "const");
            int eqIdx = clean.FindIndex(t => t.Text == "=");
            List<Tok> lhs = eqIdx >= 0 ? clean.GetRange(0, eqIdx) : clean;
            string arraySuffix = "";
            if (lhs.Count >= 3 && lhs[^1].Text == "]") {
                int openBr = lhs.FindLastIndex(t => t.Text == "[");
                if (openBr > 0) {
                    arraySuffix = string.Concat(lhs.GetRange(openBr, lhs.Count - openBr).Select(t => t.Text));
                    lhs = lhs.GetRange(0, openBr);
                }
            }
            if (lhs.Count < 2) return;
            string name = lhs[^1].Text;
            string type = MapTypeToken(lhs[^2].Text);
            if (name == "Pi" || name == "PI") { /* keep user PI if defined */ }
            allGlobals.Add(new DeclGlobal {
                Type = type,
                Name = name,
                ArraySuffix = arraySuffix,
                IsConst = isConst,
                InitTokens = eqIdx >= 0 ? clean.GetRange(eqIdx + 1, clean.Count - eqIdx - 1) : new List<Tok>(),
                AllTokens = clean
            });
        }

        private List<Tok> ExtractStructsFromFunctionBody(List<Tok> tokens)
        {
            List<Tok> output = new(tokens.Count);
            int i = 0;
            while (i < tokens.Count) {
                if (tokens[i].Text == "struct" && TryParseStruct(tokens, i, out DeclStruct st, out int next)) {
                    allStructs.Add(st);
                    i = next;
                    continue;
                }
                output.Add(tokens[i]);
                i++;
            }
            return output;
        }

        private static bool TryParseStruct(List<Tok> tokens, int start, out DeclStruct st, out int afterEnd)
        {
            st = new DeclStruct();
            afterEnd = start;
            int nameIdx = NextNonNewline(tokens, start + 1);
            if (nameIdx >= tokens.Count || tokens[nameIdx].Kind != TokKind.Ident) return false;
            int open = NextNonNewline(tokens, nameIdx + 1);
            if (open >= tokens.Count || tokens[open].Text != "{") return false;
            int close = FindMatching(tokens, open, "{", "}");
            if (close < 0) return false;
            int semi = NextNonNewline(tokens, close + 1);
            if (semi < tokens.Count && tokens[semi].Text == ";") afterEnd = semi + 1;
            else afterEnd = close + 1;

            st.Name = tokens[nameIdx].Text;
            st.RawBodyTokens = tokens.GetRange(open + 1, close - open - 1);
            // Parse fields separated by ';' inside body (ignoring methods if any)
            List<Tok> fieldTokens = st.RawBodyTokens.Where(t => t.Kind != TokKind.Newline).ToList();
            int pos = 0;
            while (pos < fieldTokens.Count) {
                int fEnd = FindStatementEnd(fieldTokens, pos);
                if (fEnd <= pos) { pos++; continue; }
                List<Tok> fSlice = fieldTokens.GetRange(pos, fEnd - pos);
                pos = fEnd + 1;
                if (fSlice.Any(t => t.Text == "(")) continue; // skip helper methods if any
                if (fSlice.Count >= 2) {
                    string arr = "";
                    if (fSlice[^1].Text == "]") {
                        int ob = fSlice.FindLastIndex(t => t.Text == "[");
                        if (ob > 0) {
                            arr = string.Concat(fSlice.GetRange(ob, fSlice.Count - ob).Select(t => t.Text));
                            fSlice = fSlice.GetRange(0, ob);
                        }
                    }
                    if (fSlice.Count >= 2)
                        st.Fields.Add((MapTypeToken(fSlice[^2].Text), fSlice[^1].Text, arr));
                }
            }
            return true;
        }

        private (HashSet<string> Funcs, HashSet<string> Structs, HashSet<string> Globals) ComputeReachable(List<Tok> rootTokens)
        {
            HashSet<string> funcs = new(StringComparer.Ordinal);
            HashSet<string> structs = new(StringComparer.Ordinal);
            HashSet<string> globals = new(StringComparer.Ordinal);
            Queue<string> work = new();

            void EnqueueIdents(IEnumerable<Tok> toks) {
                Tok? prev = null;
                foreach (Tok t in toks) {
                    if (t.Kind == TokKind.Newline) continue;
                    if (t.Kind == TokKind.Ident && prev?.Text != ".") work.Enqueue(t.Text);
                    prev = t;
                }
            }

            EnqueueIdents(rootTokens);
            foreach (PinParameter p in inputs) work.Enqueue(GlslTypeName(p.Kind));
            work.Enqueue(GlslTypeName(mainOutput.Kind));
            foreach (OutputPinSpec o in additionalOutputs) work.Enqueue(GlslTypeName(o.Kind));

            while (work.Count > 0) {
                string ident = work.Dequeue();
                foreach (DeclFunction fn in allFunctions) {
                    if (fn.Name == ident && funcs.Add(fn.Name)) {
                        work.Enqueue(fn.ReturnType);
                        foreach (DeclParam p in fn.Params) work.Enqueue(p.Type);
                        EnqueueIdents(fn.BodyTokens);
                    }
                }
                foreach (DeclStruct st in allStructs) {
                    if (st.Name == ident && structs.Add(st.Name)) {
                        foreach (var field in st.Fields) work.Enqueue(field.Type);
                    }
                }
                foreach (DeclGlobal gl in allGlobals) {
                    if (gl.Name == ident && globals.Add(gl.Name)) {
                        work.Enqueue(gl.Type);
                        EnqueueIdents(gl.InitTokens);
                    }
                }
            }
            return (funcs, structs, globals);
        }

        private void PreScanSpecialInputs(List<Tok> tokens)
        {
            for (int i = 0; i < tokens.Count; i++) {
                if (tokens[i].Kind != TokKind.Ident) continue;
                string name = tokens[i].Text;
                if (name == "PI") usesPiConstant = true;

                // Track LWC<PinName>
                if (name.StartsWith("LWC", StringComparison.Ordinal)) {
                    string basePin = name["LWC".Length..];
                    if (inputByName.TryGetValue(basePin, out PinParameter? lwcPin) && lwcPin.HasLwc)
                        usedLwcInputs.Add(basePin);
                }

                // Track SceneTexture bare vs .ID / .Fetch(
                if (inputByName.TryGetValue(name, out PinParameter? pin) && pin.IsSceneTexture) {
                    bool isMember = i > 0 && tokens[i - 1].Text == ".";
                    if (isMember) continue;
                    int next = NextNonNewline(tokens, i + 1);
                    if (next < tokens.Count && tokens[next].Text == ".") {
                        int field = NextNonNewline(tokens, next + 1);
                        if (field < tokens.Count && (tokens[field].Text == "ID" || tokens[field].Text == "Fetch"))
                            continue;
                    }
                    bareSceneTextureInputs.Add(name);
                }
            }
        }

        private void InspectEnvironmentUsage(List<Tok> tokens, Dictionary<string, string> localParamTypes)
        {
            for (int i = 0; i < tokens.Count; i++) {
                if (tokens[i].Kind != TokKind.Ident) continue;
                string name = tokens[i].Text;
                if (name == "PI") usesPiConstant = true;

                bool isMatParamIdent = name == "Parameters"
                    || (localParamTypes.TryGetValue(name, out string? pType)
                        && pType is "FMaterialPixelParameters" or "FMaterialVertexParameters");

                if (name == "GetWorldPosition") {
                    int n1 = NextNonNewline(tokens, i + 1);
                    if (n1 < tokens.Count && tokens[n1].Text == "(") {
                        int n2 = NextNonNewline(tokens, n1 + 1);
                        if (n2 < tokens.Count && (tokens[n2].Text == "Parameters"
                                || (localParamTypes.TryGetValue(tokens[n2].Text, out string? argType)
                                    && argType is "FMaterialPixelParameters" or "FMaterialVertexParameters")))
                            usesWorldPositionHelper = usesParametersObject = true;
                    }
                }

                if (name is "FMaterialPixelParameters" or "FMaterialVertexParameters")
                    usesParametersObject = true;

                if (isMatParamIdent && !(i > 0 && tokens[i - 1].Text == ".")) {
                    int dot = NextNonNewline(tokens, i + 1);
                    if (dot < tokens.Count && tokens[dot].Text == ".") {
                        int fld = NextNonNewline(tokens, dot + 1);
                        if (fld < tokens.Count && tokens[fld].Kind == TokKind.Ident) {
                            string field = tokens[fld].Text;
                            if (field == "TexCoords") {
                                int ob = NextNonNewline(tokens, fld + 1);
                                if (ob < tokens.Count && tokens[ob].Text == "[") {
                                    int idxTok = NextNonNewline(tokens, ob + 1);
                                    if (idxTok < tokens.Count && int.TryParse(tokens[idxTok].Text, out int tcIdx) && tcIdx is >= 0 and < 8)
                                        usedTexCoordIndices.Add(tcIdx);
                                    else
                                        for (int k = 0; k < 8; k++) usedTexCoordIndices.Add(k);
                                }
                                usedParameterFields.Add("TexCoords");
                                usesParametersObject = true;
                            } else if (field == "Particle") {
                                int dot2 = NextNonNewline(tokens, fld + 1);
                                int fld2 = dot2 < tokens.Count && tokens[dot2].Text == "." ? NextNonNewline(tokens, dot2 + 1) : -1;
                                if (fld2 >= 0 && fld2 < tokens.Count && tokens[fld2].Kind == TokKind.Ident) {
                                    usedParameterFields.Add("Particle." + tokens[fld2].Text);
                                    usesParametersObject = true;
                                }
                            } else {
                                usedParameterFields.Add(field);
                                usesParametersObject = true;
                            }
                        }
                    } else {
                        usesParametersObject = true;
                    }
                }

                if (localParamTypes.ContainsKey(name)) continue;

                if (name is "View" or "ResolvedView" or "PrimaryView" && !(i > 0 && tokens[i - 1].Text == ".")) {
                    int dot = NextNonNewline(tokens, i + 1);
                    if (dot < tokens.Count && tokens[dot].Text == ".") {
                        int fld = NextNonNewline(tokens, dot + 1);
                        if (fld < tokens.Count && tokens[fld].Kind == TokKind.Ident) {
                            string field = tokens[fld].Text;
                            if (!resolvedViewFields.ContainsKey(field))
                                resolvedViewFields[field] = env.ResolveViewField(field);
                        }
                    }
                }
            }
        }

        private string EmitStruct(DeclStruct st, HashSet<string> renamedSymbols)
        {
            StringBuilder sb = new();
            sb.AppendLine($"struct {RenameSymbol(st.Name, renamedSymbols)} {{");
            foreach (var f in st.Fields)
                sb.AppendLine($"    {RenameType(f.Type, renamedSymbols)} {f.Name}{f.ArraySuffix};");
            sb.Append("};");
            return sb.ToString();
        }

        private string EmitZeroStructHelper(DeclStruct st, HashSet<string> renamedSymbols)
        {
            string structName = RenameSymbol(st.Name, renamedSymbols);
            StringBuilder sb = new();
            sb.AppendLine($"{structName} lfx_zero_{structName}() {{");
            sb.AppendLine($"    {structName} r;");
            foreach (var f in st.Fields) {
                if (f.ArraySuffix.Length == 0) {
                    string z = ZeroForTypeName(RenameType(f.Type, renamedSymbols));
                    if (z is not null)
                        sb.AppendLine($"    r.{f.Name} = {z};");
                }
            }
            sb.AppendLine("    return r;");
            sb.Append('}');
            return sb.ToString();
        }

        private string? ZeroForTypeName(string glslType)
        {
            if (glslType is "float") return "0.0";
            if (glslType is "vec2") return "vec2(0.0)";
            if (glslType is "vec3") return "vec3(0.0)";
            if (glslType is "vec4") return "vec4(0.0)";
            if (glslType is "int") return "0";
            if (glslType is "ivec2") return "ivec2(0)";
            if (glslType is "ivec3") return "ivec3(0)";
            if (glslType is "ivec4") return "ivec4(0)";
            if (glslType is "uint") return "0u";
            if (glslType is "uvec2") return "uvec2(0u)";
            if (glslType is "uvec3") return "uvec3(0u)";
            if (glslType is "uvec4") return "uvec4(0u)";
            if (glslType is "bool") return "false";
            if (glslType is "bvec2") return "bvec2(false)";
            if (glslType is "bvec3") return "bvec3(false)";
            if (glslType is "bvec4") return "bvec4(false)";
            if (FromGlslTypeName(glslType).IsMatrix) return $"{glslType}(0.0)";
            if (glslType == "FMaterialAttributes") return "lfx_zero_FMaterialAttributes()";
            if (allStructs.Any(s => prefix + s.Name == glslType || s.Name == glslType)) {
                zeroHelperStructs.Add(glslType);
                return $"lfx_zero_{glslType}()";
            }
            return null;
        }

        private string EmitParametersStruct(string structName)
        {
            if (usedParameterFields.Count == 0 && !usedTexCoordIndices.Any())
                usedParameterFields.Add("TexCoords");

            StringBuilder sb = new();
            bool hasParticle = usedParameterFields.Any(f => f.StartsWith("Particle.", StringComparison.Ordinal));
            if (hasParticle) {
                sb.AppendLine($"struct {structName}_Particle {{");
                foreach (string field in usedParameterFields.Where(f => f.StartsWith("Particle.", StringComparison.Ordinal)).OrderBy(f => f)) {
                    string pField = field["Particle.".Length..];
                    var resolved = env.ResolveParameterField(field);
                    sb.AppendLine($"    {resolved.GlslType} {pField};");
                }
                sb.AppendLine("};");
            }
            sb.AppendLine($"struct {structName} {{");
            if (usedParameterFields.Contains("TexCoords")) {
                int count = usedTexCoordIndices.Count > 0 ? usedTexCoordIndices.Max() + 1 : 1;
                sb.AppendLine($"    vec2 TexCoords[{count}];");
            }
            if (hasParticle)
                sb.AppendLine($"    {structName}_Particle Particle;");
            foreach (string field in usedParameterFields.Where(f => f != "TexCoords" && !f.StartsWith("Particle.", StringComparison.Ordinal)).OrderBy(f => f)) {
                var resolved = env.ResolveParameterField(field);
                sb.AppendLine($"    {resolved.GlslType} {field};");
            }
            if (usedParameterFields.Count == 0)
                sb.AppendLine("    int _unused;");
            sb.Append("};");
            return sb.ToString();
        }

        private void AppendParametersInitialization(StringBuilder sb, string structName)
        {
            sb.AppendLine($"    {structName} Parameters;");
            if (usedParameterFields.Contains("TexCoords")) {
                int count = usedTexCoordIndices.Count > 0 ? usedTexCoordIndices.Max() + 1 : 1;
                for (int tc = 0; tc < count; tc++) {
                    if (usedTexCoordIndices.Contains(tc) || usedTexCoordIndices.Count == 0) {
                        var resolved = env.ResolveParameterField($"TexCoords[{tc}]");
                        sb.AppendLine($"    Parameters.TexCoords[{tc}] = {resolved.GlslInitExpr};");
                    } else {
                        sb.AppendLine($"    Parameters.TexCoords[{tc}] = vec2(0.0);");
                    }
                }
            }
            foreach (string field in usedParameterFields.Where(f => f != "TexCoords").OrderBy(f => f)) {
                var resolved = env.ResolveParameterField(field);
                sb.AppendLine($"    Parameters.{field} = {resolved.GlslInitExpr};");
            }
        }

        private string EmitGetWorldPositionHelper(string paramsStructName)
        {
            env.EnsureLargeWorldMath();
            string worldLwcExpr = env.ResolveGetWorldPosition();
            return $"LfxLWC3 {prefix}GetWorldPosition({paramsStructName} p) {{ return {worldLwcExpr}; }}";
        }

        private IEnumerable<string> EmitResolvedViewHelpers()
        {
            if (resolvedViewFields.Count == 0) yield break;
            StringBuilder sb = new();
            sb.AppendLine($"struct {prefix}ViewType {{");
            foreach (var kv in resolvedViewFields.OrderBy(k => k.Key, StringComparer.Ordinal))
                sb.AppendLine($"    {kv.Value.GlslType} {kv.Key};");
            sb.AppendLine("};");
            sb.Append($"{prefix}ViewType {prefix}read_view() {{ return {prefix}ViewType(");
            sb.Append(string.Join(", ", resolvedViewFields.OrderBy(k => k.Key, StringComparer.Ordinal).Select(k => k.Value.GlslExpr)));
            sb.Append("); }");
            yield return sb.ToString();
        }

        private string EmitGlobal(
            DeclGlobal gl,
            HashSet<string> renamedSymbols,
            HashSet<string> knownStructNames,
            Dictionary<string, HashSet<int>> helperSamplerIndices,
            string paramsStructName)
        {
            string type = RenameType(gl.Type, renamedSymbols);
            string name = RenameSymbol(gl.Name, renamedSymbols);
            string qual = gl.IsConst ? "const " : "";
            if (gl.InitTokens.Count == 0)
                return $"{qual}{type} {name}{gl.ArraySuffix};";
            string rhs = TranslateExpressionTokens(gl.InitTokens, renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, new Dictionary<string, string>(StringComparer.Ordinal));
            string arrSz = gl.ArraySuffix;
            if (rhs.TrimStart().StartsWith('{')) {
                (string formattedInit, int elementCount) = RewriteBraceArrayInitializer(type, rhs);
                rhs = formattedInit;
                if (arrSz == "[]") arrSz = $"[{elementCount}]";
            }
            return $"{qual}{type} {name}{arrSz} = {NormalizeTypedInitializer(type, arrSz, rhs)};";
        }

        private string EmitHelperFunction(
            DeclFunction fn,
            HashSet<string> renamedSymbols,
            HashSet<string> knownStructNames,
            Dictionary<string, HashSet<int>> helperSamplerIndices,
            string paramsStructName)
        {
            string retType = RenameType(fn.ReturnType, renamedSymbols);
            string fnName = RenameSymbol(fn.Name, renamedSymbols);
            Dictionary<string, string> locals = new(StringComparer.Ordinal);
            Dictionary<string, (int Rows, int Cols)> paramDegenerates = new(StringComparer.Ordinal);
            List<string> paramDecls = new();
            foreach (DeclParam p in fn.Params) {
                if (p.IsSamplerState) continue;
                string pType = p.Type is "FMaterialPixelParameters" or "FMaterialVertexParameters"
                    ? paramsStructName
                    : RenameType(p.Type, renamedSymbols);
                string arrRank = ArrayRankSuffix(p.ArraySuffix);
                locals[p.Name] = arrRank.Length > 0 ? pType + arrRank : pType;
                if (arrRank.Length == 0 && TryParseDegenerateMatrixType(p.Type, out int pr, out int pc))
                    paramDegenerates[p.Name] = (pr, pc);
                string qualPrefix = p.Qualifiers.Length > 0 ? p.Qualifiers + " " : "";
                paramDecls.Add($"{qualPrefix}{pType} {p.Name}{p.ArraySuffix}");
            }
            string body = TranslateFunctionBody(
                fn.BodyTokens,
                retType,
                ensureReturnWrapper: false,
                renamedSymbols,
                knownStructNames,
                helperSamplerIndices,
                paramsStructName,
                locals,
                paramDegenerates);
            return $"{retType} {fnName}({string.Join(", ", paramDecls)}) {{\n{IndentBlock(body)}\n}}";
        }

        private string TranslateFunctionBody(
            List<Tok> rawBodyTokens,
            string returnType,
            bool ensureReturnWrapper,
            HashSet<string> renamedSymbols,
            HashSet<string> knownStructNames,
            Dictionary<string, HashSet<int>> helperSamplerIndices,
            string paramsStructName,
            Dictionary<string, string> localParamTypes,
            Dictionary<string, (int Rows, int Cols)>? localParamDegenerates = null)
        {
            List<Tok> tokens = new(rawBodyTokens);
            // If ensureReturnWrapper and no top-level 'return' keyword exists, wrap Code as return <Code>;
            bool hasReturn = tokens.Any(t => t.Kind == TokKind.Ident && t.Text == "return");
            if (ensureReturnWrapper && !hasReturn) {
                List<Tok> nonEmpty = tokens.Where(t => t.Kind != TokKind.Newline).ToList();
                while (nonEmpty.Count > 0 && nonEmpty[^1].Text == ";")
                    nonEmpty.RemoveAt(nonEmpty.Count - 1);
                if (nonEmpty.Count == 0)
                    return $"return {ZeroForTypeName(returnType) ?? "0.0"};";
                // If single expression (no inner ';'), return ( <expr> );
                if (!nonEmpty.Any(t => t.Text == ";")) {
                    tokens = new List<Tok> { new(TokKind.Ident, "return") };
                    tokens.AddRange(nonEmpty);
                    tokens.Add(new(TokKind.Symbol, ";"));
                } else {
                    // Multi-statement block with AdditionalOutputs and no explicit return statement
                    tokens.Add(new(TokKind.Ident, "return"));
                    tokens.Add(new(TokKind.Number, "0"));
                    tokens.Add(new(TokKind.Symbol, ";"));
                }
            }

            List<string>? previousLocals = matrixArgumentLocals;
            matrixArgumentLocals = new();
            try {
                List<Tok> transformed = TransformTokenStream(tokens, renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localParamTypes, returnType, null, localParamDegenerates);
                string body = RenderTokens(transformed);
                return matrixArgumentLocals.Count == 0 ? body : string.Join("\n", matrixArgumentLocals) + "\n" + body;
            } finally {
                matrixArgumentLocals = previousLocals;
            }
        }

        private List<Tok> TransformTokenStream(
            List<Tok> tokens,
            HashSet<string> renamedSymbols,
            HashSet<string> knownStructNames,
            Dictionary<string, HashSet<int>> helperSamplerIndices,
            string paramsStructName,
            Dictionary<string, string> localParamTypes,
            string? functionReturnType,
            Dictionary<string, string>? inheritedConstInits = null,
            Dictionary<string, (int Rows, int Cols)>? inheritedDegenerates = null)
        {
            // Step 1: Resolve SceneTexture .ID/.Fetch(), Texture macros/methods, View.*/ResolvedView.*, GetWorldPosition(), intrinsics, type keywords, numeric literals, array inits, C-casts, mul()
            Dictionary<string, string> localVarTypes = new(localParamTypes, StringComparer.Ordinal);
            Dictionary<string, string> localConstInits = inheritedConstInits is null
                ? new(StringComparer.Ordinal)
                : new(inheritedConstInits, StringComparer.Ordinal);
            Dictionary<string, (int Rows, int Cols)> localDegenerates = inheritedDegenerates is null
                ? new(StringComparer.Ordinal)
                : new(inheritedDegenerates, StringComparer.Ordinal);
            Stack<Dictionary<string, string>> scopeStack = new();
            Stack<Dictionary<string, string>> constScopeStack = new();
            Stack<Dictionary<string, (int Rows, int Cols)>> degenerateScopeStack = new();
            List<Tok> pass1 = new(tokens.Count);
            bool atDeclStart = true;
            for (int i = 0; i < tokens.Count; i++) {
                Tok t = tokens[i];
                if (t.Kind == TokKind.Newline) { pass1.Add(t); continue; }

                if (t.Text == "{") {
                    scopeStack.Push(new Dictionary<string, string>(localVarTypes, StringComparer.Ordinal));
                    constScopeStack.Push(new Dictionary<string, string>(localConstInits, StringComparer.Ordinal));
                    degenerateScopeStack.Push(new Dictionary<string, (int Rows, int Cols)>(localDegenerates, StringComparer.Ordinal));
                } else if (t.Text == "}") {
                    if (scopeStack.Count > 0) localVarTypes = scopeStack.Pop();
                    if (constScopeStack.Count > 0) localConstInits = constScopeStack.Pop();
                    if (degenerateScopeStack.Count > 0) localDegenerates = degenerateScopeStack.Pop();
                }

                if (t.Text == "(" && TryReadPrimitiveCast(tokens, i, out ExprType castType, out int castStart)) {
                    int castEnd = ReadUnaryOperandEnd(tokens, castStart);
                    var operand = TransformTokenStream(tokens.GetRange(castStart, castEnd - castStart + 1),
                        renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits);
                    string operandText = RenderTokens(operand).Trim();
                    ExprType sourceType = InferGlslExprType(operand, localVarTypes);
                    string cast = ConvertPrimitiveCast(castType, sourceType, operandText);
                    pass1.Add(new Tok(TokKind.Ident, cast));
                    atDeclStart = false;
                    i = castEnd;
                    continue;
                }

                if (t.Text == ".") {
                    int memberIndex = NextNonNewline(tokens, i + 1);
                    if (memberIndex < tokens.Count && IsMatrixSelector(tokens[memberIndex].Text)) {
                        int start = FindMultiplicativeOperandStart(pass1, postfix: true);
                        var baseTokens = pass1.GetRange(start, pass1.Count - start);
                        ExprType type = InferGlslExprType(baseTokens, localVarTypes);
                        if (type.IsMatrix) {
                            var members = ReadMatrixMembers(type, tokens[memberIndex].Text);
                            string receiver = RenderTokens(baseTokens).Trim();
                            int assignment = NextNonNewline(tokens, memberIndex + 1);
                            string expression;
                            if (assignment < tokens.Count && tokens[assignment].Text is "=" or "+=" or "-=" or "*=" or "/=" or "%=") {
                                int end = MatrixAssignmentEnd(tokens, assignment + 1);
                                var right = TransformTokenStream(tokens.GetRange(assignment + 1, end - assignment - 1),
                                    renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits);
                                expression = AssignMatrixSelection(type, members, receiver, tokens[assignment].Text, RenderTokens(right).Trim());
                                i = end - 1;
                            } else if (assignment < tokens.Count && tokens[assignment].Text is "++" or "--") {
                                expression = AssignMatrixSelection(type, members, receiver,
                                    tokens[assignment].Text == "++" ? "+=" : "-=", "1.0", postfix: true);
                                i = assignment;
                            } else {
                                int before = start - 1;
                                while (before >= 0 && pass1[before].Kind == TokKind.Newline) before--;
                                if (before >= 0 && pass1[before].Text is "++" or "--") {
                                    expression = AssignMatrixSelection(type, members, receiver,
                                        pass1[before].Text == "++" ? "+=" : "-=", "1.0");
                                    start = before;
                                } else {
                                    expression = ReadMatrixSelection(type, members, receiver);
                                }
                                i = memberIndex;
                            }
                            pass1.RemoveRange(start, pass1.Count - start);
                            pass1.Add(new Tok(TokKind.Ident, expression));
                            atDeclStart = false;
                            continue;
                        }
                        if (TryReadLinearMatrixSelector(type, tokens[memberIndex].Text, out string linearSwizzle)) {
                            string receiver = RenderTokens(baseTokens).Trim();
                            string selected = type.IsScalar
                                ? (linearSwizzle.Length == 1 ? receiver
                                    : $"{FormatExprGlslType(new ExprType(ExprKind.Vector, linearSwizzle.Length, type.Prefix))}({receiver})")
                                : $"{receiver}.{linearSwizzle}";
                            pass1.RemoveRange(start, pass1.Count - start);
                            pass1.Add(new Tok(TokKind.Ident, selected));
                            atDeclStart = false;
                            i = memberIndex;
                            continue;
                        }
                    }
                }

                if (t.Text is "*=" or "+=" or "-=" or "/=" or "%=") {
                    int start = FindMultiplicativeOperandStart(pass1, postfix: true);
                    var left = pass1.GetRange(start, pass1.Count - start);
                    ExprType type = InferGlslExprType(left, localVarTypes);
                    if (type.IsMatrix) {
                        int end = MatrixAssignmentEnd(tokens, i + 1);
                        var right = TransformTokenStream(tokens.GetRange(i + 1, end - i - 1),
                            renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits);
                        ExprType sourceType = InferGlslExprType(right, localVarTypes);
                        if (sourceType.IsMatrix && type != sourceType)
                            throw new ConversionException($"Custom HLSL compound {t.Text} has mismatched matrix shapes");
                        string target = RenderTokens(left).Trim(), argument = RenderTokens(right).Trim();
                        string expression = CompoundAssignMatrix(type, t.Text, target, argument, sourceType.IsMatrix);
                        pass1.RemoveRange(start, pass1.Count - start);
                        pass1.Add(new Tok(TokKind.Ident, expression));
                        atDeclStart = false;
                        i = end - 1;
                        continue;
                    }
                }

                if (t.Text is ("*" or "/" or "%") && i + 1 < tokens.Count) {
                    int leftStart = FindMultiplicativeOperandStart(pass1);
                    var leftTokens = pass1.GetRange(leftStart, pass1.Count - leftStart);
                    ExprType leftType = InferGlslExprType(leftTokens, localVarTypes);
                    int rightStart = NextNonNewline(tokens, i + 1);
                    if (rightStart < tokens.Count && IsCastOperandStart(tokens[rightStart])) {
                        int rightEnd = ReadUnaryOperandEnd(tokens, rightStart);
                        var rightTokens = tokens.GetRange(rightStart, rightEnd - rightStart + 1);
                        ExprType rightType = InferGlslExprType(rightTokens, localVarTypes);
                        bool matMat = leftType.IsMatrix && rightType == leftType;
                        bool matScalar = leftType.IsMatrix && rightType.IsScalar && t.Text == "%";
                        bool scalarMat = leftType.IsScalar && rightType.IsMatrix && t.Text is ("/" or "%");
                        if (matMat || matScalar || scalarMat) {
                            var right = TransformTokenStream(rightTokens, renamedSymbols, knownStructNames,
                                helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits);
                            string lhs = RenderTokens(leftTokens).Trim(), rhs = RenderTokens(right).Trim();
                            string rewritten = t.Text == "*"
                                ? $"matrixCompMult({lhs}, {rhs})"
                                : BinaryMatrixOp(leftType.IsMatrix ? leftType : rightType, t.Text, lhs, rhs,
                                    leftType.IsMatrix, rightType.IsMatrix);
                            pass1.RemoveRange(leftStart, pass1.Count - leftStart);
                            pass1.Add(new Tok(TokKind.Ident, rewritten));
                            i = rightEnd;
                            continue;
                        }
                    }
                }

                bool isMemberAccess = IsPrecedingDot(tokens, i);
                if (atDeclStart && !isMemberAccess && t.Kind == TokKind.Ident) {
                    bool isConstDecl = false;
                    int declCursor = i;
                    while (declCursor < tokens.Count && tokens[declCursor].Kind == TokKind.Ident
                           && tokens[declCursor].Text is "const" or "static" or "precise" or "in" or "out" or "inout") {
                        if (tokens[declCursor].Text == "const") isConstDecl = true;
                        declCursor = NextNonNewline(tokens, declCursor + 1);
                    }
                    if (declCursor < tokens.Count && tokens[declCursor].Kind == TokKind.Ident) {
                        string mappedDeclType = MapTypeToken(tokens[declCursor].Text);
                        if (GlslBuiltinTypes.Contains(mappedDeclType) || knownStructNames.Contains(tokens[declCursor].Text)
                            || mappedDeclType is "float" or "int" or "uint" or "bool") {
                            string finalDeclType = knownStructNames.Contains(tokens[declCursor].Text)
                                ? RenameType(tokens[declCursor].Text, renamedSymbols)
                                : mappedDeclType;
                            RecordLocalVariableDeclarations(tokens, declCursor + 1, finalDeclType, tokens[declCursor].Text, isConstDecl,
                                localVarTypes, localDegenerates, localConstInits,
                                rawInit => RenderTokens(TransformTokenStream(rawInit, renamedSymbols, knownStructNames,
                                    helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits, localDegenerates)).Trim());
                        }
                    }
                }
                atDeclStart = t.Text is ";" or "{" or "}" or "else"
                    || (t.Text == "(" && IsForHeaderOpen(tokens, i))
                    || (t.Text == ")" && IsControlHeaderClose(tokens, i));

                if (!isMemberAccess && t.Kind == TokKind.Ident) {
                    // GetWorldPosition(Parameters)
                    if (t.Text == "GetWorldPosition" && PeekNextNonNewline(tokens, i) == "(") {
                        int op = NextNonNewline(tokens, i + 1);
                        int cl = FindMatching(tokens, op, "(", ")");
                        if (cl > op) {
                            pass1.Add(new Tok(TokKind.Ident, $"{prefix}GetWorldPosition"));
                            pass1.Add(new Tok(TokKind.Symbol, "("));
                            pass1.AddRange(TransformTokenStream(tokens.GetRange(op + 1, cl - op - 1), renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits));
                            pass1.Add(new Tok(TokKind.Symbol, ")"));
                            i = cl;
                            continue;
                        }
                    }

                    // View.Field / ResolvedView.Field / PrimaryView.Field
                    if (t.Text is "View" or "ResolvedView" or "PrimaryView") {
                        int dot = NextNonNewline(tokens, i + 1);
                        if (dot < tokens.Count && tokens[dot].Text == ".") {
                            int fld = NextNonNewline(tokens, dot + 1);
                            if (fld < tokens.Count && tokens[fld].Kind == TokKind.Ident) {
                                pass1.Add(new Tok(TokKind.Symbol, "("));
                                pass1.Add(new Tok(TokKind.Ident, $"{prefix}read_view"));
                                pass1.Add(new Tok(TokKind.Symbol, "("));
                                pass1.Add(new Tok(TokKind.Symbol, ")"));
                                pass1.Add(new Tok(TokKind.Symbol, "."));
                                pass1.Add(new Tok(TokKind.Ident, tokens[fld].Text));
                                pass1.Add(new Tok(TokKind.Symbol, ")"));
                                i = fld;
                                continue;
                            }
                        }
                    }

                    // SceneTexture input pin: Pin.ID / Pin.Fetch(uv)
                    if (inputByName.TryGetValue(t.Text, out PinParameter? scenePin) && scenePin.IsSceneTexture) {
                        int dot = NextNonNewline(tokens, i + 1);
                        if (dot < tokens.Count && tokens[dot].Text == ".") {
                            int fld = NextNonNewline(tokens, dot + 1);
                            if (fld < tokens.Count && tokens[fld].Text == "ID") {
                                pass1.Add(new Tok(TokKind.Number, (scenePin.SceneTextureId ?? 0).ToString(CultureInfo.InvariantCulture)));
                                i = fld;
                                continue;
                            }
                            if (fld < tokens.Count && tokens[fld].Text == "Fetch") {
                                int op = NextNonNewline(tokens, fld + 1);
                                if (op < tokens.Count && tokens[op].Text == "(") {
                                    int cl = FindMatching(tokens, op, "(", ")");
                                    if (cl > op) {
                                        List<Tok> inner = TransformTokenStream(tokens.GetRange(op + 1, cl - op - 1), renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits);
                                        string uvGlsl = RenderTokens(inner).Trim();
                                        string fetched = env.ResolveSceneTextureFetch(scenePin.Name, uvGlsl);
                                        pass1.Add(new Tok(TokKind.Ident, fetched));
                                        i = cl;
                                        continue;
                                    }
                                }
                            }
                        }
                    }

                    // Texture sample macros: Texture2DSample(Tex, TexSampler, UV) etc.
                    if (TextureSampleMacros.TryGetValue(t.Text, out string? macroOp)) {
                        int op = NextNonNewline(tokens, i + 1);
                        if (op < tokens.Count && tokens[op].Text == "(") {
                            int cl = FindMatching(tokens, op, "(", ")");
                            if (cl > op) {
                                if (macroOp == "IgnoreScale") {
                                    pass1.Add(new Tok(TokKind.Number, "1.0"));
                                    i = cl;
                                    continue;
                                }
                                List<List<Tok>> args = SplitByTopLevelComma(tokens.GetRange(op + 1, cl - op - 1));
                                if (args.Count >= 3) {
                                    string texIdent = RenderTokens(args[0]).Trim();
                                    string? samplerType = ResolveSamplerType(texIdent, localVarTypes);
                                    List<string> translatedArgs = args.Select(a =>
                                        RenderTokens(TransformTokenStream(a, renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits)).Trim()).ToList();
                                    string call = TranslateTextureSampleCall(texIdent, macroOp, translatedArgs.Skip(2).ToList(), samplerType);
                                    pass1.Add(new Tok(TokKind.Ident, call));
                                    i = cl;
                                    continue;
                                }
                            }
                        }
                    }

                    // Texture method calls: Tex.Sample(S, uv[, offset]), Tex.SampleLevel(...), Tex.Load(...), Tex.Gather*(...), Tex.GetDimensions(...)
                    if (IsTextureObjectOrSamplerParam(t.Text, localVarTypes)) {
                        int dot = NextNonNewline(tokens, i + 1);
                        if (dot < tokens.Count && tokens[dot].Text == ".") {
                            int mTok = NextNonNewline(tokens, dot + 1);
                            int op = mTok < tokens.Count ? NextNonNewline(tokens, mTok + 1) : tokens.Count;
                            if (op < tokens.Count && tokens[op].Text == "(") {
                                int cl = FindMatching(tokens, op, "(", ")");
                                if (cl > op) {
                                    string method = tokens[mTok].Text;
                                    string? samplerType = ResolveSamplerType(t.Text, localVarTypes);
                                    List<List<Tok>> args = SplitByTopLevelComma(tokens.GetRange(op + 1, cl - op - 1));
                                    List<string> translatedArgs = args.Select(a =>
                                        RenderTokens(TransformTokenStream(a, renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits)).Trim()).ToList();
                                    if (method == "GetDimensions") {
                                        string rewritten = TranslateGetDimensions(t.Text, translatedArgs, samplerType);
                                        pass1.Add(new Tok(TokKind.Ident, rewritten));
                                        int semi = NextNonNewline(tokens, cl + 1);
                                        i = (semi < tokens.Count && tokens[semi].Text == ";") ? semi : cl;
                                        continue;
                                    }
                                    if (method == "Load" && translatedArgs.Count is >= 1 and <= 2) {
                                        string loadOff = translatedArgs.Count == 2
                                            ? InlineConstIdentifiers(translatedArgs[1], localConstInits)
                                            : "";
                                        var loadArgs = translatedArgs.Count == 2
                                            ? new List<string> { translatedArgs[0], loadOff }
                                            : translatedArgs;
                                        pass1.Add(new Tok(TokKind.Ident, TranslateTextureLoad(t.Text, loadArgs, samplerType)));
                                        i = cl;
                                        continue;
                                    }
                                    if (method is "Gather" or "GatherRed" or "GatherGreen" or "GatherBlue" or "GatherAlpha" && translatedArgs.Count >= 2) {
                                        pass1.Add(new Tok(TokKind.Ident, TranslateTextureGather(t.Text, method, translatedArgs, samplerType)));
                                        i = cl;
                                        continue;
                                    }
                                    if (method is "Sample" or "SampleLevel" or "SampleBias" or "SampleGrad" && translatedArgs.Count >= 2) {
                                        string call = TranslateTextureSampleCall(t.Text, method, translatedArgs.Skip(1).ToList(), samplerType);
                                        pass1.Add(new Tok(TokKind.Ident, call));
                                        i = cl;
                                        continue;
                                    }
                                }
                            }
                        }
                    }

                    if (!isMemberAccess && IsMatrixArgumentCallee(t.Text)) {
                        int open = NextNonNewline(tokens, i + 1);
                        if (open < tokens.Count && tokens[open].Text == "(") {
                            int close = FindMatching(tokens, open, "(", ")");
                            if (close > open) {
                                var args = SplitByTopLevelComma(tokens.GetRange(open + 1, close - open - 1));
                                string TranslateArgument(List<Tok> argument) => RenderTokens(TransformTokenStream(argument,
                                    renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits)).Trim();
                                if (LowerMatrixArguments(t.Text, args, localVarTypes, renamedSymbols, paramsStructName, TranslateArgument) is { } call) {
                                    pass1.Add(new Tok(TokKind.Ident, call));
                                    i = close;
                                    continue;
                                }
                            }
                        }
                    }

                    // Helper function calls with SamplerState parameters stripped
                    string candidateHelper = renamedSymbols.Contains(t.Text) ? prefix + t.Text : t.Text;
                    if (helperSamplerIndices.TryGetValue(candidateHelper, out HashSet<int>? samplerParamIdx)) {
                        int op = NextNonNewline(tokens, i + 1);
                        if (op < tokens.Count && tokens[op].Text == "(") {
                            int cl = FindMatching(tokens, op, "(", ")");
                            if (cl > op) {
                                List<List<Tok>> args = SplitByTopLevelComma(tokens.GetRange(op + 1, cl - op - 1));
                                List<string> kept = new();
                                for (int aIdx = 0; aIdx < args.Count; aIdx++) {
                                    if (samplerParamIdx.Contains(aIdx)) continue;
                                    kept.Add(RenderTokens(TransformTokenStream(args[aIdx], renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits)).Trim());
                                }
                                pass1.Add(new Tok(TokKind.Ident, $"{candidateHelper}({string.Join(", ", kept)})"));
                                i = cl;
                                continue;
                            }
                        }
                    }

                    // clip(expr); -> if (lfx_ch_clip_neg(expr)) discard;
                    if (t.Text == "clip") {
                        int op = NextNonNewline(tokens, i + 1);
                        if (op < tokens.Count && tokens[op].Text == "(") {
                            int cl = FindMatching(tokens, op, "(", ")");
                            if (cl > op) {
                                if (env.IsVertexStage)
                                    throw new ConversionException("Custom material clip/discard instruction requires pixel shading");
                                emitsDiscard = true;
                                usedSupportHelpers.Add("clip");
                                List<Tok> inner = TransformTokenStream(tokens.GetRange(op + 1, cl - op - 1), renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits);
                                int semi = NextNonNewline(tokens, cl + 1);
                                pass1.Add(new Tok(TokKind.Ident, $"if (lfx_ch_clip_neg({RenderTokens(inner).Trim()})) discard;"));
                                i = (semi < tokens.Count && tokens[semi].Text == ";") ? semi : cl;
                                continue;
                            }
                        }
                    }

                    if (t.Text == "discard") {
                        if (env.IsVertexStage)
                            throw new ConversionException("Custom material clip/discard instruction requires pixel shading");
                        emitsDiscard = true;
                    }

                    // mul(a, b) -> dot(a, b) for two vectors, outerProduct for Nx1 * 1xM, (a * b) for scalar, ((b) * (a)) for matrix ops
                    if (t.Text == "mul") {
                        int op = NextNonNewline(tokens, i + 1);
                        if (op < tokens.Count && tokens[op].Text == "(") {
                            int cl = FindMatching(tokens, op, "(", ")");
                            if (cl > op) {
                                List<List<Tok>> args = SplitByTopLevelComma(tokens.GetRange(op + 1, cl - op - 1));
                                if (args.Count == 2) {
                                    bool hasShapeA = TryInferDegenerateMatrixShape(args[0], localDegenerates, localVarTypes, out int ra, out int ca);
                                    bool hasShapeB = TryInferDegenerateMatrixShape(args[1], localDegenerates, localVarTypes, out int rb, out int cb);
                                    List<Tok> tokA = TransformTokenStream(args[0], renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits, localDegenerates);
                                    List<Tok> tokB = TransformTokenStream(args[1], renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits, localDegenerates);
                                    string rewrittenMul;
                                    if (hasShapeA && hasShapeB && ca == 1 && rb == 1 && ra >= 2 && cb >= 2) {
                                        ExprType typeA = InferGlslExprType(tokA, localVarTypes);
                                        ExprType typeB = InferGlslExprType(tokB, localVarTypes);
                                        if (typeA.IsVector && typeA.Prefix == 'f' && typeB.IsVector && typeB.Prefix == 'f') {
                                            rewrittenMul = $"outerProduct(({RenderTokens(tokB).Trim()}), ({RenderTokens(tokA).Trim()}))";
                                        } else {
                                            rewrittenMul = RewriteMulCall(tokA, tokB, localVarTypes);
                                        }
                                    } else {
                                        rewrittenMul = RewriteMulCall(tokA, tokB, localVarTypes);
                                    }
                                    pass1.Add(new Tok(TokKind.Ident, rewrittenMul));
                                    i = cl;
                                    continue;
                                }
                            }
                        }
                    }

                    // transpose(m) -> identity for 1x1 / 1xN / Nx1 degenerate matrices represented as GLSL scalars/vectors
                    if (t.Text == "transpose") {
                        int op = NextNonNewline(tokens, i + 1);
                        if (op < tokens.Count && tokens[op].Text == "(") {
                            int cl = FindMatching(tokens, op, "(", ")");
                            if (cl > op) {
                                List<List<Tok>> args = SplitByTopLevelComma(tokens.GetRange(op + 1, cl - op - 1));
                                if (args.Count == 1) {
                                    List<Tok> tokA = TransformTokenStream(args[0], renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, null, localConstInits, localDegenerates);
                                    ExprType typeA = InferGlslExprType(tokA, localVarTypes);
                                    string renderedArg = RenderTokens(tokA).Trim();
                                    string rewrittenTranspose = (typeA.IsScalar || typeA.IsVector)
                                        ? $"({renderedArg})"
                                        : $"transpose({renderedArg})";
                                    pass1.Add(new Tok(TokKind.Ident, rewrittenTranspose));
                                    i = cl;
                                    continue;
                                }
                            }
                        }
                    }

                    // UE built-in material view / pass state helpers
                    if (TryTranslateMaterialBuiltinCall(tokens, i, renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, out string? builtinGlsl, out int builtinConsumedTo, localConstInits)) {
                        pass1.Add(new Tok(TokKind.Ident, builtinGlsl!));
                        i = builtinConsumedTo;
                        continue;
                    }

                    // Intrinsics & type remapping
                    if (TryTranslateIntrinsicOrType(tokens, i, renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localVarTypes, out Tok? replaced, out int consumedTo, localConstInits)) {
                        if (replaced is not null) pass1.Add(replaced);
                        i = consumedTo;
                        continue;
                    }
                }

                // Standalone numbers -> strip f / h suffix cleanly
                if (t.Kind == TokKind.Number) {
                    pass1.Add(new Tok(TokKind.Number, TranslateNumberLiteral(t.Text)));
                    continue;
                }

                pass1.Add(t);
            }

            // Step 2: C-style casts (Type)expr -> Type(expr) or lfx_zero_Struct()
            List<Tok> pass2 = RewriteCStyleCasts(pass1, knownStructNames);

            // Step 3: Array brace initializers + vecN scalar literal initializers + return scalar normalization
            return RewriteStatementsAndInitializers(pass2, functionReturnType);
        }

        private string TranslateTextureSampleCall(string texIdent, string method, List<string> argsAfterSampler, string? samplerType)
        {
            if (inputByName.TryGetValue(texIdent, out PinParameter? pin) && pin.IsVirtualTexture)
                return env.ResolveVirtualTextureSample(pin.Name, method, argsAfterSampler);

            bool isCube = samplerType is "samplerCube" or "samplerCubeArray";
            switch (method) {
                case "Sample" when argsAfterSampler.Count == 1:
                    return env.IsVertexStage ? $"textureLod({texIdent}, {argsAfterSampler[0]}, 0.0)" : $"texture({texIdent}, {argsAfterSampler[0]})";
                case "Sample" when argsAfterSampler.Count == 2:
                    if (isCube) throw new ConversionException($"Cube sampler {texIdent} does not support Sample texel offsets");
                    return env.IsVertexStage
                        ? $"textureLodOffset({texIdent}, {argsAfterSampler[0]}, 0.0, {argsAfterSampler[1]})"
                        : $"textureOffset({texIdent}, {argsAfterSampler[0]}, {argsAfterSampler[1]})";
                case "SampleLevel" when argsAfterSampler.Count == 2:
                    return $"textureLod({texIdent}, {argsAfterSampler[0]}, {argsAfterSampler[1]})";
                case "SampleLevel" when argsAfterSampler.Count == 3:
                    if (isCube) throw new ConversionException($"Cube sampler {texIdent} does not support SampleLevel texel offsets");
                    return $"textureLodOffset({texIdent}, {argsAfterSampler[0]}, {argsAfterSampler[1]}, {argsAfterSampler[2]})";
                case "SampleBias" when argsAfterSampler.Count == 2:
                    return env.IsVertexStage
                        ? $"textureLod({texIdent}, {argsAfterSampler[0]}, {argsAfterSampler[1]})"
                        : $"texture({texIdent}, {argsAfterSampler[0]}, {argsAfterSampler[1]})";
                case "SampleBias" when argsAfterSampler.Count == 3:
                    if (isCube) throw new ConversionException($"Cube sampler {texIdent} does not support SampleBias texel offsets");
                    return env.IsVertexStage
                        ? $"textureLodOffset({texIdent}, {argsAfterSampler[0]}, {argsAfterSampler[1]}, {argsAfterSampler[2]})"
                        : $"textureOffset({texIdent}, {argsAfterSampler[0]}, {argsAfterSampler[2]}, {argsAfterSampler[1]})";
                case "SampleGrad" when argsAfterSampler.Count == 3:
                    return $"textureGrad({texIdent}, {argsAfterSampler[0]}, {argsAfterSampler[1]}, {argsAfterSampler[2]})";
                case "SampleGrad" when argsAfterSampler.Count == 4:
                    if (isCube) throw new ConversionException($"Cube sampler {texIdent} does not support SampleGrad texel offsets");
                    return $"textureGradOffset({texIdent}, {argsAfterSampler[0]}, {argsAfterSampler[1]}, {argsAfterSampler[2]}, {argsAfterSampler[3]})";
                default:
                    throw new ConversionException($"Unsupported custom material texture operation {method} ({argsAfterSampler.Count} args) on {texIdent}");
            }
        }

        private string TranslateTextureLoad(string texIdent, List<string> args, string? samplerType)
        {
            string loc = args[0];
            if (args.Count == 1) {
                if (samplerType is null or "sampler2D" or "sampler2DArray" or "sampler3D")
                    return AddFallbackLoad(texIdent, loc);
                throw new ConversionException($"Load is unsupported for sampler type {samplerType} on {texIdent}");
            }
            if (args.Count == 2) {
                string off = args[1];
                if (samplerType is "sampler2D" or "sampler2DArray" or "sampler3D") {
                    string helper = GetOrAddLoadOffsetHelper(samplerType, off);
                    return $"{helper}({texIdent}, {loc})";
                }
                throw new ConversionException($"Load with offset requires known 2D, 2DArray, or 3D sampler type on {texIdent}");
            }
            throw new ConversionException($"Unsupported Load overload with {args.Count} arguments on {texIdent}");
        }

        private string GetOrAddLoadOffsetHelper(string samplerType, string offsetExpr)
        {
            var key = (samplerType, offsetExpr);
            if (!loadOffsetHelpers.TryGetValue(key, out string? name)) {
                name = $"{prefix}load_off_{loadOffsetHelpers.Count}";
                loadOffsetHelpers[key] = name;
            }
            return name;
        }

        private static string InlineConstIdentifiers(string expr, Dictionary<string, string> localConstInits)
        {
            if (localConstInits.Count == 0 || string.IsNullOrEmpty(expr)) return expr;
            List<Tok> toks = Tokenize(expr);
            bool changed = false;
            for (int i = 0; i < toks.Count; i++) {
                Tok t = toks[i];
                if (t.Kind != TokKind.Ident) continue;
                if (IsPrecedingDot(toks, i)) continue;
                int next = NextNonNewline(toks, i + 1);
                if (next < toks.Count && toks[next].Text == "(") continue;
                if (localConstInits.TryGetValue(t.Text, out string? val)) {
                    toks[i] = new Tok(TokKind.Ident, val);
                    changed = true;
                }
            }
            return changed ? RenderTokens(toks).Trim() : expr;
        }

        private string AddFallbackLoad(string texIdent, string loc)
        {
            usedSupportHelpers.Add("tex_load");
            return $"lfx_ch_load({texIdent}, {loc})";
        }

        private string TranslateTextureGather(string texIdent, string method, List<string> args, string? samplerType)
        {
            env.MarkGatherUsed();
            int ch = method == "GatherGreen" ? 1 : method == "GatherBlue" ? 2 : method == "GatherAlpha" ? 3 : 0;
            bool isCube = samplerType is "samplerCube" or "samplerCubeArray";
            if (samplerType == "sampler3D")
                throw new ConversionException($"Gather is unsupported for 3D texture {texIdent}");
            return args.Count switch {
                2 => $"textureGather({texIdent}, {args[1]}, {ch})",
                3 when !isCube => $"textureGatherOffset({texIdent}, {args[1]}, {args[2]}, {ch})",
                6 when !isCube => $"textureGatherOffsets({texIdent}, {args[1]}, ivec2[4]({args[2]}, {args[3]}, {args[4]}, {args[5]}), {ch})",
                _ => throw new ConversionException($"Unsupported {method} overload ({args.Count} args) on {texIdent} ({samplerType ?? "unknown"})")
            };
        }

        private string TranslateGetDimensions(string texIdent, List<string> args, string? samplerType)
        {
            switch (samplerType) {
                case "sampler2D" or "samplerCube":
                    if (args.Count == 2)
                        return $"{{ ivec2 _lfx_dim = textureSize({texIdent}, 0); {args[0]} = _lfx_dim.x; {args[1]} = _lfx_dim.y; }}";
                    if (args.Count == 4) {
                        env.MarkQueryLevelsUsed();
                        return $"{{ ivec2 _lfx_dim = textureSize({texIdent}, int({args[0]})); {args[1]} = _lfx_dim.x; {args[2]} = _lfx_dim.y; {args[3]} = textureQueryLevels({texIdent}); }}";
                    }
                    break;
                case "sampler2DArray" or "samplerCubeArray" or "sampler3D":
                    if (args.Count == 3)
                        return $"{{ ivec3 _lfx_dim = textureSize({texIdent}, 0); {args[0]} = _lfx_dim.x; {args[1]} = _lfx_dim.y; {args[2]} = _lfx_dim.z; }}";
                    if (args.Count == 5) {
                        env.MarkQueryLevelsUsed();
                        return $"{{ ivec3 _lfx_dim = textureSize({texIdent}, int({args[0]})); {args[1]} = _lfx_dim.x; {args[2]} = _lfx_dim.y; {args[3]} = _lfx_dim.z; {args[4]} = textureQueryLevels({texIdent}); }}";
                    }
                    break;
            }
            throw new ConversionException($"Unsupported GetDimensions overload with {args.Count} arguments on {texIdent} ({samplerType ?? "unknown"})");
        }

        private string? ResolveSamplerType(string ident, Dictionary<string, string> localVarTypes)
        {
            if (inputByName.TryGetValue(ident, out PinParameter? pin) && (pin.IsSampler || pin.IsVirtualTexture))
                return pin.SamplerType ?? "sampler2D";
            if (localVarTypes.TryGetValue(ident, out string? typeName) && typeName.StartsWith("sampler", StringComparison.Ordinal))
                return typeName;
            DeclGlobal? global = allGlobals.FirstOrDefault(g => g.Name == ident);
            if (global is not null && global.Type.StartsWith("sampler", StringComparison.Ordinal))
                return global.Type;
            return null;
        }

        private bool IsTextureObjectOrSamplerParam(string ident, Dictionary<string, string> localVarTypes)
            => ResolveSamplerType(ident, localVarTypes) is not null;

        private string RewriteMulCall(List<Tok> tokA, List<Tok> tokB, Dictionary<string, string> localVarTypes)
        {
            string a = RenderTokens(tokA).Trim();
            string b = RenderTokens(tokB).Trim();
            ExprType typeA = InferGlslExprType(tokA, localVarTypes);
            ExprType typeB = InferGlslExprType(tokB, localVarTypes);
            if (typeA.IsVector && typeB.IsVector) {
                if (typeA.Prefix == 'i' || typeB.Prefix == 'i') {
                    usedSupportHelpers.Add("int_dot");
                    return $"lfx_ch_dot(({a}), ({b}))";
                }
                if (typeA.Prefix == 'u' || typeB.Prefix == 'u') {
                    usedSupportHelpers.Add("uint_dot");
                    return $"lfx_ch_udot(({a}), ({b}))";
                }
                return $"dot(({a}), ({b}))";
            }
            if (typeA.IsScalar || typeB.IsScalar)
                return $"(({a}) * ({b}))";
            return $"(({b}) * ({a}))";
        }

        // Matrices keep the HLSL rows in GLSL columns, including in constructors and row indexing.
        private readonly record struct ExprType(ExprKind Kind, int Width = 1, char Prefix = 'f', int MatrixRows = 0)
        {
            internal int MatrixColumns => Width;
            internal static ExprType Matrix(int rows, int columns) => new(ExprKind.Matrix, columns, 'f', rows);
            internal bool IsScalar => Kind == ExprKind.Scalar;
            internal bool IsVector => Kind == ExprKind.Vector;
            internal bool IsMatrix => Kind == ExprKind.Matrix;
            internal static readonly ExprType Unknown = new(ExprKind.Unknown, 0, ' ');
        }

        private enum ExprKind { Unknown, Scalar, Vector, Matrix }

        private static bool IsControlHeaderClose(List<Tok> tokens, int closeParenIdx)
        {
            int balance = 1;
            int idx = closeParenIdx - 1;
            while (idx >= 0 && balance > 0) {
                if (tokens[idx].Text == ")") balance++;
                else if (tokens[idx].Text == "(") balance--;
                idx--;
            }
            while (idx >= 0 && tokens[idx].Kind == TokKind.Newline) idx--;
            return idx >= 0 && tokens[idx].Kind == TokKind.Ident
                && tokens[idx].Text is "if" or "for" or "while";
        }

        private static bool IsForHeaderOpen(List<Tok> tokens, int openParenIdx)
        {
            int idx = openParenIdx - 1;
            while (idx >= 0 && tokens[idx].Kind == TokKind.Newline) idx--;
            return idx >= 0 && tokens[idx].Kind == TokKind.Ident && tokens[idx].Text == "for";
        }

        private static string ArrayRankSuffix(string arraySuffix)
        {
            if (string.IsNullOrEmpty(arraySuffix)) return "";
            int depth = 0, dims = 0;
            foreach (char c in arraySuffix) {
                if (c == '[') {
                    if (depth == 0) dims++;
                    depth++;
                } else if (c == ']') {
                    if (depth > 0) depth--;
                }
            }
            return dims > 0 ? string.Concat(Enumerable.Repeat("[]", dims)) : "";
        }

        private static void RecordLocalVariableDeclarations(
            List<Tok> tokens,
            int startIdx,
            string glslType,
            string rawHlslType,
            bool isConstDecl,
            Dictionary<string, string> localVarTypes,
            Dictionary<string, (int Rows, int Cols)> localDegenerates,
            Dictionary<string, string> localConstInits,
            Func<List<Tok>, string> renderInit)
        {
            int idx = NextNonNewline(tokens, startIdx);
            if (idx >= tokens.Count || tokens[idx].Kind != TokKind.Ident) return;
            int afterFirst = NextNonNewline(tokens, idx + 1);
            // Exclude function definitions/declarations `Type Name(...)`
            if (afterFirst < tokens.Count && tokens[afterFirst].Text == "(") return;

            bool isDegenerate = TryParseDegenerateMatrixType(rawHlslType, out int degRows, out int degCols);
            int depth = 0;
            bool expectVar = true;
            string? currentVar = null;
            int initStart = -1;
            for (int cursor = idx; cursor < tokens.Count; cursor++) {
                Tok cur = tokens[cursor];
                if (cur.Kind == TokKind.Newline) continue;
                if (cur.Text is "(" or "[" or "{") { depth++; expectVar = false; continue; }
                if (cur.Text is ")" or "]" or "}") {
                    if (depth == 0) {
                        if (isConstDecl && currentVar is not null && initStart >= 0 && cursor > initStart)
                            RecordConstInit(currentVar, tokens.GetRange(initStart, cursor - initStart), localConstInits, renderInit);
                        break;
                    }
                    depth--;
                    continue;
                }
                if (depth > 0) continue;
                if (cur.Text == ";") {
                    if (isConstDecl && currentVar is not null && initStart >= 0 && cursor > initStart)
                        RecordConstInit(currentVar, tokens.GetRange(initStart, cursor - initStart), localConstInits, renderInit);
                    break;
                }
                if (cur.Text == ",") {
                    if (isConstDecl && currentVar is not null && initStart >= 0 && cursor > initStart)
                        RecordConstInit(currentVar, tokens.GetRange(initStart, cursor - initStart), localConstInits, renderInit);
                    currentVar = null;
                    initStart = -1;
                    expectVar = true;
                    continue;
                }
                if (cur.Text == "=") {
                    expectVar = false;
                    initStart = cursor + 1;
                    continue;
                }
                if (expectVar && cur.Kind == TokKind.Ident) {
                    int dims = 0;
                    int dimCursor = NextNonNewline(tokens, cursor + 1);
                    while (dimCursor < tokens.Count && tokens[dimCursor].Text == "[") {
                        int closeBr = FindMatching(tokens, dimCursor, "[", "]");
                        if (closeBr <= dimCursor) break;
                        dims++;
                        dimCursor = NextNonNewline(tokens, closeBr + 1);
                    }
                    currentVar = cur.Text;
                    localVarTypes[cur.Text] = dims > 0 ? glslType + string.Concat(Enumerable.Repeat("[]", dims)) : glslType;
                    if (dims == 0 && isDegenerate) localDegenerates[cur.Text] = (degRows, degCols);
                    else localDegenerates.Remove(cur.Text);
                    if (!isConstDecl) localConstInits.Remove(cur.Text);
                    expectVar = false;
                }
            }
        }

        private static void RecordConstInit(string name, List<Tok> rawInit, Dictionary<string, string> localConstInits,
            Func<List<Tok>, string> renderInit)
        {
            string rendered = renderInit(rawInit);
            if (!string.IsNullOrEmpty(rendered)) {
                string inlined = InlineConstIdentifiers(rendered, localConstInits);
                localConstInits[name] = $"({inlined})";
            }
        }

        private ExprType InferGlslExprType(List<Tok> rawTokens, Dictionary<string, string> localVarTypes)
        {
            List<Tok> tokens = rawTokens.Where(t => t.Kind != TokKind.Newline).ToList();
            if (tokens.Count == 0) return ExprType.Unknown;
            // Re-tokenize compound Ident tokens (produced during pass1) so member accesses and calls inside them parse accurately
            List<Tok> expanded = new(tokens.Count);
            foreach (Tok tok in tokens) {
                if (tok.Kind == TokKind.Ident && tok.Text.IndexOfAny(new[] { '(', '.', '*', '+', '-', '[', ' ' }) >= 0)
                    expanded.AddRange(Tokenize(tok.Text).Where(k => k.Kind != TokKind.Newline));
                else
                    expanded.Add(tok);
            }
            return InferExpandedSlice(expanded, 0, expanded.Count, localVarTypes);
        }

        private ExprType InferExpandedSlice(List<Tok> toks, int start, int end, Dictionary<string, string> localVarTypes)
        {
            while (start < end && toks[start].Text == "(" && FindMatching(toks, start, "(", ")") == end - 1) {
                start++;
                end--;
            }
            if (start >= end) return ExprType.Unknown;

            // Ternary ?: at top level
            int depth = 0;
            for (int i = start; i < end; i++) {
                string txt = toks[i].Text;
                if (txt is "(" or "[") depth++;
                else if (txt is ")" or "]") depth = Math.Max(0, depth - 1);
                else if (depth == 0 && txt == "?") {
                    for (int j = i + 1, qd = 0; j < end; j++) {
                        string jt = toks[j].Text;
                        if (jt is "(" or "[") qd++;
                        else if (jt is ")" or "]") qd = Math.Max(0, qd - 1);
                        else if (qd == 0 && jt == ":") {
                            ExprType tBranch = InferExpandedSlice(toks, i + 1, j, localVarTypes);
                            return tBranch.Kind != ExprKind.Unknown ? tBranch : InferExpandedSlice(toks, j + 1, end, localVarTypes);
                        }
                    }
                }
            }

            // Binary operators (+, -, *, /) at lowest precedence outside parens
            for (int prec = 0; prec < 2; prec++) {
                depth = 0;
                for (int i = end - 1; i > start; i--) {
                    string txt = toks[i].Text;
                    if (txt is ")" or "]") depth++;
                    else if (txt is "(" or "[") depth = Math.Max(0, depth - 1);
                    else if (depth == 0 && ((prec == 0 && txt is "+" or "-") || (prec == 1 && txt is "*" or "/" or "%"))) {
                        string prevTxt = toks[i - 1].Text;
                        if (prevTxt is "(" or "," or "=" or "+" or "-" or "*" or "/" or "%") continue;
                        ExprType left = InferExpandedSlice(toks, start, i, localVarTypes);
                        ExprType right = InferExpandedSlice(toks, i + 1, end, localVarTypes);
                        if (left.Kind == ExprKind.Unknown || right.Kind == ExprKind.Unknown) return ExprType.Unknown;
                        if (left.IsScalar) return right;
                        if (right.IsScalar) return left;
                        if (txt == "*" && (left.IsMatrix || right.IsMatrix)) {
                            // Infer an already lowered GLSL product. The HLSL operands were reversed.
                            return InferHlslMulType(right, left);
                        }
                        return left;
                    }
                }
            }

            if (toks[start].Text is "+" or "-" or "!" or "~" && end - start > 1)
                return InferExpandedSlice(toks, start + 1, end, localVarTypes);

            if (toks[start].Text == "(" && TryReadPrimitiveCast(toks, start, out ExprType castType, out int castStart)
                    && castStart < end)
                return castType;

            // Walk postfix chain: primary followed by .member / [idx] / (...)
            ExprType current = ExprType.Unknown;
            string currentTypeName = "";
            int cursor = start;
            if (toks[cursor].Kind == TokKind.Number || toks[cursor].Text is "true" or "false") {
                current = new ExprType(ExprKind.Scalar, 1, toks[cursor].Kind == TokKind.Number && toks[cursor].Text.EndsWith("u", StringComparison.OrdinalIgnoreCase) ? 'u' : 'f');
                cursor++;
            } else if (toks[cursor].Kind == TokKind.Ident) {
                string ident = toks[cursor].Text;
                if (cursor + 1 < end && toks[cursor + 1].Text == "(") {
                    int close = FindMatching(toks, cursor + 1, "(", ")");
                    if (close > cursor + 1) {
                        currentTypeName = ResolveCallReturnGlslType(ident, toks.GetRange(cursor + 2, close - cursor - 2), localVarTypes);
                        current = FromGlslTypeName(currentTypeName);
                        cursor = close + 1;
                    }
                } else {
                    currentTypeName = ResolveIdentGlslType(ident, localVarTypes);
                    current = FromGlslTypeName(currentTypeName);
                    cursor++;
                }
            } else if (toks[cursor].Text == "(") {
                int close = FindMatching(toks, cursor, "(", ")");
                if (close > cursor) {
                    current = InferExpandedSlice(toks, cursor + 1, close, localVarTypes);
                    currentTypeName = FormatExprGlslType(current);
                    cursor = close + 1;
                }
            }

            while (cursor < end) {
                if (toks[cursor].Text == "." && cursor + 1 < end && toks[cursor + 1].Kind == TokKind.Ident) {
                    string member = toks[cursor + 1].Text;
                    cursor += 2;
                    if (current.IsMatrix && IsMatrixSelector(member)) {
                        currentTypeName = MatrixSelectionType(ReadMatrixMembers(current, member).Length);
                        current = FromGlslTypeName(currentTypeName);
                    } else if ((current.IsScalar || current.IsVector) && IsMatrixSelector(member)
                            && TryReadLinearMatrixSelector(current, member, out string linearSwizzle)) {
                        current = linearSwizzle.Length == 1
                            ? new ExprType(ExprKind.Scalar, 1, current.Prefix)
                            : new ExprType(ExprKind.Vector, linearSwizzle.Length, current.Prefix);
                        currentTypeName = FormatExprGlslType(current);
                    } else if (current.IsVector && IsComponentSwizzle(member)) {
                        current = member.Length == 1
                            ? new ExprType(ExprKind.Scalar, 1, current.Prefix)
                            : new ExprType(ExprKind.Vector, member.Length, current.Prefix);
                        currentTypeName = FormatExprGlslType(current);
                    } else if (!string.IsNullOrEmpty(currentTypeName)) {
                        currentTypeName = ResolveMemberGlslType(currentTypeName, member);
                        current = FromGlslTypeName(currentTypeName);
                    } else {
                        current = ExprType.Unknown;
                    }
                } else if (toks[cursor].Text == "[") {
                    int closeBr = FindMatching(toks, cursor, "[", "]");
                    if (closeBr <= cursor) break;
                    cursor = closeBr + 1;
                    if (currentTypeName == "FMaterialPixelParameters_TexCoords" || currentTypeName.EndsWith("[]", StringComparison.Ordinal)) {
                        string elemType = currentTypeName == "FMaterialPixelParameters_TexCoords" ? "vec2" : currentTypeName[..^2];
                        currentTypeName = elemType;
                        current = elemType.EndsWith("[]", StringComparison.Ordinal) ? ExprType.Unknown : FromGlslTypeName(elemType);
                    } else if (current.IsMatrix) {
                        current = new ExprType(ExprKind.Vector, current.MatrixColumns);
                        currentTypeName = FormatExprGlslType(current);
                    } else if (current.IsVector) {
                        current = new ExprType(ExprKind.Scalar, 1, current.Prefix);
                        currentTypeName = current.Prefix switch { 'i' => "int", 'u' => "uint", 'b' => "bool", _ => "float" };
                    }
                } else {
                    break;
                }
            }
            return cursor == end ? current : ExprType.Unknown;
        }

        private string ResolveCallReturnGlslType(string fnIdent, List<Tok> argToks, Dictionary<string, string> localVarTypes)
        {
            string mappedFn = MapTypeToken(fnIdent);
            if (GlslBuiltinTypes.Contains(mappedFn)) return mappedFn;
            if (mappedFn is "float" or "int" or "uint" or "bool" or "double") return mappedFn;
            if (fnIdent is "dot" or "length" or "distance" or "determinant" or "lfx_ch_luminance" or "Luminance") return "float";
            if (fnIdent is "lfx_ch_dot") return "int";
            if (fnIdent is "lfx_ch_udot") return "uint";
            if (fnIdent is "cross" or "lfx_material_world_position") return "vec3";
            if (fnIdent is "texture" or "textureLod" or "textureGrad" or "textureOffset" or "textureLodOffset" or "textureGradOffset"
                or "texelFetch" or "texelFetchOffset" or "textureGather" or "textureGatherOffset" or "textureGatherOffsets" or "lfx_ch_load"
                || fnIdent.StartsWith(prefix + "load_off_", StringComparison.Ordinal)
                || fnIdent.StartsWith("lfx_ch_load_off_", StringComparison.Ordinal))
                return "vec4";
            if (fnIdent.EndsWith("read_view", StringComparison.Ordinal)) return $"{prefix}ViewType";
            if (fnIdent.EndsWith("GetWorldPosition", StringComparison.Ordinal)) return "LfxLWC3";
            if (fnIdent is "lfx_lwc_demote") return "vec3";
            if (fnIdent is "mul") {
                List<List<Tok>> args = SplitByTopLevelComma(argToks);
                if (args.Count == 2) {
                    ExprType ta = InferGlslExprType(args[0], localVarTypes);
                    ExprType tb = InferGlslExprType(args[1], localVarTypes);
                    return FormatExprGlslType(InferHlslMulType(ta, tb));
                }
            }
            if (fnIdent is "transpose" or "inverse" or "matrixCompMult") {
                List<List<Tok>> args = SplitByTopLevelComma(argToks);
                if (args.Count == 0) return "";
                ExprType t = InferGlslExprType(args[0], localVarTypes);
                if (t.IsMatrix)
                    return FormatExprGlslType(fnIdent == "transpose" ? ExprType.Matrix(t.MatrixColumns, t.MatrixRows) : t);
                if (fnIdent == "transpose" && (t.IsScalar || t.IsVector))
                    return FormatExprGlslType(t);
                return "";
            }
            if (fnIdent == "outerProduct") {
                List<List<Tok>> args = SplitByTopLevelComma(argToks);
                if (args.Count == 2) {
                    ExprType col = InferGlslExprType(args[0], localVarTypes);
                    ExprType row = InferGlslExprType(args[1], localVarTypes);
                    if (col.IsVector && row.IsVector)
                        return FormatExprGlslType(ExprType.Matrix(row.Width, col.Width));
                }
            }
            if (matrixHelpers.TryGetValue(fnIdent, out var matrixHelper)) return matrixHelper.ReturnType;
            if (matrixArgumentWrappers.TryGetValue(fnIdent, out var argumentWrapper)) return argumentWrapper.ReturnType;
            foreach (var matrix in matrixSplats) {
                if (fnIdent == MatrixSplatName(matrix)) return FormatExprGlslType(matrix);
            }
            if (fnIdent is "lfx_ch_select" or "select") {
                List<List<Tok>> args = SplitByTopLevelComma(argToks);
                for (int k = 1; k < args.Count; k++) {
                    ExprType tk = InferGlslExprType(args[k], localVarTypes);
                    if (tk.IsVector) return FormatExprGlslType(tk);
                    if (tk.IsScalar) return FormatExprGlslType(tk);
                }
            }
            if (fnIdent is "lfx_ch_asfloat" or "asfloat" or "lfx_ch_asuint" or "asuint" or "lfx_ch_asint" or "asint") {
                char outPrefix = fnIdent.EndsWith("asuint", StringComparison.Ordinal) ? 'u'
                    : fnIdent.EndsWith("asint", StringComparison.Ordinal) ? 'i' : 'f';
                List<List<Tok>> args = SplitByTopLevelComma(argToks);
                if (args.Count > 0) {
                    ExprType t0 = InferGlslExprType(args[0], localVarTypes);
                    if (t0.IsVector) return FormatExprGlslType(new ExprType(ExprKind.Vector, t0.Width, outPrefix));
                    if (t0.IsScalar) return FormatExprGlslType(new ExprType(ExprKind.Scalar, 1, outPrefix));
                }
            }
            if (fnIdent is "normalize" or "abs" or "floor" or "ceil" or "fract" or "frac" or "round" or "trunc"
                or "sin" or "cos" or "tan" or "asin" or "acos" or "atan" or "atan2" or "sinh" or "cosh" or "tanh"
                or "asinh" or "acosh" or "atanh" or "sqrt" or "inversesqrt" or "rsqrt" or "exp" or "exp2" or "log" or "log2"
                or "dFdx" or "dFdy" or "fwidth" or "ddx" or "ddx_coarse" or "ddx_fine" or "ddy" or "ddy_coarse" or "ddy_fine"
                or "min" or "max" or "clamp" or "mix" or "lerp" or "step" or "smoothstep" or "pow" or "mod" or "fmod" or "sign"
                or "saturate" or "rcp" or "mad" or "Square" or "Pow2" or "Pow3" or "Pow4" or "Pow5" or "PositiveClampedPow"
                or "frexp" or "lfx_ch_frexp" or "modf"
                or "lfx_ch_saturate" or "lfx_ch_fmod" or "lfx_ch_smoothstep" or "lfx_ch_rcp" or "lfx_ch_pow"
                or "lfx_ch_positive_pow" or "lfx_ch_pow2" or "lfx_ch_pow3" or "lfx_ch_pow4" or "lfx_ch_pow5" or "lfx_ch_mad") {
                List<List<Tok>> args = SplitByTopLevelComma(argToks);
                ExprType scalarFallback = ExprType.Unknown;
                foreach (var a in args) {
                    ExprType ta = InferGlslExprType(a, localVarTypes);
                    if (ta.IsVector) return FormatExprGlslType(ta);
                    if (scalarFallback.Kind == ExprKind.Unknown && ta.IsScalar) scalarFallback = ta;
                }
                if (scalarFallback.IsScalar) return FormatExprGlslType(scalarFallback);
            }
            DeclFunction? userFn = allFunctions.FirstOrDefault(f => prefix + f.Name == fnIdent || f.Name == fnIdent);
            if (userFn is not null) return MapTypeToken(userFn.ReturnType);
            return "";
        }

        private static ExprType InferHlslMulType(ExprType a, ExprType b)
        {
            if (a.IsScalar) return b;
            if (b.IsScalar) return a;
            if (a.IsVector && b.IsVector && a.Width == b.Width)
                return new ExprType(ExprKind.Scalar, 1, a.Prefix);
            if (a.IsVector && b.IsMatrix && a.Width == b.MatrixRows)
                return new ExprType(ExprKind.Vector, b.MatrixColumns);
            if (a.IsMatrix && b.IsVector && a.MatrixColumns == b.Width)
                return new ExprType(ExprKind.Vector, a.MatrixRows);
            if (a.IsMatrix && b.IsMatrix && a.MatrixColumns == b.MatrixRows)
                return ExprType.Matrix(a.MatrixRows, b.MatrixColumns);
            return ExprType.Unknown;
        }

        private static string FormatExprGlslType(ExprType t) => t.Kind switch {
            ExprKind.Scalar => t.Prefix switch { 'i' => "int", 'u' => "uint", 'b' => "bool", _ => "float" },
            ExprKind.Vector => t.Prefix switch { 'i' => $"ivec{t.Width}", 'u' => $"uvec{t.Width}", 'b' => $"bvec{t.Width}", _ => $"vec{t.Width}" },
            ExprKind.Matrix => t.MatrixRows == t.MatrixColumns ? $"mat{t.MatrixRows}" : $"mat{t.MatrixRows}x{t.MatrixColumns}",
            _ => ""
        };

        private string ResolveIdentGlslType(string ident, Dictionary<string, string> localVarTypes)
        {
            if (localVarTypes.TryGetValue(ident, out string? localType)) return localType;
            if (ident == "Parameters") return "Parameters";
            if (ident is "effectPosition" or "lfxVertexMaterialPosition" or "lfxLightFunctionTranslatedWorld") return "vec3";
            if (ident is "effectColor" or "effectVertexColor" or "effectCustom" or "gl_FragCoord") return "vec4";
            if (ident is "effectUV" or "effectUV1") return "vec2";
            if (inputByName.TryGetValue(ident, out PinParameter? pin)) {
                if (pin.IsSampler) return pin.SamplerType ?? "sampler2D";
                return GlslTypeName(pin.Kind);
            }
            DeclGlobal? gl = allGlobals.FirstOrDefault(g => prefix + g.Name == ident || g.Name == ident);
            if (gl is null) return "";
            string glType = MapTypeToken(gl.Type);
            string arrRank = ArrayRankSuffix(gl.ArraySuffix);
            return arrRank.Length == 0 ? glType : glType + arrRank;
        }

        private string ResolveMemberGlslType(string ownerType, string member)
        {
            if (ownerType == $"{prefix}ViewType" && resolvedViewFields.TryGetValue(member, out var viewInfo))
                return viewInfo.GlslType;
            if (ownerType is "Parameters" or "FMaterialPixelParameters" or "FMaterialVertexParameters"
                || ownerType.EndsWith("_Params", StringComparison.Ordinal)) {
                if (member == "TexCoords") return "vec2[]";
                if (member == "Particle") return "Parameters_Particle";
                return env.ResolveParameterField(member).GlslType;
            }
            if (ownerType == "Parameters_Particle")
                return env.ResolveParameterField("Particle." + member).GlslType;
            DeclStruct? st = allStructs.FirstOrDefault(s => prefix + s.Name == ownerType || s.Name == ownerType);
            if (st is not null) {
                var field = st.Fields.FirstOrDefault(f => f.Name == member);
                if (!string.IsNullOrEmpty(field.Type)) {
                    string ft = MapTypeToken(field.Type);
                    string arrRank = ArrayRankSuffix(field.ArraySuffix);
                    return arrRank.Length == 0 ? ft : ft + arrRank;
                }
            }
            return "";
        }

        private static bool IsComponentSwizzle(string member)
            => member.Length is >= 1 and <= 4 && (member.All(c => c is 'x' or 'y' or 'z' or 'w') || member.All(c => c is 'r' or 'g' or 'b' or 'a'));

        private static ExprType FromGlslTypeName(string typeName) => typeName switch {
            "float" or "double" or "MaterialFloat" => new ExprType(ExprKind.Scalar, 1, 'f'),
            "int" => new ExprType(ExprKind.Scalar, 1, 'i'),
            "uint" => new ExprType(ExprKind.Scalar, 1, 'u'),
            "bool" => new ExprType(ExprKind.Scalar, 1, 'b'),
            "vec2" => new ExprType(ExprKind.Vector, 2, 'f'),
            "vec3" => new ExprType(ExprKind.Vector, 3, 'f'),
            "vec4" => new ExprType(ExprKind.Vector, 4, 'f'),
            "ivec2" => new ExprType(ExprKind.Vector, 2, 'i'),
            "ivec3" => new ExprType(ExprKind.Vector, 3, 'i'),
            "ivec4" => new ExprType(ExprKind.Vector, 4, 'i'),
            "uvec2" => new ExprType(ExprKind.Vector, 2, 'u'),
            "uvec3" => new ExprType(ExprKind.Vector, 3, 'u'),
            "uvec4" => new ExprType(ExprKind.Vector, 4, 'u'),
            "bvec2" => new ExprType(ExprKind.Vector, 2, 'b'),
            "bvec3" => new ExprType(ExprKind.Vector, 3, 'b'),
            "bvec4" => new ExprType(ExprKind.Vector, 4, 'b'),
            "mat2" or "mat3" or "mat4" => ExprType.Matrix(typeName[3] - '0', typeName[3] - '0'),
            "mat2x3" or "mat3x2" or "mat2x4" or "mat4x2" or "mat3x4" or "mat4x3" =>
                ExprType.Matrix(typeName[3] - '0', typeName[5] - '0'),
            _ => ExprType.Unknown
        };

        private bool TryTranslateMaterialBuiltinCall(
            List<Tok> tokens,
            int idx,
            HashSet<string> renamedSymbols,
            HashSet<string> knownStructNames,
            Dictionary<string, HashSet<int>> helperSamplerIndices,
            string paramsStructName,
            Dictionary<string, string> localParamTypes,
            out string? glslExpr,
            out int consumedTo,
            Dictionary<string, string>? localConstInits = null)
        {
            glslExpr = null;
            consumedTo = idx;
            string word = tokens[idx].Text;
            if (renamedSymbols.Contains(word))
                return false;
            bool recognized = word is "EyeAdaptationLookup" or "EyeAdaptationInverseLookup"
                or "PostVolumeUserFlagTest" or "GetNaniteReplaceState" or "GetShadowReplaceState"
                or "GetGIReplaceState" or "GetReflectionCapturePassSwitchState"
                or "GetLightmassReplaceState" or "GetRayTracingQualitySwitch"
                or "GetPathTracingQualitySwitch";
            if (!recognized)
                return false;
            int op = NextNonNewline(tokens, idx + 1);
            if (op >= tokens.Count || tokens[op].Text != "(")
                return false;
            int cl = FindMatching(tokens, op, "(", ")");
            if (cl <= op)
                return false;
            List<List<Tok>> rawArgs = SplitByTopLevelComma(tokens.GetRange(op + 1, cl - op - 1));
            List<string> args = new(rawArgs.Count);
            foreach (var rawArg in rawArgs) {
                string rendered = RenderTokens(TransformTokenStream(rawArg, renamedSymbols, knownStructNames,
                    helperSamplerIndices, paramsStructName, localParamTypes, null, localConstInits)).Trim();
                if (!string.IsNullOrEmpty(rendered))
                    args.Add(rendered);
            }
            switch (word) {
                case "EyeAdaptationLookup" when args.Count == 0:
                    env.MarkEyeAdaptationUsed();
                    glslExpr = "lfx_eye_adaptation()";
                    break;
                case "EyeAdaptationInverseLookup" when args.Count is 1 or 2:
                    if (env.IsVertexStage)
                        throw new ConversionException("Custom material EyeAdaptationInverseLookup requires pixel shading");
                    env.MarkEyeAdaptationUsed();
                    glslExpr = args.Count == 1
                        ? $"lfx_eye_adaptation_inverse_scale({args[0]})"
                        : $"(({args[0]}) * lfx_eye_adaptation_inverse_scale({args[1]}))";
                    break;
                case "PostVolumeUserFlagTest" when args.Count == 1:
                    env.MarkPostVolumeFlagsUsed();
                    glslExpr = $"lfx_post_volume_flag_test({args[0]})";
                    break;
                case "GetNaniteReplaceState" when args.Count == 0:
                    env.MarkNaniteReplaceUsed();
                    glslExpr = "lfx_nanite_replace_state()";
                    break;
                case "GetShadowReplaceState" when args.Count == 0:
                    env.MarkShadowReplaceUsed();
                    glslExpr = "lfx_shadow_replace_state()";
                    break;
                case "GetGIReplaceState" when args.Count == 0:
                case "GetReflectionCapturePassSwitchState" when args.Count == 0:
                case "GetLightmassReplaceState" when args.Count == 0:
                case "GetRayTracingQualitySwitch" when args.Count == 0:
                case "GetPathTracingQualitySwitch" when args.Count == 0:
                    glslExpr = "false";
                    break;
                default:
                    return false;
            }
            consumedTo = cl;
            return true;
        }

        private bool TryTranslateIntrinsicOrType(
            List<Tok> tokens,
            int idx,
            HashSet<string> renamedSymbols,
            HashSet<string> knownStructNames,
            Dictionary<string, HashSet<int>> helperSamplerIndices,
            string paramsStructName,
            Dictionary<string, string> localParamTypes,
            out Tok? replaced,
            out int consumedTo,
            Dictionary<string, string>? localConstInits = null)
        {
            string word = tokens[idx].Text;
            consumedTo = idx;
            replaced = null;

            // Types & user symbol renaming (but keep pin parameter names in current Custom body untouched!)
            if (word is "FMaterialPixelParameters" or "FMaterialVertexParameters") {
                replaced = new Tok(TokKind.Ident, paramsStructName);
                return true;
            }
            if (ScalarVectorTypeMap.TryGetValue(word, out string? glslType)) {
                if (glslType.StartsWith("LfxLWC", StringComparison.Ordinal))
                    env.EnsureLargeWorldMath();
                replaced = new Tok(TokKind.Ident, glslType);
                return true;
            }
            if (!localParamTypes.ContainsKey(word) && renamedSymbols.Contains(word)) {
                replaced = new Tok(TokKind.Ident, prefix + word);
                return true;
            }

            if (word == "PI" && usesPiConstant && !renamedSymbols.Contains("PI")) {
                usedSupportHelpers.Add("pi");
                replaced = new Tok(TokKind.Ident, "LFX_CH_PI");
                return true;
            }

            // Function-call intrinsics
            int next = NextNonNewline(tokens, idx + 1);
            if (next >= tokens.Count || tokens[next].Text != "(")
                return false;

            switch (word) {
                case "lerp": replaced = new Tok(TokKind.Ident, "mix"); return true;
                case "frac": replaced = new Tok(TokKind.Ident, "fract"); return true;
                case "rsqrt": replaced = new Tok(TokKind.Ident, "inversesqrt"); return true;
                case "atan2": replaced = new Tok(TokKind.Ident, "atan"); return true;
                case "countbits": replaced = new Tok(TokKind.Ident, "bitCount"); return true;
                case "firstbithigh": replaced = new Tok(TokKind.Ident, "findMSB"); return true;
                case "firstbitlow": replaced = new Tok(TokKind.Ident, "findLSB"); return true;
                case "reversebits": replaced = new Tok(TokKind.Ident, "bitfieldReverse"); return true;
                case "ddx" or "ddx_coarse" or "ddx_fine":
                    if (env.IsVertexStage) throw new ConversionException($"Custom material {word}() requires pixel shading");
                    env.MarkDerivativesUsed();
                    replaced = new Tok(TokKind.Ident, "dFdx");
                    return true;
                case "ddy" or "ddy_coarse" or "ddy_fine":
                    if (env.IsVertexStage) throw new ConversionException($"Custom material {word}() requires pixel shading");
                    env.MarkDerivativesUsed();
                    replaced = new Tok(TokKind.Ident, "dFdy");
                    return true;
                case "fmod":
                    usedSupportHelpers.Add("fmod");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_fmod");
                    return true;
                case "smoothstep":
                    usedSupportHelpers.Add("smoothstep");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_smoothstep");
                    return true;
                case "saturate":
                    usedSupportHelpers.Add("saturate");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_saturate");
                    return true;
                case "rcp":
                    usedSupportHelpers.Add("rcp");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_rcp");
                    return true;
                case "pow":
                    usedSupportHelpers.Add("pow");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_pow");
                    return true;
                case "PositiveClampedPow":
                    usedSupportHelpers.Add("pos_pow");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_positive_pow");
                    return true;
                case "sincos":
                    usedSupportHelpers.Add("sincos");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_sincos");
                    return true;
                case "frexp":
                    usedSupportHelpers.Add("frexp");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_frexp");
                    return true;
                case "select":
                    usedSupportHelpers.Add("select");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_select");
                    return true;
                case "isfinite":
                    usedSupportHelpers.Add("isfinite");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_isfinite");
                    return true;
                case "asfloat":
                    usedSupportHelpers.Add("bitcast");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_asfloat");
                    return true;
                case "asuint":
                    usedSupportHelpers.Add("bitcast");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_asuint");
                    return true;
                case "asint":
                    usedSupportHelpers.Add("bitcast");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_asint");
                    return true;
                case "Square" or "Pow2" when !renamedSymbols.Contains(word):
                    usedSupportHelpers.Add("pow2345");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_pow2");
                    return true;
                case "Pow3" when !renamedSymbols.Contains(word):
                    usedSupportHelpers.Add("pow2345");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_pow3");
                    return true;
                case "Pow4" when !renamedSymbols.Contains(word):
                    usedSupportHelpers.Add("pow2345");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_pow4");
                    return true;
                case "Pow5" when !renamedSymbols.Contains(word):
                    usedSupportHelpers.Add("pow2345");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_pow5");
                    return true;
                case "Luminance" when !renamedSymbols.Contains(word):
                    usedSupportHelpers.Add("luminance");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_luminance");
                    return true;
                case "mad" when !renamedSymbols.Contains(word):
                    usedSupportHelpers.Add("mad");
                    replaced = new Tok(TokKind.Ident, "lfx_ch_mad");
                    return true;
                case "log10": {
                    int cl = FindMatching(tokens, next, "(", ")");
                    if (cl > next) {
                        List<Tok> arg = TransformTokenStream(tokens.GetRange(next + 1, cl - next - 1), renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localParamTypes, null, localConstInits);
                        replaced = new Tok(TokKind.Ident, $"(log2({RenderTokens(arg).Trim()}) * 0.3010299956639812)");
                        consumedTo = cl;
                        return true;
                    }
                    return false;
                }
                case "exp10": {
                    int cl = FindMatching(tokens, next, "(", ")");
                    if (cl > next) {
                        List<Tok> arg = TransformTokenStream(tokens.GetRange(next + 1, cl - next - 1), renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localParamTypes, null, localConstInits);
                        replaced = new Tok(TokKind.Ident, $"exp2(({RenderTokens(arg).Trim()}) * 3.321928094887362)");
                        consumedTo = cl;
                        return true;
                    }
                    return false;
                }
                case "DFDemote" or "LWCDemote" or "WSDemote":
                    env.EnsureLargeWorldMath(); replaced = new Tok(TokKind.Ident, "lfx_lwc_demote"); return true;
                case "DFPromote" or "LWCPromote" or "WSPromote":
                    env.EnsureLargeWorldMath(); replaced = new Tok(TokKind.Ident, "lfx_lwc_promote"); return true;
                case "DFAdd" or "LWCAdd" or "WSAdd":
                    env.EnsureLargeWorldMath(); replaced = new Tok(TokKind.Ident, "lfx_lwc_add"); return true;
                case "DFSubtract" or "LWCSubtract" or "WSSubtract":
                    env.EnsureLargeWorldMath(); replaced = new Tok(TokKind.Ident, "lfx_lwc_subtract"); return true;
                case "DFMultiply" or "LWCMultiply" or "WSMultiply":
                    env.EnsureLargeWorldMath(); replaced = new Tok(TokKind.Ident, "lfx_lwc_multiply"); return true;
                case "DFDivide" or "LWCDivide" or "WSDivide":
                    env.EnsureLargeWorldMath(); replaced = new Tok(TokKind.Ident, "lfx_lwc_divide"); return true;
                case "DFLerp" or "LWCLerp" or "WSLerp":
                    env.EnsureLargeWorldMath(); replaced = new Tok(TokKind.Ident, "lfx_lwc_lerp"); return true;
                case "DFDot" or "LWCDot" or "WSDot":
                    env.EnsureLargeWorldMath(); replaced = new Tok(TokKind.Ident, "lfx_lwc_dot"); return true;
            }
            return false;
        }

        private List<Tok> RewriteCStyleCasts(List<Tok> tokens, HashSet<string> knownStructNames)
        {
            List<Tok> output = new(tokens.Count);
            int i = 0;
            while (i < tokens.Count) {
                if (tokens[i].Text == "(") {
                    int typeIdx = NextNonNewline(tokens, i + 1);
                    if (typeIdx < tokens.Count && tokens[typeIdx].Kind == TokKind.Ident) {
                        string typeName = tokens[typeIdx].Text;
                        bool isKnownType = GlslBuiltinTypes.Contains(typeName) || knownStructNames.Contains(typeName);
                        if (isKnownType) {
                            int close = NextNonNewline(tokens, typeIdx + 1);
                            if (close < tokens.Count && tokens[close].Text == ")") {
                                int operandStart = NextNonNewline(tokens, close + 1);
                                if (operandStart < tokens.Count && IsCastOperandStart(tokens[operandStart])) {
                                    // Guard against macro/func param type or function ptr `(type);` or binary after `(type)`
                                    int operandEnd = ReadUnaryOperandEnd(tokens, operandStart);
                                    List<Tok> operandTokens = RewriteCStyleCasts(
                                        tokens.GetRange(operandStart, operandEnd - operandStart + 1), knownStructNames);
                                    string operandText = RenderTokens(operandTokens).Trim();
                                    if (knownStructNames.Contains(typeName) && IsZeroLiteral(operandText)) {
                                        if (typeName != "FMaterialAttributes")
                                            zeroHelperStructs.Add(typeName);
                                        output.Add(new Tok(TokKind.Ident, $"lfx_zero_{typeName}()"));
                                    } else {
                                        output.Add(new Tok(TokKind.Ident, $"{typeName}({operandText})"));
                                    }
                                    i = operandEnd + 1;
                                    continue;
                                }
                            }
                        }
                    }
                }
                output.Add(tokens[i]);
                i++;
            }
            return output;
        }

        private static bool IsCastOperandStart(Tok tok)
            => tok.Kind is TokKind.Ident or TokKind.Number
               || tok.Text is "(" or "-" or "+" or "!" or "~";

        private static int ReadUnaryOperandEnd(List<Tok> tokens, int idx)
        {
            int cur = idx;
            while (cur < tokens.Count && tokens[cur].Text is "-" or "+" or "!" or "~")
                cur = NextNonNewline(tokens, cur + 1);
            if (cur >= tokens.Count) return tokens.Count - 1;
            if (tokens[cur].Text == "(") {
                int cl = FindMatching(tokens, cur, "(", ")");
                if (cl >= cur) {
                    int typeIndex = NextNonNewline(tokens, cur + 1);
                    int after = NextNonNewline(tokens, cl + 1);
                    if (typeIndex < cl && tokens[typeIndex].Kind == TokKind.Ident
                            && NextNonNewline(tokens, typeIndex + 1) == cl
                            && GlslBuiltinTypes.Contains(MapTypeToken(tokens[typeIndex].Text))
                            && after < tokens.Count && IsCastOperandStart(tokens[after]))
                        return ReadUnaryOperandEnd(tokens, after);
                    cur = cl;
                }
            }
            while (true) {
                int next = NextNonNewline(tokens, cur + 1);
                if (next < tokens.Count && tokens[next].Text == "(") {
                    int cl = FindMatching(tokens, next, "(", ")");
                    if (cl >= next) { cur = cl; continue; }
                }
                if (next < tokens.Count && tokens[next].Text == "[") {
                    int cl = FindMatching(tokens, next, "[", "]");
                    if (cl >= next) { cur = cl; continue; }
                }
                if (next < tokens.Count && tokens[next].Text == ".") {
                    int fld = NextNonNewline(tokens, next + 1);
                    if (fld < tokens.Count && tokens[fld].Kind == TokKind.Ident) { cur = fld; continue; }
                }
                break;
            }
            return cur;
        }

        private List<Tok> RewriteStatementsAndInitializers(List<Tok> tokens, string? functionReturnType)
        {
            // Normalize:
            // 1. `return <scalarLiteral>;` in vec2/vec3/vec4 function -> `return vecN(<scalarLiteral>);`
            // 2. `Type var[N] = { ... };` -> `Type var[count] = Type[count](...);`
            // 3. `vecN var = <scalarLiteral>;` -> `vecN var = vecN(<scalarLiteral>);`
            List<Tok> output = new(tokens.Count);
            for (int i = 0; i < tokens.Count; i++) {
                Tok t = tokens[i];
                // Handle return literal inside vector/scalar functions
                if (functionReturnType is not null && t.Kind == TokKind.Ident && t.Text == "return") {
                    int semi = FindStatementEnd(tokens, i + 1);
                    if (semi > i) {
                        string expr = RenderTokens(tokens.GetRange(i + 1, semi - i - 1)).Trim();
                        string normalized = NormalizeTypedScalar(functionReturnType, expr);
                        output.Add(new Tok(TokKind.Ident, $"return {normalized};"));
                        i = semi;
                        continue;
                    }
                }

                // Handle Type Name[?] = { ... } or Type Name = scalarLiteral
                if (t.Kind == TokKind.Ident && !IsPrecedingDot(tokens, i)
                        && (GlslBuiltinTypes.Contains(t.Text) || IsKnownUserTypeToken(t.Text))) {
                    int nameIdx = NextNonNewline(tokens, i + 1);
                    if (nameIdx < tokens.Count && tokens[nameIdx].Kind == TokKind.Ident) {
                        int afterName = NextNonNewline(tokens, nameIdx + 1);
                        if (afterName < tokens.Count && tokens[afterName].Text == "[") {
                            int closeBr = FindMatching(tokens, afterName, "[", "]");
                            int eqIdx = closeBr > afterName ? NextNonNewline(tokens, closeBr + 1) : -1;
                            if (eqIdx >= 0 && eqIdx < tokens.Count && tokens[eqIdx].Text == "=") {
                                int braceIdx = NextNonNewline(tokens, eqIdx + 1);
                                if (braceIdx < tokens.Count && tokens[braceIdx].Text == "{") {
                                    int closeBrace = FindMatching(tokens, braceIdx, "{", "}");
                                    if (closeBrace > braceIdx) {
                                        string elemType = t.Text;
                                        string arrSuffix = RenderTokens(tokens.GetRange(afterName, closeBr - afterName + 1)).Replace(" ", "");
                                        string rawBrace = RenderTokens(tokens.GetRange(braceIdx, closeBrace - braceIdx + 1));
                                        (string glslCtor, int count) = RewriteBraceArrayInitializer(elemType, rawBrace);
                                        if (arrSuffix == "[]") arrSuffix = $"[{count}]";
                                        output.Add(new Tok(TokKind.Ident, $"{elemType} {tokens[nameIdx].Text}{arrSuffix} = {glslCtor}"));
                                        i = closeBrace;
                                        continue;
                                    }
                                }
                            }
                        } else if (afterName < tokens.Count && tokens[afterName].Text == "=" &&
                                (IsVectorType(t.Text) || FromGlslTypeName(t.Text).IsMatrix)) {
                            int stmtEnd = FindInitializerEnd(tokens, afterName + 1);
                            if (stmtEnd > afterName + 1) {
                                List<string> declParts = new();
                                string rhs0 = RenderTokens(tokens.GetRange(afterName + 1, stmtEnd - afterName - 1)).Trim();
                                string norm0 = NormalizeTypedInitializer(t.Text, "", rhs0);
                                declParts.Add($"{t.Text} {tokens[nameIdx].Text} = {norm0}");
                                int cursor = stmtEnd;
                                while (cursor < tokens.Count && tokens[cursor].Text == ",") {
                                    int nextVar = NextNonNewline(tokens, cursor + 1);
                                    if (nextVar >= tokens.Count || tokens[nextVar].Kind != TokKind.Ident) break;
                                    int nextEq = NextNonNewline(tokens, nextVar + 1);
                                    if (nextEq >= tokens.Count || tokens[nextEq].Text != "=") break;
                                    int nextEnd = FindInitializerEnd(tokens, nextEq + 1);
                                    if (nextEnd <= nextEq + 1) break;
                                    string rhsN = RenderTokens(tokens.GetRange(nextEq + 1, nextEnd - nextEq - 1)).Trim();
                                    string normN = NormalizeTypedInitializer(t.Text, "", rhsN);
                                    declParts.Add($"{tokens[nextVar].Text} = {normN}");
                                    cursor = nextEnd;
                                }
                                if (declParts.Count > 1 || norm0 != rhs0) {
                                    output.Add(new Tok(TokKind.Ident, string.Join(", ", declParts)));
                                    i = cursor - 1;
                                    continue;
                                }
                            }
                        }
                    }
                }

                output.Add(t);
            }
            return output;
        }

        private bool IsKnownUserTypeToken(string text)
            => text.StartsWith(prefix, StringComparison.Ordinal) && allStructs.Any(s => prefix + s.Name == text);

        private string TranslateExpressionTokens(
            List<Tok> exprTokens,
            HashSet<string> renamedSymbols,
            HashSet<string> knownStructNames,
            Dictionary<string, HashSet<int>> helperSamplerIndices,
            string paramsStructName,
            Dictionary<string, string> localParamTypes)
        {
            List<Tok> transformed = TransformTokenStream(exprTokens, renamedSymbols, knownStructNames, helperSamplerIndices, paramsStructName, localParamTypes, null);
            return RenderTokens(transformed).Trim();
        }

        private static (string GlslCtor, int ElementCount) RewriteBraceArrayInitializer(string elementType, string rawBrace)
        {
            string trimmed = rawBrace.Trim();
            if (!trimmed.StartsWith('{') || !trimmed.EndsWith('}'))
                return (rawBrace, 0);
            string inner = trimmed[1..^1].Trim();
            if (inner.Length == 0)
                return ($"{elementType}[0]()", 0);
            int count = SplitTopLevelCommasString(inner).Count;
            return ($"{elementType}[{count}]({inner})", count);
        }

        private static List<string> SplitTopLevelCommasString(string text)
        {
            List<string> items = new();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < text.Length; i++) {
                char c = text[i];
                if (c is '(' or '[' or '{') depth++;
                else if (c is ')' or ']' or '}') depth = Math.Max(0, depth - 1);
                else if (c == ',' && depth == 0) {
                    items.Add(text[start..i].Trim());
                    start = i + 1;
                }
            }
            string last = text[start..].Trim();
            if (last.Length > 0) items.Add(last);
            return items;
        }

        private string NormalizeTypedInitializer(string glslType, string arraySuffix, string rhs)
        {
            if (arraySuffix.Length != 0) return rhs;
            if (rhs.StartsWith('{') && rhs.EndsWith('}') && FromGlslTypeName(glslType).IsMatrix)
                return $"{glslType}({string.Join(", ", FlattenMatrixInitializer(rhs))})";
            return NormalizeTypedScalar(glslType, rhs);
        }

        private string NormalizeTypedScalar(string type, string expression)
        {
            if (!IsScalarNumericLiteral(expression)) return expression;
            if (IsVectorType(type)) return $"{type}({expression})";
            ExprType target = FromGlslTypeName(type);
            return target.IsMatrix ? SplatMatrix(target, expression) : expression;
        }

        private static bool IsVectorType(string t)
            => t is "vec2" or "vec3" or "vec4" or "ivec2" or "ivec3" or "ivec4"
                or "uvec2" or "uvec3" or "uvec4" or "bvec2" or "bvec3" or "bvec4";

        private static bool IsScalarNumericLiteral(string text)
            => Regex.IsMatch(text.Trim(), @"^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?[uU]?$");

        private static bool IsZeroLiteral(string text)
        {
            string trimmed = text.Trim();
            return trimmed is "0" or "0.0" or "0u" or "false" or ".0" or "0.";
        }

        private void EmitSupportHelpers(List<string> declarations, HashSet<string> zeroHelpers)
        {
            _ = zeroHelpers;
            EmitMatrixSplats(declarations);
            if (usedSupportHelpers.Contains("pi"))
                declarations.Add("const float LFX_CH_PI = 3.1415926535897932;");
            if (usedSupportHelpers.Contains("saturate")) {
                foreach (string t in new[] { "float", "vec2", "vec3", "vec4" })
                    declarations.Add($"{t} lfx_ch_saturate({t} v) {{ return clamp(v, 0.0, 1.0); }}");
            }
            if (usedSupportHelpers.Contains("rcp")) {
                foreach (string t in new[] { "float", "vec2", "vec3", "vec4" })
                    declarations.Add($"{t} lfx_ch_rcp({t} v) {{ return {t}(1.0) / v; }}");
            }
            if (usedSupportHelpers.Contains("fmod")) {
                foreach (string t in new[] { "float", "vec2", "vec3", "vec4" }) {
                    declarations.Add($"{t} lfx_ch_fmod({t} x, {t} y) {{ return x - y * trunc(x / y); }}");
                    if (t != "float")
                        declarations.Add($"{t} lfx_ch_fmod({t} x, float y) {{ return lfx_ch_fmod(x, {t}(y)); }}");
                }
            }
            if (usedSupportHelpers.Contains("smoothstep")) {
                foreach (string t in new[] { "float", "vec2", "vec3", "vec4" }) {
                    declarations.Add($"{t} lfx_ch_smoothstep({t} a, {t} b, {t} x) {{ {t} k = clamp((x - a) / (b - a), 0.0, 1.0); return k * k * (3.0 - 2.0 * k); }}");
                    if (t != "float")
                        declarations.Add($"{t} lfx_ch_smoothstep(float a, float b, {t} x) {{ return lfx_ch_smoothstep({t}(a), {t}(b), x); }}");
                }
            }
            if (usedSupportHelpers.Contains("pow")) {
                foreach (string t in new[] { "float", "vec2", "vec3", "vec4" }) {
                    declarations.Add($"{t} lfx_ch_pow({t} b, {t} e) {{ return pow(b, e); }}");
                    if (t != "float") {
                        declarations.Add($"{t} lfx_ch_pow({t} b, float e) {{ return pow(b, {t}(e)); }}");
                        declarations.Add($"{t} lfx_ch_pow(float b, {t} e) {{ return pow({t}(b), e); }}");
                    }
                }
            }
            if (usedSupportHelpers.Contains("pos_pow")) {
                declarations.Add("float lfx_ch_positive_pow(float b, float e) { return b <= 0.0 ? 0.0 : pow(max(b, 1e-20), e); }");
                foreach (string t in new[] { "vec2", "vec3", "vec4" }) {
                    declarations.Add($"{t} lfx_ch_positive_pow({t} b, {t} e) {{ return mix(pow(max(b, {t}(1e-20)), e), {t}(0.0), lessThanEqual(b, {t}(0.0))); }}");
                    declarations.Add($"{t} lfx_ch_positive_pow({t} b, float e) {{ return lfx_ch_positive_pow(b, {t}(e)); }}");
                }
            }
            if (usedSupportHelpers.Contains("sincos")) {
                foreach (string t in new[] { "float", "vec2", "vec3", "vec4" })
                    declarations.Add($"void lfx_ch_sincos({t} x, out {t} s, out {t} c) {{ s = sin(x); c = cos(x); }}");
            }
            if (usedSupportHelpers.Contains("frexp")) {
                foreach (var (f, iv) in new[] { ("float", "int"), ("vec2", "ivec2"), ("vec3", "ivec3"), ("vec4", "ivec4") }) {
                    declarations.Add($"{f} lfx_ch_frexp({f} x, out {f} e) {{ {iv} ie; {f} m = frexp(x, ie); e = {f}(ie); return m; }}");
                    declarations.Add($"{f} lfx_ch_frexp({f} x, out {iv} e) {{ return frexp(x, e); }}");
                }
            }
            if (usedSupportHelpers.Contains("select")) {
                foreach (var pair in new[] { ("bool", "float"), ("bvec2", "vec2"), ("bvec3", "vec3"), ("bvec4", "vec4") }) {
                    if (pair.Item1 == "bool") {
                        declarations.Add("float lfx_ch_select(bool c, float a, float b) { return c ? a : b; }");
                        declarations.Add("vec2 lfx_ch_select(bool c, vec2 a, vec2 b) { return c ? a : b; }");
                        declarations.Add("vec3 lfx_ch_select(bool c, vec3 a, vec3 b) { return c ? a : b; }");
                        declarations.Add("vec4 lfx_ch_select(bool c, vec4 a, vec4 b) { return c ? a : b; }");
                        declarations.Add("int lfx_ch_select(bool c, int a, int b) { return c ? a : b; }");
                        declarations.Add("uint lfx_ch_select(bool c, uint a, uint b) { return c ? a : b; }");
                    } else {
                        declarations.Add($"{pair.Item2} lfx_ch_select({pair.Item1} c, {pair.Item2} a, {pair.Item2} b) {{ return mix(b, a, c); }}");
                    }
                }
            }
            if (usedSupportHelpers.Contains("isfinite")) {
                declarations.Add("bool lfx_ch_isfinite(float v) { return !(isnan(v) || isinf(v)); }");
                for (int w = 2; w <= 4; w++) {
                    string vt = $"vec{w}", bt = $"bvec{w}";
                    string comp = string.Join(", ", Enumerable.Range(0, w).Select(idx => $"!(isnan(v[{idx}]) || isinf(v[{idx}]))"));
                    declarations.Add($"{bt} lfx_ch_isfinite({vt} v) {{ return {bt}({comp}); }}");
                }
            }
            if (usedSupportHelpers.Contains("bitcast")) {
                foreach (var (f, u, i) in new[] { ("float", "uint", "int"), ("vec2", "uvec2", "ivec2"), ("vec3", "uvec3", "ivec3"), ("vec4", "uvec4", "ivec4") }) {
                    declarations.Add($"{f} lfx_ch_asfloat({u} v) {{ return uintBitsToFloat(v); }}");
                    declarations.Add($"{f} lfx_ch_asfloat({i} v) {{ return intBitsToFloat(v); }}");
                    declarations.Add($"{f} lfx_ch_asfloat({f} v) {{ return v; }}");
                    declarations.Add($"{u} lfx_ch_asuint({f} v) {{ return floatBitsToUint(v); }}");
                    declarations.Add($"{u} lfx_ch_asuint({i} v) {{ return {u}(v); }}");
                    declarations.Add($"{u} lfx_ch_asuint({u} v) {{ return v; }}");
                    declarations.Add($"{i} lfx_ch_asint({f} v) {{ return floatBitsToInt(v); }}");
                    declarations.Add($"{i} lfx_ch_asint({u} v) {{ return {i}(v); }}");
                    declarations.Add($"{i} lfx_ch_asint({i} v) {{ return v; }}");
                }
            }
            if (usedSupportHelpers.Contains("pow2345")) {
                foreach (string t in new[] { "float", "vec2", "vec3", "vec4" }) {
                    declarations.Add($"{t} lfx_ch_pow2({t} x) {{ return x * x; }}");
                    declarations.Add($"{t} lfx_ch_pow3({t} x) {{ return x * x * x; }}");
                    declarations.Add($"{t} lfx_ch_pow4({t} x) {{ {t} x2 = x * x; return x2 * x2; }}");
                    declarations.Add($"{t} lfx_ch_pow5({t} x) {{ {t} x2 = x * x; return x2 * x2 * x; }}");
                }
            }
            if (usedSupportHelpers.Contains("luminance"))
                declarations.Add("float lfx_ch_luminance(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }");
            if (usedSupportHelpers.Contains("mad")) {
                foreach (var (s, vp) in new[] { ("float", "vec"), ("int", "ivec"), ("uint", "uvec") }) {
                    declarations.Add($"{s} lfx_ch_mad({s} a, {s} b, {s} c) {{ return a * b + c; }}");
                    for (int n = 2; n <= 4; n++) {
                        string v = vp + n;
                        declarations.Add($"{v} lfx_ch_mad({v} a, {v} b, {v} c) {{ return a * b + c; }}");
                        declarations.Add($"{v} lfx_ch_mad({v} a, {v} b, {s} c) {{ return a * b + c; }}");
                        declarations.Add($"{v} lfx_ch_mad({v} a, {s} b, {v} c) {{ return a * b + c; }}");
                        declarations.Add($"{v} lfx_ch_mad({v} a, {s} b, {s} c) {{ return a * b + c; }}");
                        declarations.Add($"{v} lfx_ch_mad({s} a, {v} b, {v} c) {{ return a * b + c; }}");
                        declarations.Add($"{v} lfx_ch_mad({s} a, {v} b, {s} c) {{ return a * b + c; }}");
                        declarations.Add($"{v} lfx_ch_mad({s} a, {s} b, {v} c) {{ return a * b + c; }}");
                    }
                }
            }
            if (usedSupportHelpers.Contains("clip")) {
                declarations.Add("bool lfx_ch_clip_neg(float v) { return v < 0.0; }");
                for (int w = 2; w <= 4; w++)
                    declarations.Add($"bool lfx_ch_clip_neg(vec{w} v) {{ return any(lessThan(v, vec{w}(0.0))); }}");
            }
            if (usedSupportHelpers.Contains("tex_load")) {
                declarations.Add("vec4 lfx_ch_load(sampler2D t, ivec3 c) { return texelFetch(t, c.xy, c.z); }");
                declarations.Add("vec4 lfx_ch_load(sampler2D t, ivec2 c) { return texelFetch(t, c, 0); }");
                declarations.Add("vec4 lfx_ch_load(sampler2DArray t, ivec4 c) { return texelFetch(t, c.xyz, c.w); }");
                declarations.Add("vec4 lfx_ch_load(sampler2DArray t, ivec3 c) { return texelFetch(t, c, 0); }");
                declarations.Add("vec4 lfx_ch_load(sampler3D t, ivec4 c) { return texelFetch(t, c.xyz, c.w); }");
                declarations.Add("vec4 lfx_ch_load(sampler3D t, ivec3 c) { return texelFetch(t, c, 0); }");
            }
            foreach (var entry in loadOffsetHelpers) {
                string samplerType = entry.Key.SamplerType;
                string off = entry.Key.Offset;
                string helperName = entry.Value;
                if (samplerType == "sampler2D") {
                    declarations.Add($"vec4 {helperName}(sampler2D t, ivec3 c) {{ return texelFetchOffset(t, c.xy, c.z, {off}); }}");
                    declarations.Add($"vec4 {helperName}(sampler2D t, ivec2 c) {{ return texelFetchOffset(t, c, 0, {off}); }}");
                } else {
                    declarations.Add($"vec4 {helperName}({samplerType} t, ivec4 c) {{ return texelFetchOffset(t, c.xyz, c.w, {off}); }}");
                    declarations.Add($"vec4 {helperName}({samplerType} t, ivec3 c) {{ return texelFetchOffset(t, c, 0, {off}); }}");
                }
            }
            if (usedSupportHelpers.Contains("int_dot")) {
                declarations.Add("int lfx_ch_dot(ivec2 a, ivec2 b) { return a.x * b.x + a.y * b.y; }");
                declarations.Add("int lfx_ch_dot(ivec3 a, ivec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }");
                declarations.Add("int lfx_ch_dot(ivec4 a, ivec4 b) { return a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w; }");
            }
            if (usedSupportHelpers.Contains("uint_dot")) {
                declarations.Add("uint lfx_ch_udot(uvec2 a, uvec2 b) { return a.x * b.x + a.y * b.y; }");
                declarations.Add("uint lfx_ch_udot(uvec3 a, uvec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }");
                declarations.Add("uint lfx_ch_udot(uvec4 a, uvec4 b) { return a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w; }");
            }
        }

        private List<DeclStruct> TopologicalStructs(List<DeclStruct> structs)
        {
            Dictionary<string, DeclStruct> map = structs.ToDictionary(s => s.Name, s => s, StringComparer.Ordinal);
            List<DeclStruct> result = new();
            HashSet<string> visited = new(StringComparer.Ordinal);
            void Visit(DeclStruct s) {
                if (!visited.Add(s.Name)) return;
                foreach (var f in s.Fields)
                    if (map.TryGetValue(f.Type, out DeclStruct? dep))
                        Visit(dep);
                result.Add(s);
            }
            foreach (DeclStruct s in structs) Visit(s);
            return result;
        }

        private string RenameSymbol(string name, HashSet<string> renamed)
            => renamed.Contains(name) ? prefix + name : name;

        private string RenameType(string typeName, HashSet<string> renamed)
            => typeName == "FMaterialAttributes" ? "FMaterialAttributes" : RenameSymbol(typeName, renamed);

        private static string MapTypeToken(string raw)
            => ScalarVectorTypeMap.TryGetValue(raw, out string? mapped) ? mapped : raw;

        private static string TranslateNumberLiteral(string lit)
        {
            if (lit.StartsWith("0x", StringComparison.OrdinalIgnoreCase) || lit.StartsWith("0b", StringComparison.OrdinalIgnoreCase))
                return lit.TrimEnd('l', 'L');
            if (lit.EndsWith("f", StringComparison.OrdinalIgnoreCase) || lit.EndsWith("h", StringComparison.OrdinalIgnoreCase)) {
                string stem = lit[..^1];
                if (!stem.Contains('.') && !stem.Contains('e') && !stem.Contains('E'))
                    return stem + ".0";
                return stem;
            }
            if (lit.EndsWith("l", StringComparison.OrdinalIgnoreCase))
                return lit.TrimEnd('l', 'L');
            return lit;
        }

        private List<Tok> StripHlslDecorators(List<Tok> tokens)
        {
            List<Tok> output = new(tokens.Count);
            for (int i = 0; i < tokens.Count; i++) {
                Tok t = tokens[i];
                if (t.Text == "[") {
                    int n1 = NextNonNewline(tokens, i + 1);
                    if (n1 < tokens.Count && tokens[n1].Text == "[") {
                        int c1 = FindMatching(tokens, n1, "[", "]");
                        int c2 = c1 >= 0 ? NextNonNewline(tokens, c1 + 1) : -1;
                        if (c2 >= 0 && c2 < tokens.Count && tokens[c2].Text == "]") {
                            i = c2;
                            continue;
                        }
                    }
                    // Strip standalone `[unroll]`, `[loop]`, `[branch]`, `[flatten]`, `[unroll(4)]` ONLY when NOT indexing an identifier
                    bool isIndexing = output.LastOrDefault(x => x.Kind != TokKind.Newline)?.Kind is TokKind.Ident or TokKind.Number
                                      || output.LastOrDefault(x => x.Kind != TokKind.Newline)?.Text is ")" or "]";
                    if (!isIndexing && n1 < tokens.Count && tokens[n1].Text is "unroll" or "loop" or "branch" or "flatten" or "fastopt" or "allow_uav_condition" or "WaveSize") {
                        int cl = FindMatching(tokens, i, "[", "]");
                        if (cl >= i) { i = cl; continue; }
                    }
                }
                if (t.Text == ":" && NextNonNewline(tokens, i + 1) is int rIdx && rIdx < tokens.Count
                        && tokens[rIdx].Text is "register" or "packoffset") {
                    int op = NextNonNewline(tokens, rIdx + 1);
                    int cl = op < tokens.Count && tokens[op].Text == "(" ? FindMatching(tokens, op, "(", ")") : rIdx;
                    i = cl >= 0 ? cl : rIdx;
                    continue;
                }
                if (t.Kind == TokKind.Ident && t.Text is "row_major" or "column_major" or "globallycoherent" or "groupshared" or "precise" or "nointerpolation" or "noperspective" or "centroid" or "snorm" or "unorm") {
                    if (t.Text == "precise" && env.ShaderVersion >= 400) {
                        output.Add(t);
                        continue;
                    }
                    continue;
                }
                if (t.Kind == TokKind.Ident && t.Text == "static") {
                    continue;
                }
                output.Add(t);
            }
            return output;
        }

        private static List<Tok> Tokenize(string code)
        {
            List<Tok> tokens = new();
            int i = 0;
            while (i < code.Length) {
                char c = code[i];
                if (c == '\n') {
                    tokens.Add(new Tok(TokKind.Newline, "\n"));
                    i++;
                    continue;
                }
                if (char.IsWhiteSpace(c)) {
                    i++;
                    continue;
                }
                if (c is '"' or '\'') {
                    int end = i + 1;
                    while (end < code.Length) {
                        if (code[end] == '\\' && end + 1 < code.Length) { end += 2; continue; }
                        if (code[end] == c) { end++; break; }
                        end++;
                    }
                    tokens.Add(new Tok(TokKind.String, code[i..end]));
                    i = end;
                    continue;
                }
                if ((c is >= 'a' and <= 'z') || (c is >= 'A' and <= 'Z') || c == '_') {
                    int start = i++;
                    while (i < code.Length && ((code[i] is >= 'a' and <= 'z') || (code[i] is >= 'A' and <= 'Z') || (code[i] is >= '0' and <= '9') || code[i] == '_')) i++;
                    tokens.Add(new Tok(TokKind.Ident, code[start..i]));
                    continue;
                }
                if (char.IsDigit(c) || (c == '.' && i + 1 < code.Length && char.IsDigit(code[i + 1]))) {
                    int start = i;
                    if (c == '0' && i + 1 < code.Length && (code[i + 1] is 'x' or 'X' or 'b' or 'B')) {
                        i += 2;
                        while (i < code.Length && (char.IsLetterOrDigit(code[i]) || code[i] == '_')) i++;
                    } else {
                        while (i < code.Length && char.IsDigit(code[i])) i++;
                        if (i < code.Length && code[i] == '.') {
                            i++;
                            while (i < code.Length && char.IsDigit(code[i])) i++;
                        }
                        if (i < code.Length && code[i] is 'e' or 'E') {
                            i++;
                            if (i < code.Length && code[i] is '+' or '-') i++;
                            while (i < code.Length && char.IsDigit(code[i])) i++;
                        }
                        while (i < code.Length && code[i] is 'f' or 'F' or 'h' or 'H' or 'u' or 'U' or 'l' or 'L') i++;
                    }
                    tokens.Add(new Tok(TokKind.Number, code[start..i]));
                    continue;
                }
                if (i + 1 < code.Length) {
                    string two = code.Substring(i, 2);
                    if (two is "==" or "!=" or "<=" or ">=" or "&&" or "||" or "++" or "--" or "+=" or "-=" or "*=" or "/=" or "%=" or "<< " or "<<" or ">>" or "&=" or "|=" or "^=") {
                        if (two == "<<" || two == ">>") {
                            if (i + 2 < code.Length && code[i + 2] == '=') {
                                tokens.Add(new Tok(TokKind.Symbol, code.Substring(i, 3)));
                                i += 3;
                                continue;
                            }
                        }
                        tokens.Add(new Tok(TokKind.Symbol, two.Trim()));
                        i += 2;
                        continue;
                    }
                }
                tokens.Add(new Tok(TokKind.Symbol, c.ToString()));
                i++;
            }
            return tokens;
        }

        private static string RenderTokens(List<Tok> tokens)
        {
            StringBuilder sb = new();
            Tok? prev = null;
            foreach (Tok t in tokens) {
                if (t.Kind == TokKind.Newline) {
                    if (sb.Length > 0 && sb[^1] != '\n') sb.Append('\n');
                    prev = t;
                    continue;
                }
                if (prev is not null && prev.Kind != TokKind.Newline) {
                    bool noSpaceBefore = t.Text is "," or ";" or ")" or "]" or "." or "++" or "--";
                    bool noSpaceAfter = prev.Text is "(" or "[" or "." or "++" or "--";
                    bool funcCallOpen = t.Text == "(" && prev.Kind == TokKind.Ident
                        && prev.Text is not "if" and not "for" and not "while" and not "switch" and not "return";
                    if (!noSpaceBefore && !noSpaceAfter && !funcCallOpen) {
                        if (t.Text == "[" && prev.Kind == TokKind.Ident) { /* attach subscript */ }
                        else sb.Append(' ');
                    }
                }
                sb.Append(t.Text);
                prev = t;
            }
            return sb.ToString().Trim();
        }

        private static bool UsesIdentifier(List<Tok> tokens, string ident)
            => tokens.Any(t => t.Kind == TokKind.Ident && t.Text == ident);

        private static bool IsPrecedingDot(List<Tok> tokens, int idx)
        {
            int p = idx - 1;
            while (p >= 0 && tokens[p].Kind == TokKind.Newline) p--;
            return p >= 0 && tokens[p].Text == ".";
        }

        private static int NextNonNewline(List<Tok> tokens, int start)
        {
            int i = start;
            while (i < tokens.Count && tokens[i].Kind == TokKind.Newline) i++;
            return i;
        }

        private static string? PeekNextNonNewline(List<Tok> tokens, int currentIdx)
        {
            int next = NextNonNewline(tokens, currentIdx + 1);
            return next < tokens.Count ? tokens[next].Text : null;
        }

        private static int FindMatching(List<Tok> tokens, int openIdx, string openSym, string closeSym)
        {
            int depth = 0;
            for (int i = openIdx; i < tokens.Count; i++) {
                if (tokens[i].Text == openSym) depth++;
                else if (tokens[i].Text == closeSym) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }

        private static int FindStatementEnd(List<Tok> tokens, int start)
        {
            int depth = 0;
            for (int i = start; i < tokens.Count; i++) {
                string s = tokens[i].Text;
                if (s is "(" or "[" or "{") depth++;
                else if (s is ")" or "]" or "}") depth = Math.Max(0, depth - 1);
                else if (depth == 0 && s == ";") return i;
            }
            return -1;
        }

        private static int FindInitializerEnd(List<Tok> tokens, int start)
        {
            int depth = 0;
            for (int i = start; i < tokens.Count; i++) {
                string s = tokens[i].Text;
                if (s is "(" or "[" or "{") depth++;
                else if (s is ")" or "]" or "}") depth = Math.Max(0, depth - 1);
                else if (depth == 0 && s is ";" or ",") return i;
            }
            return -1;
        }

        private static int SkipBlockOrStmt(List<Tok> tokens, int start)
        {
            for (int i = start; i < tokens.Count; i++) {
                if (tokens[i].Text == "{") {
                    int cl = FindMatching(tokens, i, "{", "}");
                    int semi = cl >= 0 ? NextNonNewline(tokens, cl + 1) : tokens.Count;
                    return (semi < tokens.Count && tokens[semi].Text == ";") ? semi + 1 : cl + 1;
                }
                if (tokens[i].Text == ";") return i + 1;
            }
            return tokens.Count;
        }

        private static List<List<Tok>> SplitByTopLevelComma(List<Tok> tokens)
        {
            List<List<Tok>> groups = new();
            List<Tok> cur = new();
            int depth = 0;
            foreach (Tok t in tokens) {
                if (t.Text is "(" or "[" or "{") depth++;
                else if (t.Text is ")" or "]" or "}") depth = Math.Max(0, depth - 1);
                else if (depth == 0 && t.Text == ",") {
                    groups.Add(cur);
                    cur = new List<Tok>();
                    continue;
                }
                cur.Add(t);
            }
            if (cur.Count > 0 || groups.Count > 0) groups.Add(cur);
            return groups;
        }

        private static string IndentBlock(string text)
        {
            string[] lines = text.Split('\n');
            return string.Join('\n', lines.Select(l => "    " + l));
        }
    }
}
