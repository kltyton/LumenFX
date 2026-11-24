using System.Globalization;
using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal.Attributes;
using LumenFx.Compiler.Unreal.Expressions;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record CustomOutputSlot(string Name, CustomHlslTranslator.OutputKind Kind, string VariableName);

    private sealed record CustomInvocation(
        Expr? MainExpr,
        CustomHlslTranslator.OutputKind MainKind,
        string MainVarName,
        IReadOnlyList<CustomOutputSlot> AdditionalOutputs);

    private static readonly Regex CustomTexCoordRegex = new(@"^TexCoords\[(\d+)\]$", RegexOptions.Compiled);

    private string? customEngineSourceRoot;
    private IReadOnlyDictionary<string, string>? customShaderDirectoryMappings;
    private readonly Dictionary<(int ContextId, int NodeIndex), CustomInvocation> customInvocations = new();
    private readonly HashSet<(int ContextId, int NodeIndex)> customVisiting = new();
    private readonly HashSet<string> customEmittedDeclarations = new(StringComparer.Ordinal);
    private bool customMaterialAttributesStructEmitted;
    private bool customLwcUsed;
    private bool customPixelDiscard;

    /// <summary>Configures Unreal Engine shader roots and virtual include mappings for Custom material compilation.</summary>
    public MaterialCompiler ConfigureShaderSources(
        string? engineSourceRoot,
        IReadOnlyDictionary<string, string>? directoryMappings = null)
    {
        customEngineSourceRoot = engineSourceRoot;
        customShaderDirectoryMappings = directoryMappings;
        return this;
    }

    private void ResetCustomExpressionState()
    {
        customInvocations.Clear();
        customVisiting.Clear();
        customEmittedDeclarations.Clear();
        customMaterialAttributesStructEmitted = false;
        customLwcUsed = false;
        customPixelDiscard = false;
    }

    private Expr Custom(Context context, SourceObject node, int output)
    {
        CustomInvocation invocation = EnsureCustomInvocation(context, node);
        if (output < 0 || output > invocation.AdditionalOutputs.Count)
            throw new ConversionException($"Invalid Custom material output index {output} in {context.Asset.Name}:{node.Name}");

        if (output == 0) {
            if (invocation.MainKind == CustomHlslTranslator.OutputKind.MaterialAttributes)
                throw new ConversionException($"Custom material output {output} (CMOT_MaterialAttributes) in {context.Asset.Name}:{node.Name} must connect to a MaterialAttributes pin");
            return invocation.MainExpr!;
        }

        CustomOutputSlot slot = invocation.AdditionalOutputs[output - 1];
        if (slot.Kind == CustomHlslTranslator.OutputKind.MaterialAttributes)
            throw new ConversionException($"Custom material output {output} ({slot.Name}: CMOT_MaterialAttributes) in {context.Asset.Name}:{node.Name} must connect to a MaterialAttributes pin");
        return new Expr(slot.VariableName, OutputKindWidth(slot.Kind), UnsignedInt: IsUnsignedOutputKind(slot.Kind));
    }

    private AttributeResult CustomAttribute(Context context, SourceObject node, int output, MaterialAttribute attribute)
    {
        CustomInvocation invocation = EnsureCustomInvocation(context, node);
        if (output < 0 || output > invocation.AdditionalOutputs.Count)
            throw new ConversionException($"Invalid Custom material attribute output index {output} in {context.Asset.Name}:{node.Name}");

        (CustomHlslTranslator.OutputKind kind, string varName) = output == 0
            ? (invocation.MainKind, invocation.MainVarName)
            : (invocation.AdditionalOutputs[output - 1].Kind, invocation.AdditionalOutputs[output - 1].VariableName);

        if (kind != CustomHlslTranslator.OutputKind.MaterialAttributes)
            throw new ConversionException($"Custom material output {output} in {context.Asset.Name}:{node.Name} is not CMOT_MaterialAttributes");

        string field = CustomHlslTranslator.MaterialAttributeField(attribute);
        int width = MaterialAttributes.Get(attribute).Width;
        Expr value = attribute == MaterialAttribute.ShadingModel
            ? new Expr($"float({varName}.{field})", 1)
            : new Expr($"{varName}.{field}", width);
        return new AttributeResult(value, Authored: true);
    }

    private CustomInvocation EnsureCustomInvocation(Context context, SourceObject node)
    {
        var key = (context.Id, node.Index);
        if (customInvocations.TryGetValue(key, out CustomInvocation? cached))
            return cached;
        if (!customVisiting.Add(key))
            throw new ConversionException($"Cyclic Custom material expression {context.Asset.Name}:{node.Name}");

        try {
            JObject properties = node.Properties;
            string code = properties.Value<string>("Code")
                ?? throw new ConversionException($"Custom material code is missing in {context.Asset.Name}:{node.Name}");
            string? preamble = properties.Value<string>("Preamble");

            string clipMode = (properties.Value<string>("ContainsClipInstruction") ?? "CMCI_Search").Split("::").Last();
            if (clipMode is "CMCI_Yes" or "1") {
                if (compilingVertex)
                    throw new ConversionException("Custom material clip/discard instruction requires pixel shading");
                customPixelDiscard = true;
            }

            // UMaterialExpressionCustom constructor sets OutputType = CMOT_Float3, so delta serialization omits it for Float3.
            CustomHlslTranslator.OutputKind mainKind = ParseCustomOutputKind(properties.Value<string>("OutputType"), "CMOT_Float3");
            var mainSpec = new CustomHlslTranslator.OutputPinSpec("Return", mainKind, IsAdditionalOutput: false);

            var additionalSpecs = new List<CustomHlslTranslator.OutputPinSpec>();
            var usedNames = new HashSet<string>(StringComparer.Ordinal);
            foreach (JObject outObj in properties["AdditionalOutputs"] as JArray ?? new JArray()) {
                string? outName = outObj.Value<string>("OutputName")?.Trim();
                if (string.IsNullOrWhiteSpace(outName) || outName.Equals("None", StringComparison.OrdinalIgnoreCase))
                    continue;
                if (!usedNames.Add(outName))
                    throw new ConversionException($"Duplicate Custom output name '{outName}' in {context.Asset.Name}:{node.Name}");
                additionalSpecs.Add(new CustomHlslTranslator.OutputPinSpec(
                    outName,
                    ParseCustomOutputKind(outObj.Value<string>("OutputType"), "CMOT_Float1"),
                    IsAdditionalOutput: true));
            }

            var pinParams = new List<CustomHlslTranslator.PinParameter>();
            var seenInputs = new HashSet<string>(StringComparer.Ordinal);
            var rawInputConnections = new Dictionary<string, JToken?>(StringComparer.Ordinal);
            var sceneNodes = new Dictionary<string, (Context SceneContext, SourceObject SceneNode)>(StringComparer.Ordinal);
            var resolvedExprInputs = new Dictionary<string, Expr>(StringComparer.Ordinal);
            var virtualTextureExprs = new Dictionary<string, Expr>(StringComparer.Ordinal);

            foreach (JObject inputObj in properties["Inputs"] as JArray ?? new JArray()) {
                string? inputName = inputObj.Value<string>("InputName")?.Trim();
                if (string.IsNullOrWhiteSpace(inputName) || inputName.Equals("None", StringComparison.OrdinalIgnoreCase))
                    continue;
                if (!seenInputs.Add(inputName))
                    throw new ConversionException($"Duplicate Custom input name '{inputName}' in {context.Asset.Name}:{node.Name}");
                if (usedNames.Contains(inputName))
                    throw new ConversionException($"Custom input name '{inputName}' collides with AdditionalOutputs in {context.Asset.Name}:{node.Name}");

                JToken? rawConnection = inputObj["Input"];
                rawInputConnections[inputName] = rawConnection;

                (Context SceneContext, SourceObject SceneNode)? sceneTarget = ResolveSceneTextureSource(context, rawConnection);
                if (sceneTarget is { } scene) {
                    sceneNodes[inputName] = scene;
                    int sceneId = ParseSceneTextureId(scene.SceneNode.Properties);
                    pinParams.Add(new CustomHlslTranslator.PinParameter(
                        inputName,
                        CustomHlslTranslator.OutputKind.Float4,
                        IsSceneTexture: true,
                        SceneTextureId: sceneId));
                    continue;
                }

                Expr value = Connected(rawConnection) ? Input(context, rawConnection, () => Constant(0)) : Constant(0);
                if (value.Sampler) {
                    if (value.VirtualTexture) {
                        virtualTextureExprs[inputName] = value;
                        pinParams.Add(new CustomHlslTranslator.PinParameter(
                            inputName,
                            CustomHlslTranslator.OutputKind.Float4,
                            IsSampler: true,
                            SamplerType: "sampler2D",
                            IsVirtualTexture: true));
                        continue;
                    }

                    if (!textureSamplerKeys.ContainsKey(value.Code))
                        throw new ConversionException($"Custom material texture input has no ordinary sampler: {inputName}");
                    usedTextureSamplers.Add(value.Code);
                    resolvedExprInputs[inputName] = value;
                    pinParams.Add(new CustomHlslTranslator.PinParameter(
                        inputName,
                        CustomHlslTranslator.OutputKind.Float4,
                        IsSampler: true,
                        SamplerType: TextureSamplerType(TextureShape(value.Code))));
                    continue;
                }

                resolvedExprInputs[inputName] = value;
                CustomHlslTranslator.OutputKind pinKind = WidthToOutputKind(value, UsesMirTranslation);
                pinParams.Add(new CustomHlslTranslator.PinParameter(
                    inputName,
                    pinKind,
                    HasLwc: value.LargeWorld,
                    LwcType: value.LargeWorld ? $"LfxLWC{value.Width}" : null));
            }

            var defines = new List<CustomShaderPreprocessor.DefineEntry>();
            foreach (JObject def in properties["AdditionalDefines"] as JArray ?? new JArray()) {
                string? defName = def.Value<string>("DefineName")?.Trim();
                if (string.IsNullOrWhiteSpace(defName)) continue;
                defines.Add(new CustomShaderPreprocessor.DefineEntry(defName, def.Value<string>("DefineValue") ?? ""));
            }

            var includePaths = new List<string>();
            foreach (JToken token in properties["IncludeFilePaths"] as JArray ?? new JArray()) {
                string? includePath = token.Value<string>()?.Trim();
                if (!string.IsNullOrWhiteSpace(includePath))
                    includePaths.Add(includePath);
            }

            var preprocessor = new CustomShaderPreprocessor(
                customEngineSourceRoot,
                customShaderDirectoryMappings,
                compilingVertex,
                library.MaterialPermutation);
            (string preprocessedPreamble, string preprocessedCode) = preprocessor.Process(
                defines,
                includePaths,
                preamble,
                code);

            int uniqueId = nextFunction++;
            var environment = new CustomEnvironment(this, context, sceneNodes, virtualTextureExprs);
            CustomHlslTranslator.ResultData translated = CustomHlslTranslator.Translate(
                uniqueId,
                preprocessedPreamble,
                preprocessedCode,
                pinParams,
                mainSpec,
                additionalSpecs,
                environment);

            if (translated.EmitsDiscard) {
                if (compilingVertex)
                    throw new ConversionException("Custom material clip/discard instruction requires pixel shading");
                customPixelDiscard = true;
            }

            foreach (string bareScenePin in translated.BareSceneTextureInputs) {
                Expr fetched = Input(context, rawInputConnections[bareScenePin], () => Constant(0));
                resolvedExprInputs[bareScenePin] = Cast(fetched, 4);
            }

            if (translated.UsesMaterialAttributes && !customMaterialAttributesStructEmitted) {
                customMaterialAttributesStructEmitted = true;
                EmitCustomDeclaration(CustomHlslTranslator.MaterialAttributesStructDefinition());
            }

            foreach (string support in translated.SupportDeclarations)
                EmitCustomDeclaration(support);
            EmitCustomDeclaration(translated.FunctionDefinition);

            var callArgs = new List<string>();
            foreach (CustomHlslTranslator.PinParameter pin in pinParams) {
                if (pin.IsVirtualTexture) continue;
                if (pin.IsSceneTexture && !translated.BareSceneTextureInputs.Contains(pin.Name)) continue;
                Expr argExpr = resolvedExprInputs[pin.Name];
                callArgs.Add(argExpr.Sampler ? argExpr.Code : FormatCustomArgument(argExpr, pin.Kind));
                if (pin.HasLwc && translated.UsedLwcInputs.Contains(pin.Name)) {
                    customLwcUsed = true;
                    callArgs.Add(argExpr.RawCode);
                }
            }

            int callIndex = customInvocations.Count;
            var additionalSlots = new List<CustomOutputSlot>(additionalSpecs.Count);
            for (int i = 0; i < additionalSpecs.Count; i++) {
                CustomHlslTranslator.OutputPinSpec spec = additionalSpecs[i];
                string slotVar = $"lfx_cust_{callIndex}_out{i}";
                expressions.Add($"{CustomHlslTranslator.GlslTypeName(spec.Kind)} {slotVar} = {CustomHlslTranslator.ZeroInitializer(spec.Kind)};");
                callArgs.Add(slotVar);
                additionalSlots.Add(new CustomOutputSlot(spec.Name, spec.Kind, slotVar));
            }

            string mainVar = $"lfx_cust_{callIndex}_main";
            expressions.Add($"{CustomHlslTranslator.GlslTypeName(mainKind)} {mainVar} = {translated.FunctionName}({string.Join(", ", callArgs)});");

            Expr? mainExpr = mainKind == CustomHlslTranslator.OutputKind.MaterialAttributes
                ? null
                : new Expr(mainVar, OutputKindWidth(mainKind), UnsignedInt: IsUnsignedOutputKind(mainKind));

            var invocation = new CustomInvocation(mainExpr, mainKind, mainVar, additionalSlots);
            customInvocations[key] = invocation;
            return invocation;
        } finally {
            customVisiting.Remove(key);
        }
    }

    private void EmitCustomDeclaration(string declaration)
    {
        if (string.IsNullOrWhiteSpace(declaration)) return;
        if (customEmittedDeclarations.Add(declaration))
            functions.Add(declaration);
    }

    private string FormatCustomArgument(Expr argExpr, CustomHlslTranslator.OutputKind expectedKind)
    {
        int expectedWidth = OutputKindWidth(expectedKind);
        if (IsBooleanOutputKind(expectedKind)) return MirBoolean(argExpr, expectedWidth).Code;
        if (IsSignedOutputKind(expectedKind)) return CastSigned(argExpr, expectedWidth).Code;
        if (IsUnsignedOutputKind(expectedKind)) return CastUnsigned(argExpr, expectedWidth).Code;
        return Cast(argExpr, expectedWidth).Code;
    }

    private static CustomHlslTranslator.OutputKind ParseCustomOutputKind(string? raw, string defaultToken)
    {
        string token = (raw ?? defaultToken).Split("::").Last();
        return token switch {
            "CMOT_Float1" or "0" => CustomHlslTranslator.OutputKind.Float1,
            "CMOT_Float2" or "1" => CustomHlslTranslator.OutputKind.Float2,
            "CMOT_Float3" or "2" => CustomHlslTranslator.OutputKind.Float3,
            "CMOT_Float4" or "3" => CustomHlslTranslator.OutputKind.Float4,
            "CMOT_MaterialAttributes" or "4" => CustomHlslTranslator.OutputKind.MaterialAttributes,
            _ => throw new ConversionException($"Unsupported custom material output {token}")
        };
    }

    private static CustomHlslTranslator.OutputKind WidthToOutputKind(Expr value, bool mir) =>
            (value.ShaderBoolean || mir && IsMirBoolean(value), value.SignedInt, value.UnsignedInt, value.Width) switch {
        (false, false, false, 1) => CustomHlslTranslator.OutputKind.Float1,
        (false, false, false, 2) => CustomHlslTranslator.OutputKind.Float2,
        (false, false, false, 3) => CustomHlslTranslator.OutputKind.Float3,
        (false, false, false, 4) => CustomHlslTranslator.OutputKind.Float4,
        (false, false, true, 1) => CustomHlslTranslator.OutputKind.UInt1,
        (false, false, true, 2) => CustomHlslTranslator.OutputKind.UInt2,
        (false, false, true, 3) => CustomHlslTranslator.OutputKind.UInt3,
        (false, false, true, 4) => CustomHlslTranslator.OutputKind.UInt4,
        (false, true, false, 1) => CustomHlslTranslator.OutputKind.Int1,
        (false, true, false, 2) => CustomHlslTranslator.OutputKind.Int2,
        (false, true, false, 3) => CustomHlslTranslator.OutputKind.Int3,
        (false, true, false, 4) => CustomHlslTranslator.OutputKind.Int4,
        (true, false, false, 1) => CustomHlslTranslator.OutputKind.Bool1,
        (true, false, false, 2) => CustomHlslTranslator.OutputKind.Bool2,
        (true, false, false, 3) => CustomHlslTranslator.OutputKind.Bool3,
        (true, false, false, 4) => CustomHlslTranslator.OutputKind.Bool4,
        _ => throw new ConversionException($"Unsupported custom material input width {value.Width}")
    };

    private static bool IsUnsignedOutputKind(CustomHlslTranslator.OutputKind kind) => kind is
        CustomHlslTranslator.OutputKind.UInt1 or CustomHlslTranslator.OutputKind.UInt2
        or CustomHlslTranslator.OutputKind.UInt3 or CustomHlslTranslator.OutputKind.UInt4;

    private static bool IsSignedOutputKind(CustomHlslTranslator.OutputKind kind) => kind is
        CustomHlslTranslator.OutputKind.Int1 or CustomHlslTranslator.OutputKind.Int2
        or CustomHlslTranslator.OutputKind.Int3 or CustomHlslTranslator.OutputKind.Int4;

    private static bool IsBooleanOutputKind(CustomHlslTranslator.OutputKind kind) => kind is
        CustomHlslTranslator.OutputKind.Bool1 or CustomHlslTranslator.OutputKind.Bool2
        or CustomHlslTranslator.OutputKind.Bool3 or CustomHlslTranslator.OutputKind.Bool4;

    private static int OutputKindWidth(CustomHlslTranslator.OutputKind kind) => kind switch {
        CustomHlslTranslator.OutputKind.Float1 or CustomHlslTranslator.OutputKind.UInt1 or CustomHlslTranslator.OutputKind.Int1 or CustomHlslTranslator.OutputKind.Bool1 => 1,
        CustomHlslTranslator.OutputKind.Float2 or CustomHlslTranslator.OutputKind.UInt2 or CustomHlslTranslator.OutputKind.Int2 or CustomHlslTranslator.OutputKind.Bool2 => 2,
        CustomHlslTranslator.OutputKind.Float3 or CustomHlslTranslator.OutputKind.UInt3 or CustomHlslTranslator.OutputKind.Int3 or CustomHlslTranslator.OutputKind.Bool3 => 3,
        CustomHlslTranslator.OutputKind.Float4 or CustomHlslTranslator.OutputKind.UInt4 or CustomHlslTranslator.OutputKind.Int4 or CustomHlslTranslator.OutputKind.Bool4 => 4,
        _ => throw new ConversionException($"Custom output kind {kind} is not scalar or vector")
    };

    private (Context SceneContext, SourceObject SceneNode)? ResolveSceneTextureSource(Context context, JToken? connection)
    {
        Context currentContext = context;
        JToken? currentToken = connection;
        var seen = new HashSet<(int ContextId, int NodeIndex)>();

        while (currentToken is JObject link && link.Value<int?>("expression") is int exprIdx && exprIdx != 0) {
            if (!seen.Add((currentContext.Id, exprIdx)))
                throw new ConversionException($"Cyclic material connection in {currentContext.Asset.Name}");

            SourceObject candidate = currentContext.Asset.Object(exprIdx);
            if (candidate.ClassName is "MaterialExpressionReroute" or "MaterialExpressionNamedRerouteDeclaration"
                    or "MaterialExpressionNamedRerouteUsage") {
                currentToken = RerouteInput(currentContext, candidate);
                continue;
            }

            if (candidate.ClassName == "MaterialExpressionFunctionInput"
                    && currentContext.Inputs.TryGetValue(candidate.Index, out FunctionInputBinding? binding)
                    && binding.Attributes is null) {
                if (binding.Connection is not null) {
                    currentContext = binding.Caller;
                    currentToken = binding.Connection;
                } else {
                    currentToken = candidate.Properties["Preview"];
                }
                continue;
            }

            if (candidate.ClassName == "MaterialExpressionSceneTexture")
                return (currentContext, candidate);

            return null;
        }

        return null;
    }

    private sealed class CustomEnvironment : CustomHlslTranslator.IEnvironment
    {
        private readonly MaterialCompiler owner;
        private readonly Context nodeContext;
        private readonly IReadOnlyDictionary<string, (Context SceneContext, SourceObject SceneNode)> sceneNodes;
        private readonly IReadOnlyDictionary<string, Expr> virtualTextures;

        internal CustomEnvironment(
            MaterialCompiler owner,
            Context nodeContext,
            IReadOnlyDictionary<string, (Context SceneContext, SourceObject SceneNode)> sceneNodes,
            IReadOnlyDictionary<string, Expr> virtualTextures)
        {
            this.owner = owner;
            this.nodeContext = nodeContext;
            this.sceneNodes = sceneNodes;
            this.virtualTextures = virtualTextures;
        }

        public bool IsVertexStage => owner.compilingVertex;

        public int ShaderVersion => (owner.MaterialDeclarationViewInputs & 72) != 0
            || owner.doubleVectorParameterUsed
            || owner.collectionLwcUsed
            || owner.materialTextureGatherUsed
            || owner.customLwcUsed
            || owner.materialLwcTileUsed
            || owner.materialTextureShapes.ContainsValue(MaterialTextureShape.CubeArray)
            ? 400
            : 330;

        public string ResolveSceneTextureFetch(string inputName, string uvGlsl)
        {
            if (!sceneNodes.TryGetValue(inputName, out var entry))
                throw new ConversionException($"Custom input '{inputName}' is not a SceneTexture connection");
            return owner.SurfaceSceneTextureAt(entry.SceneContext, entry.SceneNode.Properties, 0, uvGlsl).Code;
        }

        public string ResolveVirtualTextureSample(string inputName, string operation, IReadOnlyList<string> args)
        {
            if (!virtualTextures.TryGetValue(inputName, out Expr? vtExpr))
                throw new ConversionException($"Custom input '{inputName}' is not a VirtualTexture connection");
            if (args.Count == 0)
                throw new ConversionException($"VirtualTexture operation {operation} on '{inputName}' requires UV coordinates");

            string uv = args[0];
            return operation switch {
                "Sample" when args.Count == 1 =>
                    owner.SampleStreamedVirtualTextureAt(nodeContext, vtExpr.Code, uv, "TMVM_None", "0.0", null, null, "SSM_FromTextureAsset", automaticBias: true),
                "SampleLevel" when args.Count == 2 =>
                    owner.SampleStreamedVirtualTextureAt(nodeContext, vtExpr.Code, uv, "TMVM_MipLevel", args[1], null, null, "SSM_FromTextureAsset", automaticBias: false),
                "SampleBias" when args.Count == 2 =>
                    owner.SampleStreamedVirtualTextureAt(nodeContext, vtExpr.Code, uv, "TMVM_MipBias", args[1], null, null, "SSM_FromTextureAsset", automaticBias: true),
                "SampleGrad" when args.Count == 3 =>
                    owner.SampleStreamedVirtualTextureAt(nodeContext, vtExpr.Code, uv, "TMVM_Derivative", "0.0", args[1], args[2], "SSM_FromTextureAsset", automaticBias: false),
                _ => throw new ConversionException($"Unsupported VirtualTexture operation {operation} ({args.Count} args) on '{inputName}'")
            };
        }

        public (string GlslType, string GlslInitExpr) ResolveParameterField(string fieldAccess)
        {
            Match texMatch = CustomTexCoordRegex.Match(fieldAccess);
            if (texMatch.Success) {
                int index = int.Parse(texMatch.Groups[1].Value, CultureInfo.InvariantCulture);
                if (index is < 0 or >= 8)
                    throw new ConversionException($"Custom material TexCoords index {index} out of range [0, 7]");
                return ("vec2", owner.Coordinates(new JObject { ["CoordinateIndex"] = index }).Code);
            }

            if (fieldAccess.StartsWith("Particle.", StringComparison.Ordinal))
                return ResolveParticleField(fieldAccess["Particle.".Length..]);

            return fieldAccess switch {
                "VertexColor" => ("vec4", ResolveVertexColorVec4()),
                "CameraVector" or "CameraVectorWS" => ("vec3", owner.volumeCoordinates ? "vec3(0.0)" : owner.CameraVector().Code),
                "VertexNormal" => ("vec3", owner.volumeCoordinates ? "vec3(0.0, 0.0, 1.0)" : owner.VertexBasis(false).Code),
                "WorldNormal" => ("vec3", owner.volumeCoordinates
                    ? "vec3(0.0, 0.0, 1.0)"
                    : owner.compilingVertex
                        ? owner.VertexBasis(false).Code
                        : $"({owner.VertexBasis(false).Code} * {owner.RasterTwoSidedSign().Code})"),
                "TangentToWorld" => ("mat3", owner.volumeCoordinates ? "mat3(1.0)" : owner.MaterialTangentBasis()),
                "TwoSidedSign" => ("float", (owner.compilingVertex || owner.volumeCoordinates) ? "1.0" : owner.RasterTwoSidedSign().Code),
                "WorldPosition_CamRelative" => ("vec3", owner.WorldPosition(new JObject { ["WorldPositionShaderOffset"] = "WPT_CameraRelative" }).Code),
                "WorldPosition_NoOffsets_CamRelative" => ("vec3", owner.WorldPosition(new JObject { ["WorldPositionShaderOffset"] = "WPT_CameraRelativeNoOffsets" }).Code),
                "AbsoluteWorldPosition" => ("vec3", owner.WorldPosition(new JObject { ["WorldPositionShaderOffset"] = "WPT_Default" }).Code),
                "SVPosition" => ("vec4", ResolveSvPosition()),
                "ScreenPosition" => ("vec4", ResolveScreenPositionClip()),
                _ => throw new ConversionException($"Unsupported custom material parameter field Parameters.{fieldAccess}")
            };
        }

        public (string GlslType, string GlslExpr) ResolveViewField(string fieldName)
        {
            switch (fieldName) {
                case "GameTime":
                    return ("float", "EffectClock.w");
                case "RealTime":
                    return ("float", "EffectClock.z");
                case "DeltaTime":
                    return ("float", "EffectTimeData.x");
                case "ViewSizeAndInvSize":
                case "BufferSizeAndInvSize":
                    if (owner.volumeCoordinates)
                        return ("vec4", "vec4(1.0 / VolumeViewport.xy, VolumeViewport.xy)");
                    owner.viewUniformUsed = true;
                    return ("vec4", "vec4(1.0 / vec2(Geometry.w, CameraUp.w), vec2(Geometry.w, CameraUp.w))");
                case "PreExposure":
                    owner.materialViewUsed |= 2;
                    return ("float", "MaterialCamera.y");
                case "OneOverPreExposure":
                    owner.materialViewUsed |= 2;
                    return ("float", "MaterialCamera.z");
                case "FrameNumber":
                    owner.materialViewUsed |= 256;
                    return ("uint", "uint(MaterialCamera.w)");
                case "PostVolumeUserFlags":
                    owner.materialViewUsed |= PostVolumeFlagsViewInput;
                    return ("int", "MaterialPostVolume.x");
                case "ViewForward":
                    if (owner.volumeCoordinates)
                        throw new ConversionException("View.ViewForward is unavailable in volume material compute stage");
                    owner.viewUniformUsed = true;
                    return ("vec3", "(-normalize(cross(CameraRight.xyz, CameraUp.xyz)).xzy)");
                case "ViewRight":
                    if (owner.volumeCoordinates)
                        throw new ConversionException("View.ViewRight is unavailable in volume material compute stage");
                    owner.viewUniformUsed = true;
                    return ("vec3", "CameraRight.xyz.xzy");
                case "ViewUp":
                    if (owner.volumeCoordinates)
                        throw new ConversionException("View.ViewUp is unavailable in volume material compute stage");
                    owner.viewUniformUsed = true;
                    return ("vec3", "CameraUp.xyz.xzy");
                case "WorldCameraOrigin":
                    return ("vec3", owner.WorldCameraPosition().Code);
                case "TranslatedWorldCameraOrigin":
                    return ("vec3", "vec3(0.0)");
                case "PreViewTranslation":
                    owner.materialViewUsed |= 8;
                    return ("vec3", "lfx_lwc_demote(LfxLWC3(-MaterialWorldTile.xyz, MaterialPreViewTranslation.xyz))");
                case "ViewToTranslatedWorld":
                case "ViewToWorld":
                    owner.materialViewUsed |= 32;
                    return ("mat4", "MaterialViewToWorld");
                case "TranslatedWorldToView":
                case "WorldToView":
                    owner.materialViewUsed |= 32;
                    return ("mat4", "MaterialWorldToView");
                case "ViewToClip":
                case "ViewToClipNoAA":
                    owner.viewProjectionUsed = true;
                    return ("mat4", "(ProjMat * mat4(0.01, 0.0, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.0, -0.01, 0.0, 0.0, 0.0, 0.0, 1.0))");
                case "TranslatedWorldToClip":
                case "WorldToClip":
                    owner.materialViewUsed |= 32;
                    owner.viewProjectionUsed = true;
                    return ("mat4", "(ProjMat * mat4(0.01, 0.0, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.0, -0.01, 0.0, 0.0, 0.0, 0.0, 1.0) * MaterialWorldToView)");
                case "ClipToView":
                    owner.viewProjectionUsed = true;
                    return ("mat4", "(mat4(100.0, 0.0, 0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 0.0, -100.0, 0.0, 0.0, 0.0, 0.0, 1.0) * inverse(ProjMat))");
                case "ClipToTranslatedWorld":
                    owner.materialViewUsed |= 32;
                    owner.viewProjectionUsed = true;
                    return ("mat4", "(MaterialViewToWorld * mat4(100.0, 0.0, 0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 0.0, -100.0, 0.0, 0.0, 0.0, 0.0, 1.0) * inverse(ProjMat))");
                case "CameraToWorld":
                case "CameraToTranslatedWorld":
                    owner.materialViewUsed |= 32;
                    return ("mat4", "MaterialCameraToWorld");
                case "WorldToCamera":
                case "TranslatedWorldToCamera":
                case "TranslatedWorldToCameraView":
                    owner.materialViewUsed |= 32;
                    return ("mat4", "MaterialWorldToCamera");
                case "TemporalAAParams":
                    owner.materialViewUsed |= 1;
                    return ("vec4", "MaterialTemporalAA");
                case "NearPlane":
                    owner.materialViewUsed |= 4;
                    return ("float", "MaterialCamera.x");
                case "ViewRectMin":
                    return ("vec4", "vec4(0.0)");
                case "FirstPersonFieldOfView":
                case "FirstPersonFieldOfViewWideAngles":
                    owner.materialViewUsed |= 128;
                    return ("vec2", "MaterialFirstPersonFov.xy");
                case "FirstPersonTanHalfFieldOfView":
                    owner.materialViewUsed |= 128;
                    return ("vec2", "MaterialFirstPersonFov.zw");
                case "FirstPersonTanAndInvTanHalfFOV":
                    owner.materialViewUsed |= 128;
                    return ("vec4", "vec4(MaterialFirstPersonFov.zw, vec2(1.0) / MaterialFirstPersonFov.zw)");
                case "FirstPersonScale":
                    owner.materialViewUsed |= 128;
                    return ("float", "MaterialFirstPersonScale.z");
                default:
                    throw new ConversionException($"Unsupported custom material view field View.{fieldName}");
            }
        }

        public string ResolveGetWorldPosition()
        {
            owner.customLwcUsed = true;
            return owner.WorldPosition(new JObject { ["WorldPositionShaderOffset"] = "WPT_Default" }).RawCode;
        }

        public void EnsureLargeWorldMath() => owner.customLwcUsed = true;

        public void MarkGatherUsed() => owner.materialTextureGatherUsed = true;

        public void MarkQueryLevelsUsed() => owner.materialTextureQueryLevelsUsed = true;

        public void MarkEyeAdaptationUsed() => owner.materialViewUsed |= EyeAdaptationViewInput;

        public void MarkPostVolumeFlagsUsed() => owner.materialViewUsed |= PostVolumeFlagsViewInput;

        public void MarkNaniteReplaceUsed() => owner.MarkPassSwitchFlag(MaterialPassSwitchFlags.NaniteReplace);

        public void MarkShadowReplaceUsed() => owner.MarkPassSwitchFlag(MaterialPassSwitchFlags.ShadowReplace);

        public void MarkDerivativesUsed()
        {
            if (owner.compilingVertex)
                throw new ConversionException("Custom material screen-space derivatives require pixel shading");
        }

        private string ResolveVertexColorVec4()
        {
            if (owner.volumeCoordinates) return "vec4(0.0)";
            if (!owner.meshCoordinates && owner.compilingVertex) return "effectColor";
            if (!owner.meshCoordinates) return "vec4(1.0)";
            return "effectVertexColor";
        }

        private string ResolveSvPosition()
        {
            if (owner.compilingVertex || owner.volumeCoordinates)
                return "vec4(0.0)";
            owner.screenPositionUsed = true;
            return "vec4(lfx_material_fragment_xy(), gl_FragCoord.z, gl_FragCoord.w)";
        }

        private string ResolveScreenPositionClip()
        {
            if (owner.compilingVertex || owner.volumeCoordinates)
                return "vec4(0.0, 0.0, 0.0, 1.0)";
            owner.screenPositionUsed = true;
            return "vec4((lfx_depth_viewport_uv().x * 2.0 - 1.0) / max(abs(gl_FragCoord.w), 1e-6), (1.0 - lfx_depth_viewport_uv().y * 2.0) / max(abs(gl_FragCoord.w), 1e-6), gl_FragCoord.z / max(abs(gl_FragCoord.w), 1e-6), 1.0 / max(abs(gl_FragCoord.w), 1e-6))";
        }

        private (string GlslType, string GlslInitExpr) ResolveParticleField(string field)
        {
            bool nonParticleContext = owner.volumeCoordinates || owner.decalCoordinates || owner.lightFunctionCoordinates;
            return field switch {
                "Color" => ("vec4", nonParticleContext ? "vec4(1.0)" : owner.meshCoordinates ? "effectVertexColor" : "effectColor"),
                "RelativeTime" => ("float", owner.volumeCoordinates ? "0.0" : "effectParticle.x"),
                "Random" => ("float", owner.volumeCoordinates ? "0.0" : owner.ParticleRandom().Code),
                "Direction" => ("vec3", owner.ParticleMaterial("direction").Code),
                "Speed" => ("float", owner.ParticleMaterial("speed").Code),
                "Velocity" => ("vec4", $"vec4({owner.ParticleMaterial("direction").Code} * {owner.ParticleMaterial("speed").Code}, {owner.ParticleMaterial("speed").Code})"),
                "Size" => ("vec2", owner.ParticleMaterial("size").Code),
                "Radius" => ("float", $"max({owner.ParticleMaterial("radius").Code}, 0.001)"),
                "TranslatedWorldPositionAndSize" => ("vec4", $"vec4({owner.ParticlePosition(new JObject { ["OriginType"] = "CameraRelative" }).Code}, max({owner.ParticleMaterial("radius").Code}, 0.001))"),
                "SubUVCoords" => ("vec4", (owner.spriteCoordinates || owner.meshCoordinates)
                    ? $"vec4({owner.SubUVProperties(0).Code}, {owner.SubUVProperties(1).Code})"
                    : "vec4(0.0)"),
                "SubUVLerp" => ("float", (owner.spriteCoordinates || owner.meshCoordinates)
                    ? owner.SubUVProperties(2).Code
                    : "0.0"),
                "MotionBlurFade" => ("float", "1.0"),
                _ => throw new ConversionException($"Unsupported custom material particle field Parameters.Particle.{field}")
            };
        }
    }
}
