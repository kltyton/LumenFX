using System.Globalization;
using System.Text;
using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Attributes;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal;

public sealed record CompiledMaterial(string Fragment, IReadOnlyDictionary<string, string> Textures,
    IReadOnlyDictionary<string, TextureSettings> TextureSettings, string Blend, bool Cull, bool DepthTest,
    double[] DynamicDefaults, bool ApplyFogging, bool FogPerPixel, string TranslucencyPass, int DynamicSlots,
    bool RibbonParticleData, bool DepthProjection, bool SceneDepth, bool MacroUV)
{
    public CompiledNaniteMaterialSource? NaniteSource { get; init; }
    internal string? NaniteSoftwareRaster { get; init; }
    public string SourceObject { get; init; } = "";
    public int AutomaticSpriteCoverageChannels { get; init; }
    public bool WriteTranslucentVelocity { get; init; }
    public bool VelocityFromDepth { get; init; }
    public bool SceneColor { get; init; }
    public bool IsSky { get; init; }
    public int MaterialView { get; init; }
    public int DBufferTextures { get; init; }
    public bool ReceivesDecals { get; init; } = true;
    public CompiledVertexMaterial? VertexMaterial { get; init; }
    public bool LightFunction { get; init; }
    public bool LightFunctionAtlasCompatible { get; init; }
    public string TranslucencyVolume { get; init; } = "none";
    public bool HighQualityForwardReflections { get; init; }
    public bool DefaultLitSurface { get; init; }
    public int ParticleLightingFeatures { get; init; }
    public string? SubsurfaceProfile { get; init; }
    public IReadOnlyList<string> SlabSubsurfaceProfiles { get; init; } = Array.Empty<string>();
    public IReadOnlyList<string> SpecularProfiles { get; init; } = Array.Empty<string>();
    public IReadOnlyList<string> ToonProfiles { get; init; } = Array.Empty<string>();
    public int AdaptiveSubstrateWords { get; init; }
    public bool SubstratePunctualVolume { get; init; }
    public bool AnisotropicSurface { get; init; }
    public bool CustomSurfaceData { get; init; }
    public string? PointShadowCoverage { get; init; }
    public bool TranslucentCustomDepth { get; init; }
    public int CustomSceneTextures { get; init; }
    public int DeferredSceneTextures { get; init; }
    public IReadOnlyList<CompiledMaterialParameter> RuntimeParameters { get; init; } = Array.Empty<CompiledMaterialParameter>();
    public IReadOnlyList<CompiledMaterialTexture> RuntimeTextures { get; init; } = Array.Empty<CompiledMaterialTexture>();
    internal IReadOnlyDictionary<string, MaterialTextureSamplerSource> TextureSamplerSources { get; init; }
        = new Dictionary<string, MaterialTextureSamplerSource>();
    internal IReadOnlyList<CompiledFontSampler> FontSamplers { get; init; } = Array.Empty<CompiledFontSampler>();
    internal IReadOnlyList<CompiledTextureParameterSampler> TextureParameterSamplers { get; init; } = Array.Empty<CompiledTextureParameterSampler>();
    internal IReadOnlyDictionary<string, IReadOnlyDictionary<int, string>> FontAssets { get; init; }
        = new Dictionary<string, IReadOnlyDictionary<int, string>>();
    internal IReadOnlyList<CompiledMaterialCollection> MaterialCollections { get; init; } = Array.Empty<CompiledMaterialCollection>();
    public IReadOnlySet<string> RenderTargetTextures { get; init; } = new HashSet<string>(StringComparer.Ordinal);
    internal bool VolumeCompute { get; init; }
    public int? RuntimeVirtualTextureOutputs { get; init; }
    public IReadOnlyList<CompiledVirtualTextureRead> VirtualTextureReads { get; init; } = Array.Empty<CompiledVirtualTextureRead>();
    public bool VirtualTextureFeedback { get; init; }
    internal IReadOnlyDictionary<string, string> SparseVolumeTextures { get; init; } = new Dictionary<string, string>();
    internal LumenFx.Compiler.Unreal.Decals.CompiledDecalMaterial? Decal { get; init; }
}

public sealed record CompiledNaniteMaterialSource(string Domain, string BlendMode, string ShadingModel,
    int? ShadingModelField, bool UsedWithNanite, bool AutomaticallySetUsage, bool Sky, bool TwoSided, double OpacityMaskClip,
    bool PixelDepthOffset, bool DitherOpacityMask);

public enum MaterialVertexFactory { Ribbon, Sprite, Mesh, Nanite, Decal, LightFunction, Volume }

public sealed partial class MaterialCompiler
{
    private readonly AssetLibrary library;
    private readonly IReadOnlyDictionary<string, string>? textureAliases;
    private readonly bool spriteCoordinates, meshCoordinates, naniteCoordinates, decalCoordinates, lightFunctionCoordinates, volumeCoordinates;
    private readonly MaterialPrecisionSettings precision;
    private readonly RendererMaterialConstants rendererConstants;
    private readonly RendererMaterialBindings rendererBindings;
    private readonly bool runtimeDynamicParameters;
    private bool sourceDynamicMaterial;

    public MaterialCompiler(AssetLibrary library, IReadOnlyDictionary<string, string>? textureAliases = null,
        bool spriteCoordinates = false) : this(library, textureAliases,
            spriteCoordinates ? MaterialVertexFactory.Sprite : MaterialVertexFactory.Ribbon) { }

    public MaterialCompiler(AssetLibrary library, IReadOnlyDictionary<string, string>? textureAliases,
        MaterialVertexFactory vertexFactory, MaterialPrecisionSettings? precision = null, RendererMaterialConstants? rendererConstants = null,
        RendererMaterialBindings? rendererBindings = null, bool runtimeDynamicParameters = false)
    {
        this.library = library;
        this.textureAliases = textureAliases;
        spriteCoordinates = vertexFactory == MaterialVertexFactory.Sprite;
        naniteCoordinates = vertexFactory is MaterialVertexFactory.Mesh or MaterialVertexFactory.Nanite;
        meshCoordinates = naniteCoordinates;
        decalCoordinates = vertexFactory == MaterialVertexFactory.Decal;
        lightFunctionCoordinates = vertexFactory == MaterialVertexFactory.LightFunction;
        volumeCoordinates = vertexFactory == MaterialVertexFactory.Volume;
        this.precision = precision ?? new();
        this.rendererConstants = rendererConstants ?? RendererMaterialConstants.Empty;
        this.rendererBindings = rendererBindings ?? RendererMaterialBindings.Empty;
        this.runtimeDynamicParameters = runtimeDynamicParameters;
    }

    private sealed record Expr(string RawCode, int Width, bool Sampler = false,
        MaterialSamplerType SamplerType = MaterialSamplerType.Color, bool ZeroConstant = false, bool LargeWorld = false,
        bool ParameterLiteral = false, int TextureDimensions = 2, bool VirtualTexture = false,
        string? DerivativeX = null, string? DerivativeY = null, bool Uniform = false, bool? StaticBool = null,
        string? StaticTextureSource = null, bool UnsignedInt = false, bool SignedInt = false, bool ShaderBoolean = false,
        bool MirColumnVector = false)
    {
        public bool IsInteger => SignedInt || UnsignedInt;
        public string Code => LargeWorld ? $"lfx_lwc_demote({RawCode})" : RawCode;
    }
    private sealed record Context(int Id, SourceAsset Asset, IReadOnlyDictionary<int, FunctionInputBinding> Inputs,
        IReadOnlyList<string> FunctionChain, string ParameterAssociation = "GlobalParameter", int ParameterIndex = -1,
        FunctionParameterOverrides? FunctionOverrides = null, MaterialLayerParameters? LayerParameters = null)
    {
        internal Context(int id, SourceAsset asset, IReadOnlyDictionary<int, FunctionInputBinding> inputs)
            : this(id, asset, inputs, Array.Empty<string>()) { }
    }
    private readonly Dictionary<(int Context, int Node, int Output), Expr> cache = new();
    private readonly HashSet<(int Context, int Node, int Output)> visiting = new();
    private readonly record struct SamplerKey(string Source, string? Parameter, string Association, int Index,
        MaterialTextureSamplerSource SamplerSource = MaterialTextureSamplerSource.Asset);
    private readonly Dictionary<SamplerKey, string> samplers = new();
    private readonly Dictionary<string, string> samplerSources = new(StringComparer.Ordinal);
    private readonly Dictionary<string, CompiledMaterialTexture> runtimeTextures = new(StringComparer.Ordinal);
    private readonly Dictionary<string, TextureSettings> textureSettings = new(StringComparer.Ordinal);
    private readonly HashSet<string> renderTargetTextures = new(StringComparer.Ordinal);
    private readonly List<string> functions = new();
    private readonly List<string> expressions = new();
    private readonly Dictionary<(string Code, int Width, bool LargeWorld, bool ParameterLiteral, string? Dx, string? Dy, bool Uniform, bool UnsignedInt, bool SignedInt, bool ShaderBoolean,
        bool MirColumnVector), Expr> expressionValues = new();
    private int nextContext;
    private int nextFunction;
    private double[]? dynamicDefaults;
    private int dynamicSlots;
    private bool ribbonParticleData;
    private bool cameraVectorUsed;
    private bool sceneDepthUsed;
    private bool sceneColorUsed;
    private int materialViewUsed;
    private bool depthProjectionUsed;
    private bool screenPositionUsed;
    private bool sphericalOpacityUsed;
    private bool macroUVUsed;
    private bool viewUniformUsed;
    private bool viewProjectionUsed;
    private bool subUVUsed;
    private bool particleRandomUsed;
    private bool decalTexcoordsUsed;
    private int dbufferTextures;
    private int customSceneTextures;
    private int deferredSceneTextures;
    private bool compilingVertex;
    private bool compilingPreviousFrame;
    private bool vertexMaterialUsed;
    private int pixelCoordinateMask;
    private int customizedUVMask;
    private bool worldNoOffsetsUsed;
    private bool lightFunctionUsesPositionOrCoordinates;
    private bool vertexBasisUsed, pixelVertexNormalUsed, pixelVertexTangentUsed;
    private bool previousViewUsed;
    private string compiledBlendMode = "BLEND_Opaque";
    private string compiledShadingModel = "MSM_DefaultLit";
    private bool materialTextureQueryLevelsUsed;

    public CompiledMaterial Compile(string materialPath) => Compile(materialPath, false);

    public CompiledMaterial CompileRuntimeVirtualTextureWriter(string materialPath) => Compile(materialPath, true);

    private CompiledMaterial Compile(string materialPath, bool runtimeVirtualTextureWriter)
    {
        cache.Clear(); visiting.Clear(); parameters.Clear(); samplers.Clear(); textureSettings.Clear();
        specularProfiles.Clear();
        toonProfiles.Clear();
        slabDiffusionProfiles.Clear();
        parentFontParameters.Clear();
        samplerSources.Clear();
        textureSamplerKeys.Clear();
        textureSamplerSources.Clear();
        usedTextureSamplers.Clear();
        pixelNoiseInputs = vertexNoiseInputs = NoiseInputs.None;
        renderTargetTextures.Clear();
        materialTextureShapes.Clear();
        materialTextureGatherUsed = false;
        materialTextureQueryLevelsUsed = false;
        sparseVolumeSamplers.Clear();
        runtimeTextures.Clear();
        textureParameterSamplers.Clear();
        fontSamplers.Clear();
        fontAssets.Clear();
        runtimeParameters.Clear();
        materialCollections.Clear();
        collectionLwcUsed = false;
        doubleVectorParameterUsed = false;
        materialLwcTileUsed = false;
        functionScopes.Clear();
        layerGraphs.Clear(); materialInstanceSources.Clear();
        attributeCache.Clear(); attributeVisiting.Clear();
        staticSwitchOverrides.Clear();
        inheritedStaticSwitchDefaults.Clear();
        staticMaskOverrides.Clear();
        functions.Clear(); expressions.Clear(); expressionValues.Clear();
        ResetCustomExpressionState();
        nextContext = nextFunction = 0;
        dynamicDefaults = null;
        dynamicSlots = 0;
        ribbonParticleData = false;
        cameraVectorUsed = false;
        sceneDepthUsed = false;
        particleRandomUsed = false;
        sceneColorUsed = false;
        materialViewUsed = 0;
        depthProjectionUsed = false;
        screenPositionUsed = false;
        sphericalOpacityUsed = false;
        macroUVUsed = false;
        viewUniformUsed = false;
        viewProjectionUsed = false;
        subUVUsed = false;
        decalTexcoordsUsed = false;
        dbufferTextures = 0;
        customSceneTextures = 0;
        deferredSceneTextures = 0;
        vertexSceneViewportUsed = vertexSceneDepthUsed = vertexPixelDepthUsed = false;
        vertexCustomSceneTextures = vertexDeferredSceneTextures = 0;
        compilingVertex = false;
        compilingPreviousFrame = false;
        vertexMaterialUsed = false;
        pixelCoordinateMask = customizedUVMask = 0;
        worldNoOffsetsUsed = false;
        lightFunctionUsesPositionOrCoordinates = false;
        vertexBasisUsed = pixelVertexNormalUsed = pixelVertexTangentUsed = false;
        rasterNormalSource = null;
        rasterNormalInput = null;
        rasterWorldNormal = null;
        rasterTwoSided = rasterNaniteTwoSidedUsed = false;
        previousViewUsed = false;
        preSkinnedInputs = 0;
        pixelBasisMatrixUsed = false;
        previousMatricesUsed = false;
        meshMaterialMatricesUsed = pixelMeshMaterialMatricesUsed = false;
        pixelNaniteInstanceDataUsed = vertexNaniteInstanceDataUsed = false;
        pixelPassSwitchFlags = vertexPassSwitchFlags = MaterialPassSwitchFlags.None;
        primitiveMaterialMatricesUsed = false;
        volumeMaterialAffineUsed = false;
        previousFirstPersonUsed = false;
        firstPersonRootInputs = 0;
        previousClockUsed = false;
        vertexInterpolators.Clear();
        this.runtimeVirtualTextureWriter = runtimeVirtualTextureWriter;
        compilingVirtualTextureOutput = false;
        virtualTexturePageViewUsed = false;
        virtualTextureReads.Clear();
        virtualTextureFeedbackStacks.Clear();
        SourceAsset asset = library.Load(materialPath);
        SourceObject material = asset.ResolveObject(materialPath, IsMaterial);
        sourceDynamicMaterial = material.ClassName == "MaterialInstanceDynamic";
        string sourceObject = asset.ObjectPath(material.Index);
        string blend = "alpha";
        string? blendOverride = null;
        string? shadingModelOverride = null;
        bool? twoSidedOverride = null;
        bool? outputVelocityOverride = null;
        bool? castAsMaskedOverride = null;
        double? maskClipOverride = null;
        int materialInstanceDepth = 0;
        HashSet<(string Asset, int Object)> materialParents = new();
        while (material.ClassName is "MaterialInstanceConstant" or "MaterialInstanceDynamic")
        {
            if (!materialParents.Add((asset.Name, material.Index)))
                throw new ConversionException($"Recursive material instance parent: {asset.Name}:{material.Name}");
            JObject properties = material.Properties;
            materialInstanceSources.Add(new MaterialInstanceSource(asset, material));
            if (material.ClassName == "MaterialInstanceConstant")
                ReadStaticParameters(asset, material, materialInstanceDepth++ != 0);
            if (properties["BasePropertyOverrides"] is JObject overrides)
            {
                if (blendOverride is null && overrides.Value<bool?>("bOverride_BlendMode") == true)
                    blendOverride = overrides.Value<string>("BlendMode");
                if (shadingModelOverride is null && material.ClassName == "MaterialInstanceConstant"
                        && overrides.Value<bool?>("bOverride_ShadingModel") == true)
                {
                    string? model = overrides.Value<string>("ShadingModel")?.Split("::").Last();
                    if (model != "MSM_FromMaterialExpression") shadingModelOverride = model;
                }
                if (twoSidedOverride is null && overrides.Value<bool?>("bOverride_TwoSided") == true)
                    twoSidedOverride = overrides.Value<bool>("TwoSided");
                if (outputVelocityOverride is null && overrides.Value<bool?>("bOverride_OutputTranslucentVelocity") == true)
                    outputVelocityOverride = overrides.Value<bool>("bOutputTranslucentVelocity");
                if (maskClipOverride is null && overrides.Value<bool?>("bOverride_OpacityMaskClipValue") == true)
                    maskClipOverride = SourceAsset.Number(overrides["OpacityMaskClipValue"]);
                if (castAsMaskedOverride is null && material.ClassName == "MaterialInstanceConstant"
                        && overrides.Value<bool?>("bOverride_CastDynamicShadowAsMasked") == true)
                    castAsMaskedOverride = overrides.Value<bool>("bCastDynamicShadowAsMasked");
            }
            ReadInstanceParameters(asset, material);
            string parent = asset.ObjectPath(properties.Value<int>("Parent"));
            asset = library.Load(parent);
            material = asset.ResolveObject(parent, IsMaterial);
        }
        compiledMaterialProperties = material.Properties;
        compiledBlendMode = (blendOverride ?? material.Properties.Value<string>("BlendMode") ?? "BLEND_Opaque").Split("::").Last();
        compiledShadingModel = (shadingModelOverride ?? material.Properties.Value<string>("ShadingModel") ?? "MSM_DefaultLit").Split("::").Last();
        if (runtimeVirtualTextureWriter && (decalCoordinates || lightFunctionCoordinates || volumeCoordinates
                || (material.Properties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last() != "MD_Surface"))
            throw new ConversionException($"A runtime virtual texture writer requires a surface material: {sourceObject}");
        if (decalCoordinates) return CompileDecalMaterial(asset, material, sourceObject, blendOverride);
        if (lightFunctionCoordinates) return CompileLightFunctionMaterial(asset, material, sourceObject);
        if (volumeCoordinates) return CompileVolumeMaterial(asset, material, sourceObject);
        string mode = compiledBlendMode;
        bool cull = !(twoSidedOverride ?? material.Properties.Value<bool?>("TwoSided") ?? false);
        bool translucent = mode is not ("BLEND_Opaque" or "BLEND_Masked");
        bool applyFogging = !runtimeVirtualTextureWriter && translucent && (material.Properties.Value<bool?>("bUseTranslucencyVertexFog") ?? true);
        bool fogPerPixel = material.Properties.Value<bool?>("bComputeFogPerPixel") ?? false;
        string sourcePass = translucent ? (material.Properties.Value<string>("TranslucencyPass") ?? "MTP_AfterDOF").Split("::").Last()
            : "MTP_BeforeDOF";
        string translucencyPass = sourcePass switch {
            "MTP_BeforeDOF" => "before_dof", "MTP_AfterDOF" => "after_dof",
            "MTP_AfterMotionBlur" => "after_motion_blur",
            _ => throw new ConversionException($"Unknown translucency pass {sourcePass}: {materialPath}")
        };
        bool modulate = mode is "BLEND_Modulate" or "BLEND_ColoredTransmittanceOnly";
        if (modulate && !runtimeVirtualTextureWriter && sourcePass == "MTP_AfterMotionBlur")
            throw new ConversionException($"Modulate is not supported in the source After Motion Blur pass: {sourceObject}");
        SourceObject data = material.Properties["EditorOnlyData"] is { } editor
            ? asset.Object(editor.Value<int>()) : material;
        Context context = new(nextContext++, asset, new Dictionary<int, FunctionInputBinding>());
        if (!runtimeVirtualTextureWriter) {
            rasterNormalSource = (context, data);
            rasterTwoSided = !cull;
        }
        SubstrateSurface? substrate = null;
        if (!runtimeVirtualTextureWriter && Connected(data.Properties["FrontMaterial"])) {
            Expr? thickness = compiledMaterialProperties.Value<bool?>("bIsThinSurface") == true
                ? Cast(MaterialInput(context, data, MaterialAttribute.SurfaceThickness,
                    () => DefaultAttribute(MaterialAttribute.SurfaceThickness)).Value, 1) : null;
            substrate = FitSlabClosureBudget(CompileSubstrateSurface(context, data.Properties["FrontMaterial"], new(), thickness: thickness));
        }
        var unlitSubstrate = substrate as UnlitSubstrate;
        var layeredSlabs = substrate as LayeredSlabs;
        var adaptiveSubstrate = translucent ? null
            : layeredSlabs is not null ? CompileSlabClosurePayload(layeredSlabs)
            : substrate is SlabSubstrate { RequiresDeferredMedium: true } diffusionSlab ? CompileSlabClosurePayload(SlabLayers(diffusionSlab)) : null;
        var slabSubstrate = layeredSlabs is null ? substrate as SlabSubstrate
            : layeredSlabs.Layers[0].Slab with { Coverage = layeredSlabs.Coverage };
        var hairSubstrate = substrate as HairSubstrate;
        var eyeSubstrate = substrate as EyeSubstrate;
        DefaultLitSurface? blendableSlabProfile = null;
        if ((substrate is HairSubstrate or EyeSubstrate or ToonSubstrate) && translucent)
            throw new ConversionException($"Translucent Substrate {substrate.GetType().Name} requires forward closure integration: {sourceObject}");
        bool translucentSlab = (layeredSlabs is not null || slabSubstrate is not null) && translucent;
        TranslucentSlabModel? translucentSlabModel = null;
        if (translucentSlab) {
            Expr ao = Cast(MaterialInput(context, data, MaterialAttribute.AmbientOcclusion,
                () => DefaultAttribute(MaterialAttribute.AmbientOcclusion)).Value, 1);
            translucentSlabModel = CompileTranslucentSlabModel(substrate!, ao,
                compiledMaterialProperties.Value<bool?>("bIsThinSurface") == true);
        }
        if (slabSubstrate is not null && !translucentSlab) {
            Expr ao = Cast(MaterialInput(context, data, MaterialAttribute.AmbientOcclusion,
                () => DefaultAttribute(MaterialAttribute.AmbientOcclusion)).Value, 1);
            Expr normal = slabSubstrate.WorldNormal;
            if (rasterTwoSided && compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") != false)
                normal = Shared(new Expr($"({normal.Code} * {RasterTwoSidedSign().Code})", 3));
            slabSubstrate = slabSubstrate with { AmbientOcclusion = ao, WorldNormal = normal, DecalResponse = CompileSurfaceDecalResponse() };
            if (!library.MaterialPermutation.AdaptiveSubstrate && slabSubstrate.DiffusionProfile is { } profile)
                blendableSlabProfile = CompileBlendableSlabProfile(slabSubstrate, profile);
        }
        string shadingModel = compiledShadingModel;
        string domain = (material.Properties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last();
        ThinTranslucentSurface? thinTranslucent = translucent && !modulate && !runtimeVirtualTextureWriter
                && domain == "MD_Surface" && substrate is null && shadingModel == "MSM_ThinTranslucent"
            ? CompileThinTranslucentSurface(context, data) : null;
        blend = runtimeVirtualTextureWriter ? "premultiplied"
            : unlitSubstrate is not null || translucentSlab ? UnlitSubstrateBlend(mode, sourceObject)
            : thinTranslucent is not null ? "colored_transmittance" : mode switch
        {
            "BLEND_Additive" => "additive",
            "BLEND_AlphaComposite" => "premultiplied",
            "BLEND_Translucent" or "BLEND_TranslucentColoredTransmittance" or "BLEND_TranslucentGreyTransmittance" => "alpha",
            "BLEND_AlphaHoldout" => "holdout",
            "BLEND_Modulate" or "BLEND_ColoredTransmittanceOnly" => "modulate",
            "BLEND_Opaque" or "BLEND_Masked" => "opaque",
            _ => throw new ConversionException($"Unsupported material blend {mode}: {materialPath}")
        };
        RuntimeVirtualTextureOutput? virtualTextureOutput = runtimeVirtualTextureWriter ? CompileVirtualTextureOutput(context, data) : null;
        Expr emissive = substrate?.Emissive ?? (runtimeVirtualTextureWriter ? Constant(0)
            : MaterialInput(context, data, MaterialAttribute.EmissiveColor, () => Constant(0)).Value);
        Expr opacity = (unlitSubstrate is not null || translucentSlab) && translucent && mode != "BLEND_AlphaComposite"
            ? substrate!.Coverage : runtimeVirtualTextureWriter || substrate is not null && mode == "BLEND_Opaque"
                ? Constant(1) : MaterialInput(context, data,
                    mode == "BLEND_Masked" ? MaterialAttribute.OpacityMask : MaterialAttribute.Opacity, () => Constant(1)).Value;
        bool castAsMasked = translucent && (castAsMaskedOverride ?? material.Properties.Value<bool?>("bCastDynamicShadowAsMasked") ?? false);
        bool translucentCustomDepth = translucent && domain == "MD_Surface" && !runtimeVirtualTextureWriter
            && material.Properties.Value<bool?>("AllowTranslucentCustomDepthWrites") == true;
        bool outputTranslucentVelocity = outputVelocityOverride ?? material.Properties.Value<bool?>("bOutputTranslucentVelocity") ?? false;
        Expr? motionWorldOffset = !runtimeVirtualTextureWriter && domain == "MD_Surface" && !translucent
            ? CompileMotionVectorWorldOffset(context, data) : null;
        Expr? pixelDepthOffset = !runtimeVirtualTextureWriter && domain == "MD_Surface" && (!translucent || outputTranslucentVelocity)
            ? Cast(MaterialInput(context, data, MaterialAttribute.PixelDepthOffset, () => Constant(0)).Value, 1)
            : null;
        if (pixelDepthOffset?.ZeroConstant == true) pixelDepthOffset = null;
        bool cameraVectorDepthOffset = pixelDepthOffset is not null && UsesCameraVectorDepthOffset(asset, material);
        depthProjectionUsed |= pixelDepthOffset is not null;
        string? shadowCoverage = !runtimeVirtualTextureWriter && domain == "MD_Surface" && shadingModel != "MSM_SingleLayerWater"
            ? mode == "BLEND_Opaque" ? "solid" : mode == "BLEND_Masked" || castAsMasked ? "masked" : null
            : null;
        string opacityClip = Number(maskClipOverride ?? SourceAsset.Number(material.Properties["OpacityMaskClipValue"], .3333));
        bool naniteDitherMask = naniteCoordinates && mode == "BLEND_Masked"
            && material.Properties.Value<bool?>("DitherOpacityMask") == true;
        bool naniteSoftwareRaster = naniteCoordinates && !translucent && !runtimeVirtualTextureWriter
            && domain == "MD_Surface" && (mode == "BLEND_Masked" || pixelDepthOffset is not null);
        string naniteMask = naniteDitherMask ? $"lfx_nanite_dither_mask(rawOpacity - {opacityClip})"
            : $"(rawOpacity - {opacityClip})";
        Expr SurfaceAmbientOcclusion() => Cast(MaterialInput(context, data, MaterialAttribute.AmbientOcclusion,
            () => DefaultAttribute(MaterialAttribute.AmbientOcclusion)).Value, 1);
        DefaultLitSurface? defaultLit = substrate switch {
            HairSubstrate hair => CompileHairSurface(hair, SurfaceAmbientOcclusion()),
            EyeSubstrate eye => CompileEyeSurface(eye, SurfaceAmbientOcclusion()),
            ToonSubstrate toon => CompileToonSurface(toon, SurfaceAmbientOcclusion()),
            null when !translucent && !runtimeVirtualTextureWriter
                && domain == "MD_Surface" && shadingModel is ("MSM_DefaultLit" or "MSM_MAX" or "MSM_Subsurface"
                    or "MSM_PreintegratedSkin" or "MSM_ClearCoat" or "MSM_SubsurfaceProfile" or "MSM_TwoSidedFoliage"
                    or "MSM_Hair" or "MSM_Cloth" or "MSM_Eye")
                => CompileDefaultLitSurface(context, data, shadingModel),
            _ => null
        };
        var translucentLighting = translucentSlab && !modulate && !runtimeVirtualTextureWriter && domain == "MD_Surface"
            ? CompileTranslucentSlabLighting(translucentSlabModel!.Layers[0].Slab)
            : translucent && !modulate && !runtimeVirtualTextureWriter && domain == "MD_Surface"
                && substrate is null && shadingModel is ("MSM_DefaultLit" or "MSM_Subsurface" or "MSM_ClearCoat" or "MSM_ThinTranslucent")
            ? CompileTranslucencyVolumeSurface(context, data, shadingModel) : null;
        string[] pixelFunctions = functions.ToArray(), pixelExpressions = expressions.ToArray();
        CompiledVertexMaterial? vertexMaterial = CompileVertexMaterial(context, data, pixelCoordinateMask, runtimeVirtualTextureWriter);
        functions.AddRange(pixelFunctions); expressions.AddRange(pixelExpressions);
        vertexMaterialUsed = vertexMaterial is not null;
        StringBuilder shader = new(MaterialShaderHeader());
        AppendMaterialView(shader);
        shader.AppendLine("#moj_import <lumenfx:material_fog.glsl>");
        if (translucentLighting?.Surface is not null) {
            bool forward = translucentLighting.Mode == "surface_per_pixel";
            shader.AppendLine(forward ? "#if LFX_FORWARD_TRANSLUCENCY && !LFX_PARTICLE_MOTION"
                : "#if LFX_SURFACE_VOLUME_ENVIRONMENT && !LFX_PARTICLE_MOTION");
            bool preintegratedGf = compiledMaterialProperties.Value<bool?>("bForwardRenderUsePreintegratedGFForSimpleIBL") == true;
            shader.AppendLine($"#define LFX_MATERIAL_PREINTEGRATED_GF {(preintegratedGf ? 1 : 0)}");
            if (compiledMaterialProperties.Value<bool?>("bForwardBlendsSkyLightCubemaps") == true)
                shader.AppendLine("#define LFX_MATERIAL_SKY_BLEND 1");
            shader.AppendLine(forward ? "#moj_import <lumenfx:lighting/forward_translucency.glsl>"
                : "#moj_import <lumenfx:lighting/translucent_surface_environment.glsl>");
            if (translucentSlab) {
                shader.AppendLine(forward ? "#moj_import <lumenfx:lighting/forward_translucent_slab.glsl>"
                    : "#moj_import <lumenfx:lighting/translucent_slab_env.glsl>");
            }
            shader.AppendLine("#endif");
        }
        if (translucentLighting is { Mode: not "surface_per_pixel" }) {
            shader.AppendLine("#if LFX_TRANSLUCENCY_LIGHTING && !LFX_PARTICLE_MOTION");
            shader.AppendLine("#moj_import <lumenfx:lighting/translucency_volume_material.glsl>");
            shader.AppendLine("#endif");
        }
        if (vertexMaterialUsed) AppendNanitePixelInput(shader, "vec3 effectPositionNoOffsets");
        if (pixelVertexNormalUsed) AppendNanitePixelInput(shader, "vec3 effectMaterialVertexNormal");
        if (pixelVertexTangentUsed) AppendNanitePixelInput(shader, "vec3 effectMaterialVertexTangent");
        AppendPixelInterpolators(shader);
        AppendNaniteInstanceMaterialData(shader, false);
        AppendMaterialPassSwitchHelpers(shader, vertex: false);
        if (naniteDitherMask) AppendNaniteOpacityMaskDither(shader);
        AppendMeshMaterialMatrices(shader, false);
        AppendPrimitiveMaterialMatrices(shader);
        AppendRasterTwoSidedSign(shader);
        if (worldNoOffsetsUsed) shader.AppendLine("vec3 lfx_material_position_no_offsets() { return "
                + (vertexMaterialUsed ? "effectPositionNoOffsets" : "effectPosition") + "; }");
        AppendNaniteWorldDerivatives(shader);
        for (int coordinate = 0; coordinate < 8; coordinate++)
            if ((customizedUVMask & (1 << coordinate)) != 0) AppendNanitePixelInput(shader, $"vec2 lfxCustomizedUV{coordinate}");
        if (cameraVectorUsed || depthProjectionUsed || viewProjectionUsed) shader.AppendLine("#moj_import <minecraft:projection.glsl>");
        if (cameraVectorUsed || depthProjectionUsed || screenPositionUsed || viewUniformUsed || macroUVUsed) {
            shader.AppendLine("layout(std140) uniform EffectView { vec4 CameraRight; vec4 CameraUp; vec4 Geometry;\n#if LFX_MACRO_UV\nvec4 MacroUV;\n#endif\n#if LFX_DEPTH_PROJECTION\nvec4 DepthTransform;\n#endif\n};");
        }
        if (cameraVectorUsed) {
            shader.AppendLine("vec3 lfx_camera_vector() { vec3 v = ProjMat[3][3] >= 1.0 ? normalize(cross(CameraRight.xyz, CameraUp.xyz)) : normalize(-effectPosition); return v.xzy; }");
        }
        if (depthProjectionUsed) {
            AppendMaterialLinearDepth(shader);
            if (naniteCoordinates) {
                shader.AppendLine("#if LFX_NANITE_MATERIAL");
                shader.AppendLine("float lfx_pixel_depth() { return ProjMat[3][3] >= 1.0 ? lfx_linear_depth(lfxNaniteGeometry.surfaceDepth) : 100.0 / lfxNaniteGeometry.inverseClipW; }");
                shader.AppendLine("#else");
            }
            shader.AppendLine("float lfx_pixel_depth() { return ProjMat[3][3] >= 1.0 ? lfx_linear_depth(gl_FragCoord.z) : 100.0 / gl_FragCoord.w; }");
            if (naniteCoordinates) shader.AppendLine("#endif");
        }
        if (pixelDepthOffset is not null) {
            shader.AppendLine("#if !LFX_PARTICLE_POINT_SHADOW");
            shader.AppendLine("#define LFX_MATERIAL_WRITES_DEPTH 1");
            shader.AppendLine("#moj_import <lumenfx:depth/pixel_depth_offset.glsl>");
            shader.AppendLine("#endif");
        }
        if (motionWorldOffset is not null) {
            shader.AppendLine("#if LFX_PARTICLE_MOTION && !LFX_NANITE_MATERIAL");
            shader.AppendLine("#define LFX_MATERIAL_MOTION_OFFSET 1");
            shader.AppendLine("#moj_import <minecraft:dynamictransforms.glsl>");
            shader.AppendLine("vec3 lfxMaterialMotionWorldOffset = vec3(0.0);");
            shader.AppendLine("#endif");
        }
        if (sceneDepthUsed || screenPositionUsed || customSceneTextures != 0 || deferredSceneTextures != 0) {
            shader.AppendLine("#if LFX_NANITE_SOFTWARE_RASTER");
            shader.AppendLine("vec2 lfx_material_fragment_xy() { return lfxNaniteSoftwareFragCoord.xy; }");
            shader.AppendLine("#else");
            shader.AppendLine("vec2 lfx_material_fragment_xy() { return gl_FragCoord.xy; }");
            shader.AppendLine("#endif");
        }
        if (sceneDepthUsed || screenPositionUsed)
            shader.AppendLine("vec2 lfx_depth_viewport_uv() { vec2 uv = lfx_material_fragment_xy() * vec2(Geometry.w, CameraUp.w); return vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y); }");
        if (sceneDepthUsed) {
            shader.AppendLine("uniform sampler2D SceneDepthSampler;");
            shader.AppendLine("float lfx_scene_depth() { return lfx_linear_depth(texture(SceneDepthSampler, lfx_material_fragment_xy() * vec2(Geometry.w, CameraUp.w)).r); }");
            shader.AppendLine("float lfx_scene_depth_at(vec2 uv) { vec2 hostUV = vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y); return lfx_linear_depth(texture(SceneDepthSampler, hostUV).r); }");
        }
        if (customSceneTextures != 0) shader.AppendLine("#moj_import <lumenfx:depth/custom_scene_textures.glsl>");
        if (deferredSceneTextures != 0) shader.AppendLine("#moj_import <lumenfx:scene/material_deferred_textures.glsl>");
        AppendVirtualTextureReadDeclarations(shader);
        if (virtualTextureOutput is not null || virtualTexturePageViewUsed) AppendVirtualTexturePageView(shader);
        AppendDBufferLookup(shader);
        if (hairSubstrate is not null) shader.AppendLine("#define LFX_PARTICLE_SUBSTRATE_HAIR 1");
        if (eyeSubstrate is not null) shader.AppendLine("#define LFX_PARTICLE_SUBSTRATE_EYE 1");
        if (substrate is ToonSubstrate) shader.AppendLine("#define LFX_PARTICLE_SUBSTRATE_TOON 1");
        if (substrate is ToonSubstrate && library.MaterialPermutation.AdaptiveSubstrate)
            shader.AppendLine("#define LFX_PARTICLE_TOON_ADAPTIVE 1");
        if (defaultLit is not null || blendableSlabProfile is not null)
            shader.AppendLine("#moj_import <lumenfx:lighting/default_lit_surface.glsl>");
        if (slabSubstrate is not null && !translucentSlab) shader.AppendLine("#moj_import <lumenfx:substrate/slab_surface.glsl>");
        if (adaptiveSubstrate is not null) shader.AppendLine("#moj_import <lumenfx:substrate/adaptive_closure_write.glsl>");
        AppendSpecularProfileLookup(shader);
        AppendSceneColorLookup(shader);
        AppendSphericalPixelBasis(shader);
        if (sphericalOpacityUsed) {
            MaterialShaderMath.AppendSphericalOpacity(shader);
        }
        AppendTextureSamplers(shader);
        MaterialShaderMath.Append(shader);
        AppendMaterialNoise(shader);
        AppendLargeWorldMath(shader);
        AppendPeriodicWorldMath(shader);
        foreach (var sampler in UsedTextureSamplers()) AppendSampler(shader, sampler);
        shader.AppendLine("layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; vec4 EffectTimeData; };");
        AppendMaterialCollections(shader, false);
        AppendNanitePixelInput(shader, "vec2 effectUV"); AppendNanitePixelInput(shader, "vec2 effectUV1");
        AppendNanitePixelInput(shader, "vec4 effectColor"); AppendNanitePixelInput(shader, "vec4 effectParticle");
        AppendNanitePixelInput(shader, "vec4 effectCustom"); AppendNanitePixelInput(shader, "float effectDynamicValid");
        if (meshCoordinates) {
            AppendNanitePixelInput(shader, "vec4 effectMeshUV01"); AppendNanitePixelInput(shader, "vec4 effectMeshUV23");
            AppendNanitePixelInput(shader, "vec4 effectMeshUV45"); AppendNanitePixelInput(shader, "vec4 effectMeshUV67");
            AppendNanitePixelInput(shader, "vec4 effectVertexColor"); AppendNanitePixelInput(shader, "float effectUnmirrored");
            AppendNanitePixelInput(shader, "vec4 effectMeshVelocity");
            if (subUVUsed) { AppendNanitePixelInput(shader, "vec2 effectMeshSubUV0"); AppendNanitePixelInput(shader, "vec2 effectMeshSubUV1"); AppendNanitePixelInput(shader, "float effectSubImageLerp"); }
            shader.AppendLine("vec2 lfx_mesh_coordinate(int channel) {");
            shader.AppendLine(subUVUsed ? "return effectUV;"
                : "vec4 coordinates = channel < 2 ? effectMeshUV01 : channel < 4 ? effectMeshUV23 : channel < 6 ? effectMeshUV45 : effectMeshUV67; return (channel & 1) == 0 ? coordinates.xy : coordinates.zw;");
            shader.AppendLine("}");
        }
        if ((dynamicSlots & 14) != 0) { AppendNanitePixelInput(shader, "vec4 effectCustom1"); AppendNanitePixelInput(shader, "vec4 effectCustom2"); AppendNanitePixelInput(shader, "vec4 effectCustom3"); }
        if (spriteCoordinates) shader.AppendLine("in vec2 effectNextUV; in vec2 effectUnflippedUV; in vec2 effectNextUnflippedUV;");
        if (spriteCoordinates) shader.AppendLine("in float effectSubImageLerp;");
        if (spriteCoordinates) shader.AppendLine("flat in vec4 effectSpriteVelocity; flat in vec2 effectSpriteSize; flat in float effectSpriteRotation;");
        else if (ribbonParticleData) shader.AppendLine("in vec4 effectRibbonVelocity; in vec2 effectRibbonSize;");
        AppendNanitePixelInput(shader, "vec3 effectParticleCenter", spriteCoordinates ? "flat " : "");
        if (virtualTextureOutput is not null) AppendVirtualTextureOutputDeclarations(shader);
        else if (blend == "colored_transmittance") AppendColoredTransmittanceOutputs(shader);
        else {
            if (naniteSoftwareRaster) shader.AppendLine("#if !LFX_NANITE_SOFTWARE_RASTER");
            shader.AppendLine("out vec4 fragColor;");
            if (naniteSoftwareRaster) shader.AppendLine("#endif");
        }
        AppendPixelCoordinates(shader);
        AppendPixelTangentBasis(shader);
        AppendNaniteDerivativeFunctions(shader);
        foreach (string function in functions) shader.AppendLine(function);
        if (naniteSoftwareRaster) {
            shader.AppendLine("#if LFX_NANITE_SOFTWARE_RASTER");
            shader.AppendLine("bool lfx_nanite_evaluate_coverage() {");
            shader.AppendLine("#else");
        }
        shader.AppendLine("void main() {");
        if (naniteSoftwareRaster) shader.AppendLine("#endif");
        AppendNaniteMeshMaterialMatrices(shader);
        AppendNanitePixelInputs(shader);
        if (UsesSphericalPixelBasis()) shader.AppendLine("lfxMaterialSphericalBasis = lfx_spherical_particle_basis();");
        foreach (string expression in expressions) shader.AppendLine("    " + expression);
        if (pixelDepthOffset is { } depthOffset) {
            shader.AppendLine("#if LFX_MATERIAL_WRITES_DEPTH");
            if (naniteCoordinates) {
                shader.AppendLine("#if LFX_NANITE_MATERIAL && !LFX_NANITE_RASTER_MATERIAL");
                shader.AppendLine("    if (lfx_nanite_fixed_raster()) lfxDepthOffsetWorldPosition = effectPosition; else");
                if (naniteSoftwareRaster) {
                    shader.AppendLine("#elif LFX_NANITE_SOFTWARE_RASTER");
                    shader.AppendLine("    if (lfx_nanite_shadow_depth()) { lfxDepthOffsetWorldPosition = effectPosition; lfxMaterialDeviceZ = lfxNaniteGeometry.deviceDepth; } else");
                }
                shader.AppendLine("#endif");
            }
            shader.AppendLine($"    lfxMaterialDeviceZ = lfx_apply_pixel_depth_offset({depthOffset.Code}, {(cameraVectorDepthOffset ? "true" : "false")});");
            if (naniteCoordinates) {
                shader.AppendLine("#if LFX_NANITE_MATERIAL && !LFX_NANITE_RASTER_MATERIAL");
                shader.AppendLine("    lfxMaterialDeviceZ = lfxNaniteGeometry.deviceDepth;");
                shader.AppendLine("#endif");
            }
            shader.AppendLine("    gl_FragDepth = lfxMaterialDeviceZ;");
            shader.AppendLine("#endif");
        }
        if (motionWorldOffset is { } pixelMotionOffset) {
            shader.AppendLine("#if LFX_PARTICLE_MOTION && !LFX_NANITE_MATERIAL");
            shader.AppendLine($"    lfxMaterialMotionWorldOffset = {pixelMotionOffset.Code};");
            shader.AppendLine("#endif");
        }
        if (naniteSoftwareRaster) {
            shader.AppendLine("#if LFX_NANITE_SOFTWARE_RASTER");
            if (mode == "BLEND_Masked") {
                shader.AppendLine($"    float rawOpacity = {Cast(opacity, 1).Code};");
                shader.AppendLine($"    return {naniteMask} >= 0.0;");
            } else shader.AppendLine("    return true;");
            shader.AppendLine("#else");
        }
        if (virtualTextureOutput is not null) AppendVirtualTextureOutput(shader, virtualTextureOutput);
        else {
            shader.AppendLine($"    vec3 emission = {Cast(emissive, 3).Code};");
            shader.AppendLine($"    float rawOpacity = {Cast(opacity, 1).Code};");
            shader.AppendLine("    float opacity = clamp(rawOpacity, 0.0, 1.0);");
            if (thinTranslucent is not null && translucentLighting is not null)
                shader.AppendLine("    emission *= opacity;");
            if (translucentSlabModel is not null) {
                if (compiledMaterialProperties.Value<bool?>("bAllowNegativeEmissiveColor") != true)
                    shader.AppendLine("    emission = max(emission, vec3(0.0));");
                if (!translucentSlabModel.PreweightedEmissive)
                    shader.AppendLine($"    emission *= clamp({(mode == "BLEND_AlphaComposite" ? "opacity" : translucentSlabModel.Coverage.Code)}, 0.0, 1.0);");
                if (translucentLighting is { Mode: "surface_per_pixel", Surface: not null } slabForward) {
                    shader.AppendLine("#if LFX_FORWARD_TRANSLUCENCY && !LFX_PARTICLE_MOTION");
                    foreach (var layer in translucentSlabModel.Layers) {
                        var s = layer.Slab;
                        string tangentCode = s.WorldTangent is { } t ? t.Code : "vec3(0.0)";
                        string hasTangent = s.WorldTangent is not null ? "true" : "false";
                        string simpleCoat = s.SimpleCoat.ZeroConstant ? "false" : $"({s.SimpleCoat.Code} > 0.5)";
                        shader.AppendLine($"    if (any(greaterThan({layer.Luminance.Code}, vec3(0.0)))) {{");
                        shader.AppendLine($"        emission += {layer.Luminance.Code} * lfx_forward_translucent_slab(effectPosition, {s.WorldNormal.Code}, {slabForward.View.Code}, clamp({s.Diffuse.Code}, 0.0, 1.0), clamp({s.F0.Code}, 0.0, 1.0), clamp({s.F90.Code}, 0.0, 1.0), clamp({s.Roughness.Code}, 0.0, 1.0), {s.AmbientOcclusion.Code}, {s.Anisotropy.Code}, {tangentCode}, {hasTangent}, {s.SecondRoughness.Code}, {s.SecondWeight.Code}, {simpleCoat}, {s.FuzzColor.Code}, {s.FuzzAmount.Code}, {s.FuzzRoughness.Code}, {layer.TransmittanceAbove.Code}, {layer.CoverageAbove.Code});");
                        shader.AppendLine("    }");
                    }
                    shader.AppendLine("#endif");
                } else if (translucentLighting is { } slabLit) {
                    bool spriteOffset = spriteCoordinates && particleRandomUsed && (slabLit.Mode is "pixel" or "surface_volume")
                        && (int)library.MaterialPermutation.EffectiveFeatureLevel >= (int)MaterialFeatureInput.Sm5;
                    string voxelOffset = spriteOffset ? "lfx_tlv_sprite_offset(effectParticle.y)" : "vec3(0.0)";
                    bool directionalSlab = slabLit.WorldNormal is not null;
                    shader.AppendLine("#if LFX_TRANSLUCENCY_LIGHTING && !LFX_PARTICLE_MOTION");
                    foreach (var layer in translucentSlabModel.Layers) {
                        var s = layer.Slab;
                        string layerWeight = $"({layer.Luminance.Code} * mix(vec3(1.0), clamp({layer.TransmittanceAbove.Code}, 0.0, 1.0), clamp({layer.CoverageAbove.Code}, 0.0, 1.0)))";
                        string layerDiffuse = $"clamp({s.Diffuse.Code}, 0.0, 1.0)";
                        shader.AppendLine($"    if (any(greaterThan({layer.Luminance.Code}, vec3(0.0)))) {{");
                        shader.AppendLine($"        emission += {layerWeight} * lfx_tlv_diffuse(effectPosition, {(directionalSlab ? s.WorldNormal.Code : "vec3(0.0)")}, {layerDiffuse}, {Number(slabLit.DirectionalIntensity)}, {(directionalSlab ? "true" : "false")}, {voxelOffset}, {layerDiffuse}, {layerDiffuse}, {s.AmbientOcclusion.Code}, {s.WorldNormal.Code}, {s.WorldNormal.Code}, false, {slabLit.View.Code});");
                        shader.AppendLine("    }");
                    }
                    shader.AppendLine("#endif");
                    if (slabLit.Surface is not null) {
                        shader.AppendLine("#if LFX_SURFACE_VOLUME_ENVIRONMENT && !LFX_PARTICLE_MOTION");
                        foreach (var layer in translucentSlabModel.Layers) {
                            var s = layer.Slab;
                            string tangentCode = s.WorldTangent is { } t ? t.Code : "vec3(0.0)";
                            string hasTangent = s.WorldTangent is not null ? "true" : "false";
                            shader.AppendLine($"    if (any(greaterThan({layer.Luminance.Code}, vec3(0.0)))) {{");
                            shader.AppendLine($"        emission += {layer.Luminance.Code} * lfx_translucent_slab_environment(effectPosition, {s.WorldNormal.Code}, {slabLit.View.Code}, clamp({s.Diffuse.Code}, 0.0, 1.0), clamp({s.F0.Code}, 0.0, 1.0), clamp({s.F90.Code}, 0.0, 1.0), clamp({s.Roughness.Code}, 0.0, 1.0), {s.AmbientOcclusion.Code}, {s.Anisotropy.Code}, {tangentCode}, {hasTangent}, {s.FuzzColor.Code}, {s.FuzzAmount.Code}, {s.FuzzRoughness.Code}, {layer.TransmittanceAbove.Code}, {layer.CoverageAbove.Code});");
                            shader.AppendLine("    }");
                        }
                        shader.AppendLine("#endif");
                    }
                }
            } else if (translucentLighting is { Mode: "surface_per_pixel", Surface: { } forward } surface) {
                string normal = surface.WorldNormal!.Code;
                string subsurface = forward.Subsurface is { } color ? $"vec4(clamp({color.Code}, 0.0, 1.0), opacity)" : "vec4(0.0)";
                string bent = forward.BentNormal is { } authoredBent ? $", {authoredBent.Code}, true" : string.Empty;
                string coat = shadingModel == "MSM_ClearCoat"
                    ? $", {forward.ClearCoat.Code}, {forward.ClearCoatRoughness.Code}, {forward.Metallic.Code}, {forward.BottomNormal?.Code ?? normal}"
                    : string.Empty;
                string diffuseArg = thinTranslucent is not null ? $"({surface.Diffuse.Code} * opacity)" : surface.Diffuse.Code;
                shader.AppendLine("#if LFX_FORWARD_TRANSLUCENCY && !LFX_PARTICLE_MOTION");
                shader.AppendLine($"    emission += lfx_forward_translucency(effectPosition, {normal}, {forward.View.Code}, {forward.BaseColor.Code}, {diffuseArg}, {forward.SpecularColor.Code}, {forward.Roughness.Code}, {subsurface}, {forward.AmbientOcclusion.Code}{bent}{coat});");
                shader.AppendLine("#endif");
            } else if (translucentLighting is { } lit) {
                bool spriteOffset = spriteCoordinates && particleRandomUsed && (lit.Mode is "pixel" or "surface_volume")
                    && (int)library.MaterialPermutation.EffectiveFeatureLevel >= (int)MaterialFeatureInput.Sm5;
                string voxelOffset = spriteOffset
                    ? "lfx_tlv_sprite_offset(effectParticle.y)" : "vec3(0.0)";
                shader.AppendLine("#if LFX_TRANSLUCENCY_LIGHTING && !LFX_PARTICLE_MOTION");
                string baseDiffuse = thinTranslucent is not null ? $"({lit.Diffuse.Code} * opacity)" : lit.Diffuse.Code;
                string indirectColor = lit.Subsurface is { } subsurface
                    ? $"({baseDiffuse} + clamp({subsurface.Code}, 0.0, 1.0))" : baseDiffuse;
                string volumeDiffuse = shadingModel == "MSM_ClearCoat" ? "vec3(0.0)" : baseDiffuse;
                string indirectBent = lit.BentNormal is { } authoredIndirectBent ? $"{authoredIndirectBent.Code}, true" : $"{lit.SkyNormal.Code}, false";
                shader.AppendLine($"    emission += lfx_tlv_diffuse(effectPosition, {lit.WorldNormal?.Code ?? "vec3(0.0)"}, {volumeDiffuse}, {Number(lit.DirectionalIntensity)}, {(lit.WorldNormal is not null ? "true" : "false")}, {voxelOffset}, {indirectColor}, {lit.BaseColor.Code}, {lit.AmbientOcclusion.Code}, {lit.SkyNormal.Code}, {indirectBent}, {lit.View.Code});");
                shader.AppendLine("#endif");
                if (lit.Surface is { } volumeSurface) {
                    string normal = lit.WorldNormal!.Code;
                    string volumeSubsurface = volumeSurface.Subsurface is { } color ? $"clamp({color.Code}, 0.0, 1.0)" : "vec3(0.0)";
                    string bent = volumeSurface.BentNormal is { } authoredBent ? $"{authoredBent.Code}, true" : $"{normal}, false";
                    string surfaceDiffuse = thinTranslucent is not null ? $"({lit.Diffuse.Code} * opacity)" : lit.Diffuse.Code;
                    shader.AppendLine("#if LFX_SURFACE_VOLUME_ENVIRONMENT && !LFX_PARTICLE_MOTION");
                    shader.AppendLine($"    emission += lfx_translucent_surface_environment(effectPosition, {normal}, {volumeSurface.View.Code}, {volumeSurface.BaseColor.Code}, {surfaceDiffuse}, {volumeSurface.SpecularColor.Code}, {volumeSurface.Roughness.Code}, {volumeSubsurface}, {volumeSurface.AmbientOcclusion.Code}, {bent});");
                    shader.AppendLine("#endif");
                }
            }
            if (mode == "BLEND_Masked") {
                if (naniteCoordinates) {
                    shader.AppendLine("#if LFX_NANITE_RASTER_MATERIAL");
                    shader.AppendLine($"    if (!({naniteMask} >= 0.0)) discard;");
                    shader.AppendLine("#elif !LFX_NANITE_MATERIAL");
                }
                shader.AppendLine("#if LFX_PARTICLE_POINT_SHADOW || LFX_PARTICLE_CUSTOM_DEPTH");
                shader.AppendLine($"    if (rawOpacity < {opacityClip}) discard;");
                shader.AppendLine("#else");
                shader.AppendLine($"    if (opacity < {opacityClip}) discard;");
                shader.AppendLine("#endif");
                if (naniteCoordinates) shader.AppendLine("#endif");
            }
            if (translucent) {
                if (castAsMasked || translucentCustomDepth) {
                    string depthCoverage = castAsMasked && translucentCustomDepth ? "LFX_PARTICLE_POINT_SHADOW || LFX_PARTICLE_CUSTOM_DEPTH"
                        : castAsMasked ? "LFX_PARTICLE_POINT_SHADOW" : "LFX_PARTICLE_CUSTOM_DEPTH";
                    shader.AppendLine($"#if {depthCoverage}");
                    shader.AppendLine($"    if (opacity < {opacityClip}) discard;");
                    shader.AppendLine("#elif LFX_PARTICLE_MOTION");
                } else shader.AppendLine("#if LFX_PARTICLE_MOTION");
                shader.AppendLine("#if LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY");
                shader.AppendLine($"    lfxMotionVelocityEligible = (opacity - 1.0 / 255.0 - {opacityClip}) >= 0.0;");
                shader.AppendLine("#else");
                shader.AppendLine($"    if (opacity - 1.0 / 255.0 - {opacityClip} < 0.0) discard;");
                shader.AppendLine("#endif");
                shader.AppendLine("#endif");
            }
            if (unlitSubstrate is not null) AppendUnlitSubstrateColor(shader, unlitSubstrate, mode);
            else if (translucentSlabModel is not null) AppendTranslucentSlabColor(shader, translucentSlabModel, mode);
            else if (thinTranslucent is not null) AppendThinTranslucentColor(shader, thinTranslucent, translucentLighting is not null);
            else if (modulate) {
                shader.AppendLine("    float fogTransmission = lfx_material_fog().a;");
                shader.AppendLine("    fragColor = vec4(mix(vec3(1.0), emission, fogTransmission * fogTransmission), opacity);");
            }
            else {
                if (mode is "BLEND_Opaque" or "BLEND_Masked") shader.AppendLine("    opacity = 1.0;");
                if (substrate is SlabSubstrate or HairSubstrate or EyeSubstrate or ToonSubstrate)
                    shader.AppendLine($"    emission = max(emission, vec3(0.0)) * {substrate!.Coverage.Code};");
                shader.AppendLine("    fragColor = lfx_apply_material_fog(vec4(emission, opacity));");
                if (defaultLit is not null) AppendDefaultLitSurfaceOutput(shader, defaultLit);
                if (adaptiveSubstrate is not null) AppendFirstVisibleSlabSurface(shader, adaptiveSubstrate, slabSubstrate!);
                else if (blendableSlabProfile is { DynamicProfile: { } profile }) {
                    shader.AppendLine("#if LFX_PARTICLE_SURFACE && !LFX_PARTICLE_MOTION");
                    shader.AppendLine($"    if ({profile.Code} >= 0.0) {{");
                    AppendDefaultLitSurfaceOutput(shader, blendableSlabProfile);
                    shader.AppendLine("    } else {");
                    AppendSlabSubstrateSurface(shader, slabSubstrate!);
                    shader.AppendLine("    }");
                    shader.AppendLine("#endif");
                }
                else if (slabSubstrate is not null) AppendSlabSubstrateSurface(shader, slabSubstrate);
                if (adaptiveSubstrate is not null) AppendSlabClosurePayload(shader, adaptiveSubstrate, slabSubstrate!.DecalResponse);
            }
        }
        AppendVirtualTextureFeedback(shader);
        if (naniteSoftwareRaster) shader.AppendLine("#endif");
        shader.AppendLine("}");
        string fragmentSource = shader.ToString();
        return new CompiledMaterial(fragmentSource, CompiledTextureBindings(),
            new Dictionary<string, TextureSettings>(textureSettings, StringComparer.Ordinal), blend, cull,
            material.Properties.Value<bool?>("bDisableDepthTest") != true && sourcePass != "MTP_AfterMotionBlur",
            dynamicDefaults ?? new[] { 1d, 1d, 1d, 1d }, applyFogging, fogPerPixel, translucencyPass, dynamicSlots,
            ribbonParticleData, depthProjectionUsed, sceneDepthUsed, macroUVUsed) {
                SourceObject = sourceObject,
                // UE accepts both Substrate transmittance modes; legacy PostLoad converts Colored to Translucent.
                AutomaticSpriteCoverageChannels = mode switch {
                    "BLEND_Translucent" or "BLEND_TranslucentGreyTransmittance" or "BLEND_TranslucentColoredTransmittance" => 8,
                    "BLEND_Additive" => 15,
                    _ => 0
                },
                IsSky = compiledMaterialProperties.Value<bool?>("bIsSky") ?? false,
                NaniteSource = naniteCoordinates ? new CompiledNaniteMaterialSource(domain, mode, shadingModel,
                    (material.Properties["ShadingModels"] as JObject)?.Value<int?>("ShadingModelField"),
                    material.Properties.Value<bool?>("bUsedWithNanite") ?? false,
                    material.Properties.Value<bool?>("bAutomaticallySetUsageInEditor") ?? true,
                    material.Properties.Value<bool?>("bIsSky") ?? false, !cull,
                    maskClipOverride ?? SourceAsset.Number(material.Properties["OpacityMaskClipValue"], .3333),
                    pixelDepthOffset is not null, naniteDitherMask) : null,
                NaniteSoftwareRaster = naniteSoftwareRaster ? CompileNaniteSoftwareRaster(fragmentSource) : null,
                TranslucencyVolume = translucentLighting?.Mode ?? "none",
                HighQualityForwardReflections = compiledMaterialProperties.Value<bool?>("bUseHQForwardReflections") == true,
                WriteTranslucentVelocity = outputTranslucentVelocity,
                VelocityFromDepth = material.Properties.Value<bool?>("bIsTranslucencyVelocityFromDepth") ?? false,
                DBufferTextures = dbufferTextures, SceneColor = sceneColorUsed && translucent, MaterialView = materialViewUsed,
                DefaultLitSurface = !translucent && (defaultLit is not null || slabSubstrate is not null),
                ParticleLightingFeatures = translucent ? 0 : adaptiveSubstrate?.LightingFeatures ?? (slabSubstrate is not null ? 1 | (slabSubstrate.FuzzAmount.ZeroConstant ? 0 : 2)
                    | (slabSubstrate.Wrap is null ? 0 : 128) | (slabSubstrate.SpecularProfile is null ? 0 : 256)
                    | (blendableSlabProfile is null ? 0 : 16)
                    : defaultLit?.ShadingModel switch {
                        3 => 4, 5 or 9 => 16, 7 => 8, SubstrateHairSurfaceModel => 32, SubstrateEyeSurfaceModel => 16 | 64,
                        SubstrateToonSurfaceModel => 2048, _ => 0
                    }),
                SubsurfaceProfile = eyeSubstrate is not null ? eyeSubstrate.Profile ?? CompileSubsurfaceProfile(asset, material)
                    : defaultLit?.ShadingModel is 5 or 9 ? CompileSubsurfaceProfile(asset, material) : null,
                SlabSubsurfaceProfiles = slabDiffusionProfiles.OrderBy(entry => entry.Value).Select(entry => entry.Key).ToArray(),
                SpecularProfiles = SpecularProfileSources(),
                ToonProfiles = ToonProfileSources(),
                AdaptiveSubstrateWords = translucent ? 0 : adaptiveSubstrate?.Words ?? 0,
                SubstratePunctualVolume = !translucent && (slabSubstrate?.UsesVolumeLookup == true || adaptiveSubstrate?.VolumeLookup == true),
                AnisotropicSurface = !translucent && (defaultLit?.WorldTangent is not null || slabSubstrate?.WorldTangent is not null
                    || adaptiveSubstrate?.Anisotropy == true),
                CustomSurfaceData = !translucent && (defaultLit?.ShadingModel is > 1 || slabSubstrate is not null),
                PointShadowCoverage = shadowCoverage,
                TranslucentCustomDepth = translucentCustomDepth,
                CustomSceneTextures = customSceneTextures,
                DeferredSceneTextures = deferredSceneTextures,
                VertexMaterial = vertexMaterial, RuntimeParameters = runtimeParameters.ToArray(), RuntimeTextures = CompiledRuntimeTextures(),
                TextureSamplerSources = CompiledTextureSamplerSources(),
                FontSamplers = fontSamplers.Values.ToArray(),
                TextureParameterSamplers = CompiledTextureParameterSamplers(),
                FontAssets = new Dictionary<string, IReadOnlyDictionary<int, string>>(fontAssets, SourceNameOrder.Instance),
                MaterialCollections = materialCollections.ToArray(),
                RuntimeVirtualTextureOutputs = virtualTextureOutput?.AttributeMask,
                VirtualTextureReads = virtualTextureReads.Values.ToArray(),
                VirtualTextureFeedback = virtualTextureFeedbackStacks.Count != 0,
                RenderTargetTextures = new HashSet<string>(renderTargetTextures, StringComparer.Ordinal) };
    }

    private void AppendSampler(StringBuilder shader, string sampler)
    {
        var shape = TextureShape(sampler);
        string uv = Type(TextureCoordinateWidth(shape));
        string grad = Type(TextureGradientWidth(shape));
        string sample = volumeCoordinates ? $"textureLod({sampler}, uv, 0.0)" : $"texture({sampler}, uv)";
        shader.AppendLine($"vec4 lfx_sample_{sampler}({uv} uv) {{ return {sample}; }}");
        if (naniteCoordinates) {
            shader.AppendLine("#if LFX_NANITE_MATERIAL");
            shader.AppendLine($"vec4 lfx_sample_grad_{sampler}({uv} uv, {grad} dx, {grad} dy, float bias) {{ float scale = exp2(bias); return textureGrad({sampler}, uv, dx * scale, dy * scale); }}");
            shader.AppendLine("#else");
            shader.AppendLine($"vec4 lfx_sample_grad_{sampler}({uv} uv, {grad} dx, {grad} dy, float bias) {{ return texture({sampler}, uv, bias); }}");
            shader.AppendLine("#endif");
        }
    }

    private Expr Node(Context context, int index, int output = 0)
    {
        var key = (context.Id, index, output);
        if (cache.TryGetValue(key, out var previous)) return previous;
        if (!visiting.Add(key)) throw new ConversionException($"Cyclic material graph at {context.Asset.Name}:{index}");
        var node = context.Asset.Object(index);
        var p = node.Properties;
        if (compilingVertex && node.ClassName is "MaterialExpressionDDX" or "MaterialExpressionDDY"
                or "MaterialExpressionSceneColor" or "MaterialExpressionDBufferTexture"
                or "MaterialExpressionParticleMacroUV" or "MaterialExpressionSphericalParticleOpacity"
                or "MaterialExpressionAntialiasedTextureMask" or "MaterialExpressionParticleSubUV"
                or "MaterialExpressionTextureSampleParameterSubUV")
            throw new ConversionException($"{node.ClassName} requires the pixel shader stage");
        Expr result = node.ClassName switch
        {
            "MaterialExpressionAdd" => Binary(context, p, "+", 0, 1),
            "MaterialExpressionSubtract" => Binary(context, p, "-", 1, 1),
            "MaterialExpressionMultiply" => Binary(context, p, "*", 0, 1),
            "MaterialExpressionDivide" => Binary(context, p, "/", 1, 2),
            "MaterialExpressionPower" => Power(context, p),
            "MaterialExpressionDotProduct" => Dot(context, p),
            "MaterialExpressionAppendVector" => Append(context, p),
            "MaterialExpressionLinearInterpolate" => Lerp(context, p),
            "MaterialExpressionInverseLinearInterpolate" => InverseLerp(context, p),
            "MaterialExpressionConstantBiasScale" => BiasScale(context, p),
            "MaterialExpressionSaturate" => Unary(context, p, MaterialSaturate),
            "MaterialExpressionOneMinus" => Unary(context, p, value => Arithmetic(Constant(1), value, "-")),
            "MaterialExpressionAbs" => Unary(context, p, MaterialAbs),
            "MaterialExpressionCeil" => Unary(context, p, value => ZeroGradient(PrecisionUnary(value, "ceil"))),
            "MaterialExpressionFloor" => Unary(context, p, value => ZeroGradient(PrecisionUnary(value, "floor"))),
            "MaterialExpressionFrac" => MaterialFraction(RequiredInput(context, p, "Input")),
            "MaterialExpressionRound" => ZeroGradient(RequiredUnary(context, p, "Input", "roundEven")),
            "MaterialExpressionTruncate" => ZeroGradient(RequiredUnary(context, p, "Input", "trunc")),
            "MaterialExpressionTruncateLWC" => TruncateLargeWorld(RequiredInput(context, p, "Input")),
            "MaterialExpressionFloatToUInt" => FloatToUInt(context, p),
            "MaterialExpressionUIntToFloat" => UIntToFloat(context, p),
            "MaterialExpressionSign" => ZeroGradient(RequiredUnary(context, p, "Input", "sign")),
            "MaterialExpressionSquareRoot" => naniteCoordinates ? DerivativeSqrt(context, p) : RequiredUnary(context, p, "Input", "sqrt"),
            "MaterialExpressionExponential" => naniteCoordinates ? DerivativeExp(context, p, false) : RequiredUnary(context, p, "Input", "exp"),
            "MaterialExpressionExponential2" => naniteCoordinates ? DerivativeExp(context, p, true) : RequiredUnary(context, p, "Input", "exp2"),
            "MaterialExpressionArcsine" => naniteCoordinates ? DerivativeInverseTrig(context, p, "asin") : RequiredUnary(context, p, "Input", "asin"),
            "MaterialExpressionArccosine" => naniteCoordinates ? DerivativeInverseTrig(context, p, "acos", -1) : RequiredUnary(context, p, "Input", "acos"),
            "MaterialExpressionArctangent" => naniteCoordinates ? DerivativeAtan(context, p) : RequiredUnary(context, p, "Input", "atan"),
            "MaterialExpressionArcsineFast" => DerivativeInverseTrig(context, p, "asin", fast: true),
            "MaterialExpressionArccosineFast" => DerivativeInverseTrig(context, p, "acos", -1, fast: true),
            "MaterialExpressionArctangentFast" => DerivativeAtan(context, p, fast: true),
            "MaterialExpressionLogarithm" => naniteCoordinates ? DerivativeLog(context, p, "Input", "log", 1) : RequiredUnary(context, p, "Input", "log"),
            "MaterialExpressionLogarithm2" => naniteCoordinates ? DerivativeLog(context, p, "X", "log2", 1.442695) : RequiredUnary(context, p, "X", "log2"),
            "MaterialExpressionLogarithm10" => AnalyticDerivatives ? DerivativeLog10(context, p) : Logarithm10(RequiredInput(context, p, "X")),
            "MaterialExpressionNormalize" => MaterialNormalize(RequiredInput(context, p, "VectorInput")),
            "MaterialExpressionLength" => MaterialLength(RequiredInput(context, p, "Input")),
            "MaterialExpressionCrossProduct" => Cross(context, p),
            "MaterialExpressionDistance" => Distance(context, p),
            "MaterialExpressionSphereMask" => SphereMask(context, p),
            "MaterialExpressionArctangent2" => naniteCoordinates ? DerivativeAtan2(context, p) : RequiredBinary(context, p, "Y", "X", "atan"),
            "MaterialExpressionArctangent2Fast" => DerivativeAtan2(context, p, fast: true),
            "MaterialExpressionFmod" => RequiredBinary(context, p, "A", "B", "lfx_fmod"),
            "MaterialExpressionModulo" => MaterialModulo(context, p),
            "MaterialExpressionOperator" => MirOperator(context, p),
            "MaterialExpressionSmoothStep" => SmoothStep(context, p),
            "MaterialExpressionStep" => Step(context, p),
            "MaterialExpressionIf" => Conditional(context, p),
            "MaterialExpressionIfThenElse" => IfThenElse(context, p),
            "MaterialExpressionConvert" => ConvertNode(context, node, p, output),
            "MaterialExpressionColorRamp" => ColorRamp(context, node, p),
            "MaterialExpressionMin" => Extremum(context, p, "min"),
            "MaterialExpressionMax" => Extremum(context, p, "max"),
            "MaterialExpressionDeriveNormalZ" => DeriveNormalZ(context, p),
            "MaterialExpressionDDX" => MaterialScreenDerivative(context, p, true),
            "MaterialExpressionDDY" => MaterialScreenDerivative(context, p, false),
            "MaterialExpressionDesaturation" => Desaturate(context, p),
            "MaterialExpressionHsvToRgb" => ColorSpaceConvert(context, p, rgbToHsv: false),
            "MaterialExpressionRgbToHsv" => ColorSpaceConvert(context, p, rgbToHsv: true),
            "MaterialExpressionChannelMaskParameter" => ChannelMaskParameter(context, p),
            "MaterialExpressionLocalPosition" => LocalPosition(p),
            "MaterialExpressionDistanceCullFade" => Constant(1),
            "MaterialExpressionPerInstanceFadeAmount" => Constant(1),
            "MaterialExpressionPerInstanceRandom" => Constant(0),
            "MaterialExpressionClamp" => Clamp(context, p),
            "MaterialExpressionSine" => naniteCoordinates ? DerivativeTrigonometric(context, p, "sin") : Trigonometric(context, p, "sin"),
            "MaterialExpressionCosine" => naniteCoordinates ? DerivativeTrigonometric(context, p, "cos") : Trigonometric(context, p, "cos"),
            "MaterialExpressionTangent" => naniteCoordinates ? DerivativeTrigonometric(context, p, "tan") : Trigonometric(context, p, "tan"),
            "MaterialExpressionStaticBool" => StaticBool(p.Value<bool?>("Value") == true),
            "MaterialExpressionStaticBoolParameter" => StaticBool(StaticBoolValue(context, p)),
            "MaterialExpressionStaticSwitchParameter" => StaticSwitchParameter(context, p),
            "MaterialExpressionStaticSwitch" => Switch(context, p),
            "MaterialExpressionSwitch" => CompileSwitchNode(context, node, p),
            "MaterialExpressionNaniteReplace" => NaniteReplace(context, p),
            "MaterialExpressionShadowReplace" => ShadowReplace(context, p),
            "MaterialExpressionComponentMask" => MaskNode(context, p),
            "MaterialExpressionStaticComponentMaskParameter" => StaticComponentMask(context, p),
            "MaterialExpressionReroute" or "MaterialExpressionNamedRerouteDeclaration" or "MaterialExpressionNamedRerouteUsage"
                => Input(context, RerouteInput(context, node),
                    () => throw new ConversionException($"Missing reroute input in {context.Asset.Name}:{node.Name}")),
            "MaterialExpressionVertexInterpolator" => VertexInterpolator(context, node, output),
            "MaterialExpressionShadingModel" when output == 0 => SourceShadingModel(p["ShadingModel"]),
            "MaterialExpressionConstant" => Constant(SourceAsset.Number(p["R"])),
            "MaterialExpressionConstantDouble" => ConstantDouble(p),
            "MaterialExpressionConstant2Vector" => Vector(new[] { SourceAsset.Number(p["R"]), SourceAsset.Number(p["G"]) }),
            "MaterialExpressionConstant3Vector" => Vector(Color(p["Constant"], 3)),
            "MaterialExpressionConstant4Vector" => Vector(Color(p["Constant"], 4)),
            "MaterialExpressionScalarParameter" => ScalarParameter(context, p),
            "MaterialExpressionSubstrateToonProfileParameter" when output == 0 => CompileToonProfileParameter(context, p),
            "MaterialExpressionSubstrateSelectToonProfile" when output == 0 => SelectToonProfile(context, p),
            "MaterialExpressionVectorParameter" => VectorParameter(context, p, output),
            "MaterialExpressionCurveAtlasRowParameter" => CurveAtlasRowParameter(context, p, output),
            "MaterialExpressionDoubleVectorParameter" => DoubleVectorParameter(context, p, output),
            "MaterialExpressionCollectionParameter" => CollectionParameter(context, p),
            "MaterialExpressionCollectionTransform" => CollectionTransform(context, p),
            "MaterialExpressionTextureObjectParameter" or "MaterialExpressionTextureObject" => Texture(context, p, DefaultMaterialTexture2D),
            "MaterialExpressionFontSample" or "MaterialExpressionFontSampleParameter" => FontSample(context, node, output),
            "MaterialExpressionSparseVolumeTextureObject" or "MaterialExpressionSparseVolumeTextureObjectParameter" => SparseVolumeTexture(context, p),
            "MaterialExpressionSparseVolumeTextureSample" or "MaterialExpressionSparseVolumeTextureSampleParameter" => SampleSparseVolume(context, p, output),
            "MaterialExpressionTextureProperty" => TextureProperty(context, p),
            "MaterialExpressionTextureSample" => SampleTexture(context, p, output),
            "MaterialExpressionTextureSampleParameter2D" => SampleTexture(context, p, output, MaterialTextureShape.Texture2D),
            "MaterialExpressionTextureSampleParameterVolume" => SampleTexture(context, p, output, MaterialTextureShape.Volume),
            "MaterialExpressionTextureSampleParameter2DArray" => SampleTexture(context, p, output, MaterialTextureShape.Array2D),
            "MaterialExpressionTextureSampleParameterCube" => SampleTexture(context, p, output, MaterialTextureShape.Cube),
            "MaterialExpressionTextureSampleParameterCubeArray" => SampleTexture(context, p, output, MaterialTextureShape.CubeArray),
            "MaterialExpressionAntialiasedTextureMask" => AntialiasedTextureMask(context, p),
            "MaterialExpressionTextureSampleParameterSubUV" => SampleSubUV(context, p, output, DefaultMaterialTexture2D),
            "MaterialExpressionParticleSubUV" => SampleSubUV(context, p, output),
            "MaterialExpressionParticleSubUVProperties" => SubUVProperties(output),
            "MaterialExpressionTextureCoordinate" => Coordinates(p, explicitNode: true),
            "MaterialExpressionPanner" => Panner(context, p),
            "MaterialExpressionRotator" => Rotator(context, p),
            "MaterialExpressionBumpOffset" => BumpOffset(context, p),
            "MaterialExpressionNoise" => MaterialNoise(context, p, vector: false),
            "MaterialExpressionVectorNoise" => MaterialNoise(context, p, vector: true),
            "MaterialExpressionScalarBlueNoise" => ScalarBlueNoise(),
            "MaterialExpressionBlackBody" => BlackBody(context, p),
            "MaterialExpressionParticleColor" => output switch {
                0 => new Expr("effectColor.rgb", 3),
                1 => new Expr("effectColor.r", 1), 2 => new Expr("effectColor.g", 1),
                3 => new Expr("effectColor.b", 1), 4 => new Expr("effectColor.a", 1),
                5 => new Expr("effectColor", 4),
                _ => throw new ConversionException("Invalid particle color output") },
            "MaterialExpressionDecalColor" => DecalColor(output),
            "MaterialExpressionDecalLifetimeOpacity" => decalCoordinates ? new Expr("DecalFadeParameters.y", 1) : Constant(0),
            "MaterialExpressionDecalDerivative" => DecalDerivative(output),
            "MaterialExpressionDecalMipmapLevel" => DecalMipmapLevel(context, p),
            "MaterialExpressionGetMaterialAttributes" => GetAttribute(context, p, output),
            "MaterialExpressionBreakMaterialAttributes" => BreakAttribute(context, p, output),
            "MaterialExpressionVertexColor" => VertexColor(output),
            "MaterialExpressionVertexNormalWS" => VertexBasis(false),
            "MaterialExpressionVertexTangentWS" => VertexBasis(true),
            "MaterialExpressionPixelNormalWS" => PixelWorldNormal(),
            "MaterialExpressionReflectionVectorWS" => ReflectionVector(context, p),
            "MaterialExpressionFresnel" => Fresnel(context, p),
            "MaterialExpressionTwoSidedSign" => RasterTwoSidedSign(),
            "MaterialExpressionPerInstanceCustomData" => PerInstanceCustomData(context, p, false),
            "MaterialExpressionPerInstanceCustomData3Vector" => PerInstanceCustomData(context, p, true),
            "MaterialExpressionPreSkinnedPosition" => PreSkinnedInput(false, output),
            "MaterialExpressionPreSkinnedNormal" => PreSkinnedInput(true, output),
            "MaterialExpressionCameraVectorWS" => CameraVector(),
            "MaterialExpressionSubstrateTransmittanceToMFP" => SubstrateTransmittanceToMfp(context, p, output),
            "MaterialExpressionSubstrateMetalnessToDiffuseAlbedoF0" => SubstrateMetalness(context, p, output),
            "MaterialExpressionSubstrateHazinessToSecondaryRoughness" => SubstrateHaziness(context, p, output),
            "MaterialExpressionSubstrateThinFilm" => SubstrateThinFilm(context, p, output),
            "MaterialExpressionLightVector" => LightVector(),
            "MaterialExpressionAtmosphericLightVector" => AtmosphericLightVector(),
            "MaterialExpressionAtmosphericLightColor" => AtmosphericLightColor(),
            "MaterialExpressionSkyAtmosphereLightDirection" => SkyAtmosphereLightDirection(p),
            "MaterialExpressionSkyAtmosphereLightIlluminanceOnGround" => SkyAtmosphereLightIlluminanceOnGround(p),
            "MaterialExpressionSkyAtmosphereLightIlluminance" => SkyAtmosphereLightIlluminance(context, p),
            "MaterialExpressionSkyAtmosphereLightDiskLuminance" => SkyAtmosphereLightDiskLuminance(context, p),
            "MaterialExpressionSkyAtmosphereViewLuminance" => SkyAtmosphereViewLuminance(context, p),
            "MaterialExpressionSkyAtmosphereAerialPerspective" => SkyAtmosphereAerialPerspective(context, p),
            "MaterialExpressionSkyAtmosphereDistantLightScatteredLuminance" => SkyAtmosphereDistantLightScatteredLuminance(),
            "MaterialExpressionCameraPositionWS" => WorldCameraPosition(),
            "MaterialExpressionObjectPositionWS" => ObjectMaterialPosition(p),
            "MaterialExpressionObjectOrientation" => ObjectMaterialOrientation(),
            "MaterialExpressionActorPositionWS" => ActorMaterialPosition(p),
            "MaterialExpressionObjectBounds" => ObjectMaterialExtent(false),
            "MaterialExpressionObjectRadius" => ObjectMaterialExtent(true),
            "MaterialExpressionObjectLocalBounds" => ObjectMaterialLocalBounds(output),
            "MaterialExpressionBounds" => MaterialBounds(p, output),
            "MaterialExpressionPreSkinnedLocalBounds" => PreSkinnedMaterialLocalBounds(output),
            "MaterialExpressionDepthFade" => DepthFade(context, p),
            "MaterialExpressionPixelDepth" => PixelDepth(),
            "MaterialExpressionSceneDepth" => SceneDepth(context, p),
            "MaterialExpressionSceneTexture" => decalCoordinates ? DecalSceneTexture(context, p, output) : SurfaceSceneTexture(context, p, output),
            "MaterialExpressionSceneColor" => SceneColor(context, p, output),
            "MaterialExpressionDBufferTexture" => DBufferTexture(context, p, output),
            "MaterialExpressionScreenPosition" => ScreenPosition(output),
            "MaterialExpressionViewProperty" => MaterialViewProperty(p, output),
            "MaterialExpressionEyeAdaptation" => EyeAdaptation(),
            "MaterialExpressionEyeAdaptationInverse" => EyeAdaptationInverse(context, p),
            "MaterialExpressionPostVolumeUserFlagTest" => PostVolumeUserFlagTest(context, p),
            "MaterialExpressionViewSize" => ViewSize(false),
            "MaterialExpressionSceneTexelSize" => ViewSize(true),
            "MaterialExpressionDeltaTime" => new Expr("EffectTimeData.x", 1),
            "MaterialExpressionIsOrthographic" => IsOrthographic(),
            "MaterialExpressionIsFirstPerson" => IsFirstPerson(),
            "MaterialExpressionParticleRelativeTime" => new Expr("effectParticle.x", 1),
            "MaterialExpressionParticleRandom" => ParticleRandom(),
            "MaterialExpressionParticleDirection" => ParticleMaterial("direction"),
            "MaterialExpressionParticlePositionWS" => ParticlePosition(p),
            "MaterialExpressionParticleMacroUV" => ParticleMacroUV(),
            "MaterialExpressionWorldPosition" => WorldPosition(p),
            "MaterialExpressionRuntimeVirtualTextureSample" or "MaterialExpressionRuntimeVirtualTextureSampleParameter"
                => RuntimeVirtualTextureSample(context, node, output),
            "MaterialExpressionRuntimeVirtualTextureCustomData" => RuntimeVirtualTextureCustomData(),
            "MaterialExpressionTransform" => CoordinateTransform(context, p, false),
            "MaterialExpressionTransformPosition" => CoordinateTransform(context, p, true),
            "MaterialExpressionRotateAboutAxis" => RotateAboutAxis(context, p),
            "MaterialExpressionParticleSpriteRotation" => SpriteRotation(output),
            "MaterialExpressionParticleMotionBlurFade" => Constant(1),
            "MaterialExpressionParticleSpeed" => ParticleMaterial("speed"),
            "MaterialExpressionParticleSize" => ParticleMaterial("size"),
            "MaterialExpressionParticleRadius" => new Expr($"max({ParticleMaterial("radius").Code}, 0.001)", 1),
            "MaterialExpressionSphericalParticleOpacity" => SphericalParticleOpacity(context, p),
            "MaterialExpressionDynamicParameter" => DynamicParameter(p, output),
            "MaterialExpressionTime" => MaterialTime(p),
            "MaterialExpressionCustom" => Custom(context, node, output),
            "MaterialExpressionMaterialFunctionCall" => Function(context, node, output),
            "MaterialExpressionFunctionInput" => FunctionInput(context, node),
            "MaterialExpressionFunctionOutput" => RequiredInput(context, p, "A"),
            _ when TrySelectStaticSwitchInput(context, node, p, out var selected)
                => Input(context, selected,
                    () => throw new ConversionException($"Missing selected shader input in {context.Asset.Name}:{node.Name}")),
            _ => throw new ConversionException($"Unsupported material node {node.ClassName} in {context.Asset.Name}:{node.Name}")
        };
        visiting.Remove(key);
        if (!precision.LwcEnabled) result = DemoteLargeWorld(result);
        result = Shared(result);
        cache.Add(key, result);
        return result;
    }

    private Expr Input(Context context, JToken? token, Func<Expr> defaultValue)
    {
        if (token is not JObject input) return defaultValue();
        int expression = input.Value<int>("expression");
        Expr result;
        if (input.Value<bool?>("constant") == true) result = Literal(input["value"]!);
        else if (expression == 0) return defaultValue();
        else result = Node(context, expression, input.Value<int>("output"));
        if (input.Value<int>("mask") != 0)
        {
            string mask = string.Concat(new[] { "r", "g", "b", "a" }.Where(channel => input.Value<int>(channel) != 0));
            result = Swizzle(result, mask);
        }
        return result;
    }

    private Expr Binary(Context context, JObject p, string operation, double aDefault, double bDefault)
    {
        Expr a = Input(context, p["A"], () => Constant(SourceAsset.Number(p["ConstA"], aDefault)));
        Expr b = Input(context, p["B"], () => Constant(SourceAsset.Number(p["ConstB"], bDefault)));
        int width = ArithmeticWidth(a, b);
        if ((operation is "/" or "*") && IsLiteralZero(a) || operation == "*" && IsLiteralZero(b))
            return IsUnsignedArithmetic(a, b) ? ZeroGradient(CastUnsigned(Constant(0), width))
                : ArithmeticConstant(0, width, a.LargeWorld || b.LargeWorld);
        return Arithmetic(a, b, operation);
    }

    private Expr BiasScale(Context context, JObject p)
    {
        Expr input = RequiredInput(context, p, "Input");
        Expr bias = Constant(SourceAsset.Number(p["Bias"], 1));
        Expr scale = Constant(SourceAsset.Number(p["Scale"], .5));
        return Arithmetic(Arithmetic(bias, input, "+"), scale, "*");
    }

    private Expr Extremum(Context context, JObject p, string function)
    {
        Expr a = Input(context, p["A"], () => Constant(SourceAsset.Number(p["ConstA"], 0)));
        Expr b = Input(context, p["B"], () => Constant(SourceAsset.Number(p["ConstB"], 1)));
        return ExtremumValue(a, b, function);
    }

    private Expr DeriveNormalZ(Context context, JObject p)
    {
        Expr xy = Shared(DemoteLargeWorld(Cast(RequiredInput(context, p, "InXY"), 2)));
        Expr inner = MaterialSaturate(Arithmetic(Constant(1), Dot(xy, xy), "-"));
        return Append(xy, MaterialSquareRoot(inner));
    }

    private Expr InverseLerp(Context context, JObject p)
    {
        Expr a = Input(context, p["A"], () => Constant(SourceAsset.Number(p["ConstA"], 0)));
        Expr b = Input(context, p["B"], () => Constant(SourceAsset.Number(p["ConstB"], 1)));
        Expr value = Input(context, p["Value"], () => Constant(SourceAsset.Number(p["ConstValue"], 0)));
        if (value == a) return ArithmeticConstant(0, value.Width, value.LargeWorld);
        if (value == b) return ArithmeticConstant(1, value.Width, value.LargeWorld);
        if (a == b) throw new ConversionException("Inverse linear interpolation has equal endpoints");
        int width = ArithmeticWidth(ArithmeticWidth(value, a), ArithmeticWidth(b, a));
        Expr numerator = Arithmetic(Cast(value, width), Cast(a, width), "-");
        Expr denominator = Arithmetic(Cast(b, width), Cast(a, width), "-");
        Expr result = Arithmetic(numerator, denominator, "/");
        return p.Value<bool?>("bClampResult") == true
                ? ExtremumValue(ExtremumValue(result, Constant(0), "max"), Constant(1), "min")
                : result;
    }

    private Expr Unary(Context context, JObject p, Func<Expr, Expr> function) => function(RequiredInput(context, p, "Input"));

    private Expr RequiredInput(Context context, JObject p, string name) => Input(context, p[name],
        () => throw new ConversionException($"Missing material input {name} in {context.Asset.Name}"));

    private Expr RequiredUnary(Context context, JObject p, string input, string function)
    {
        Expr value = RequiredInput(context, p, input);
        return PrecisionUnary(value, function);
    }

    private static Expr Logarithm10(Expr value) => new($"(log2({value.Code}) * 0.3010299956639812)", value.Width);

    private Expr RequiredBinary(Context context, JObject p, string left, string right, string function)
    {
        Expr a = AsFloat(RequiredInput(context, p, left)), b = AsFloat(RequiredInput(context, p, right));
        int width = a.Width;
        Expr rightValue = function == "lfx_fmod" ? Coerce(b, width) : Cast(b, width);
        Expr result = function == "lfx_fmod" && a.LargeWorld
                ? new Expr($"lfx_lwc_fmod({a.RawCode}, {rightValue.Code})", width)
                : new Expr($"{function}({Cast(a, width).Code}, {rightValue.Code})", width);
        string zero = ZeroDerivative(width);
        return AnalyticDerivatives && function == "lfx_fmod" && rightValue.DerivativeX == zero && rightValue.DerivativeY == zero
                ? result with { DerivativeX = a.DerivativeX, DerivativeY = a.DerivativeY } : result;
    }

    private Expr Cross(Context context, JObject p)
    {
        Expr a = RequiredInput(context, p, "A"), b = RequiredInput(context, p, "B");
        if (UsesMirTranslation) return MirCross(a, b);
        int width = ArithmeticWidth(a, b);
        if (width < 3)
            throw new ConversionException("Material cross product requires compatible three-component inputs");
        if (IsLiteralZero(a) || IsLiteralZero(b)) return ArithmeticConstant(0, width, a.LargeWorld || b.LargeWorld);
        return CrossFloat(Cast(a, 3), Cast(b, 3));
    }

    private Expr CrossFloat(Expr a, Expr b)
    {
        Expr result = new($"cross({a.Code}, {b.Code})", 3);
        return AnalyticDerivatives ? WithBinaryGradient(result, a, b, (dx, dy) =>
                $"(({dx}).yzx * ({b.Code}).zxy + ({a.Code}).yzx * ({dy}).zxy - ({dx}).zxy * ({b.Code}).yzx - ({a.Code}).zxy * ({dy}).yzx)")
                : result;
    }

    private Expr Distance(Context context, JObject p) =>
        Distance(RequiredInput(context, p, "A"), RequiredInput(context, p, "B"));

    private Expr Distance(Expr a, Expr b)
    {
        int width = ArithmeticWidth(a, b);
        Expr delta = Arithmetic(Cast(a, width), Cast(b, width), "-");
        if (!delta.LargeWorld && width == 1) return MaterialAbs(delta);
        return MaterialLength(AnalyticDerivatives ? Shared(delta) : delta);
    }

    private Expr SphereMask(Context context, JObject properties)
    {
        Expr distance = Distance(context, properties);
        Expr one = Constant(1), epsilon = Constant(1e-5);
        Expr inverseRadius = Connected(properties["Radius"])
            ? Arithmetic(one, ExtremumValue(epsilon, RequiredInput(context, properties, "Radius"), "max"), "/")
            : Constant(1f / MathF.Max(1e-5f, (float)SourceAsset.Number(properties["AttenuationRadius"], 256)));
        Expr inverseHardness;
        if (Connected(properties["Hardness"]))
        {
            Expr softness = Arithmetic(one, RequiredInput(context, properties, "Hardness"), "-");
            inverseHardness = Arithmetic(one, ExtremumValue(softness, epsilon, "max"), "/");
        }
        else
        {
            float hardnessPercent = (float)SourceAsset.Number(properties["HardnessPercent"], 100);
            inverseHardness = Constant(1f / MathF.Max(1f - hardnessPercent * .01f, 1e-5f));
        }
        Expr remaining = Arithmetic(one, Arithmetic(distance, inverseRadius, "*"), "-");
        return MaterialSaturate(Arithmetic(remaining, inverseHardness, "*"));
    }

    private Expr SmoothStep(Context context, JObject p)
    {
        Expr a = Input(context, p["Min"], () => Constant(SourceAsset.Number(p["ConstMin"])));
        Expr b = Input(context, p["Max"], () => Constant(SourceAsset.Number(p["ConstMax"], 1)));
        Expr value = Input(context, p["Value"], () => Constant(SourceAsset.Number(p["ConstValue"])));
        int width = ArithmeticWidth(ArithmeticWidth(a, b), value.Width);
        bool largeWorld = a.LargeWorld || b.LargeWorld || value.LargeWorld;
        if (a == value) return ArithmeticConstant(0, width, largeWorld);
        if (b == value) return ArithmeticConstant(1, width, largeWorld);
        if (a == b) return ZeroGradient(largeWorld
                ? new Expr($"lfx_lwc_step({Promote(Cast(a, width))}, {Promote(Cast(value, width))})", width)
                : new Expr($"step({Cast(a, width).Code}, {Cast(value, width).Code})", width));
        if (largeWorld)
            return new Expr($"lfx_lwc_smoothstep({Promote(Cast(a, width))}, {Promote(Cast(b, width))}, {Promote(Cast(value, width))})", width);
        return new Expr($"lfx_smoothstep({Cast(a, width).Code}, {Cast(b, width).Code}, {Cast(value, width).Code})", width);
    }

    private Expr Step(Context context, JObject p)
    {
        Expr y = Input(context, p["Y"], () => Constant(SourceAsset.Number(p["ConstY"])));
        Expr x = Input(context, p["X"], () => Constant(SourceAsset.Number(p["ConstX"], 1)));
        int width = ArithmeticWidth(x, y);
        if (x == y) return ArithmeticConstant(1, width, x.LargeWorld || y.LargeWorld);
        return ZeroGradient(x.LargeWorld || y.LargeWorld
                ? new Expr($"lfx_lwc_step({Promote(Cast(y, width))}, {Promote(Cast(x, width))})", width)
                : new Expr($"step({Cast(y, width).Code}, {Cast(x, width).Code})", width));
    }

    private Expr Conditional(Context context, JObject p)
    {
        if (UsesMirTranslation) return MirMaterialIf(context, p);
        Expr a = RequiredInput(context, p, "A");
        Expr b = Input(context, p["B"], () => Constant(SourceAsset.Number(p["ConstB"])));
        Expr greater = RequiredInput(context, p, "AGreaterThanB"), less = RequiredInput(context, p, "ALessThanB");
        bool hasEqual = p["AEqualsB"] is JObject equalInput && (equalInput.Value<int>("expression") != 0 || equalInput.Value<bool?>("constant") == true);
        Expr? equal = hasEqual ? RequiredInput(context, p, "AEqualsB") : null;
        return ConditionalValue(a, b, greater, equal, less, SourceAsset.Number(p["EqualsThreshold"], .00001));
    }

    private Expr ConditionalValue(Expr a, Expr b, Expr greater, Expr? equal, Expr less, double threshold)
    {
        int width = Math.Max(greater.Width, Math.Max(less.Width, equal?.Width ?? 1));
        int compareWidth = Math.Max(a.Width, b.Width);
        if (compareWidth != 1 && compareWidth != width) throw new ConversionException("Incompatible material If comparison width");
        return PrecisionConditionalValue(a, b, greater, equal, less, threshold, width, compareWidth);
    }

    private Expr Power(Context context, JObject p) => Power(
        RequiredInput(context, p, "Base"),
        Input(context, p["Exponent"], () => Constant(SourceAsset.Number(p["ConstExponent"], 2))));

    private Expr Power(Expr a, Expr b)
    {
        int width = Math.Max(a.Width, b.Width);
        if (double.TryParse(b.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out double exponent))
        {
            if (exponent == 0) return Cast(Constant(1), width);
            if (exponent == 1) return Cast(a, width);
        }
        a = Cast(a, width); b = Cast(b, width);
        Expr result = new($"lfx_positive_pow({a.Code}, {b.Code})", width);
        if (!AnalyticDerivatives || a.LargeWorld || b.LargeWorld) return result;
        string zero = ZeroDerivative(width);
        string positiveExponent = width == 1 ? $"{b.Code} > 0.0" : $"greaterThan({b.Code}, {zero})";
        return WithBinaryGradient(result, a, b, (dx, dy) => SelectGradient(positiveExponent,
                $"({result.Code}) * (({dy}) * log({a.Code}) + (({b.Code}) / ({a.Code})) * ({dx}))", zero, width));
    }

    private Expr Dot(Context context, JObject p)
    {
        Expr a = RequiredInput(context, p, "A");
        Expr b = RequiredInput(context, p, "B");
        return UsesMirTranslation ? MirDot(a, b) : Dot(a, b);
    }

    private Expr Dot(Expr a, Expr b)
    {
        if (IsLiteralZero(a) || IsLiteralZero(b)) return ArithmeticConstant(0, 1, a.LargeWorld || b.LargeWorld);
        int width = ArithmeticWidth(a, b);
        a = Cast(a, width); b = Cast(b, width);
        Expr result = a.LargeWorld || b.LargeWorld
                ? new Expr($"lfx_lwc_dot({a.RawCode}, {b.RawCode})", 1, LargeWorld: true)
                : new Expr(DotExpression(a.Code, b.Code, width), 1);
        return AnalyticDerivatives ? WithBinaryGradient(result, a, b, (dx, dy) =>
                $"({DotExpression(dx, b.Code, width)} + {DotExpression(a.Code, dy, width)})") : result;
    }

    private Expr Append(Context context, JObject p)
    {
        Expr a = RequiredInput(context, p, "A");
        if (UsesMirTranslation && !Connected(p["B"])) return AsFloat(a);
        return Append(a, RequiredInput(context, p, "B"));
    }

    private Expr Append(Expr a, Expr b)
    {
        a = AsFloat(a); b = AsFloat(b);
        int width = a.Width + b.Width;
        if (width > 4) throw new ConversionException("Material append exceeds four components");
        Expr result = a.LargeWorld || b.LargeWorld ? LargeWorldAppend(a, b) : new Expr($"vec{width}({a.Code}, {b.Code})", width);
        return AnalyticDerivatives ? WithBinaryGradient(result, a, b, (dx, dy) => $"vec{width}({dx}, {dy})") : result;
    }

    private Expr Lerp(Context context, JObject p)
    {
        Expr a = Input(context, p["A"], () => Constant(SourceAsset.Number(p["ConstA"])));
        Expr b = Input(context, p["B"], () => Constant(SourceAsset.Number(p["ConstB"], 1)));
        Expr alpha = Input(context, p["Alpha"], () => Constant(SourceAsset.Number(p["ConstAlpha"], .5)));
        if (UsesMirTranslation) return MirTernary("Lerp", a, b, alpha);
        if (a == b) return a;
        int width = ArithmeticWidth(a, b);
        if (alpha.Sampler) throw new ConversionException("Material interpolation alpha must be numeric");
        if (alpha.Width == 1 && double.TryParse(alpha.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out double blend)) {
            if (blend == 0) return ArithmeticResult(a, width, a.LargeWorld || b.LargeWorld);
            if (blend == 1) return ArithmeticResult(b, width, a.LargeWorld || b.LargeWorld);
        }
        return Interpolate(a, b, alpha);
    }

    private Expr MaskNode(Context context, JObject p)
    {
        Expr input = RequiredInput(context, p, "Input");
        string mask = string.Concat(new[] { "R", "G", "B", "A" }.Where(channel => p.Value<bool?>(channel) == true)).ToLowerInvariant();
        return Swizzle(input, mask);
    }

    private Expr ScalarParameter(Context context, JObject p)
    {
        if (p.Value<bool?>("bUseCustomPrimitiveData") == true) return PrimitiveCustomMaterialParameter(p, 1);
        string name = p.Value<string>("ParameterName") ?? "Param";
        double value = SourceAsset.Number(ParameterValue(context, InstanceParameterType.Scalar, name) ?? p["DefaultValue"]);
        return RuntimeParameter(context, name, "scalar", new[] { value }, Constant(value) with { ParameterLiteral = true });
    }

    private Expr DepthFade(Context context, JObject properties)
    {
        Expr opacity = Input(context, properties["InOpacity"], () => Constant(SourceAsset.Number(properties["OpacityDefault"], 1)));
        Expr distance = Input(context, properties["FadeDistance"], () => Constant(SourceAsset.Number(properties["FadeDistanceDefault"], 100)));
        int width = Math.Max(opacity.Width, distance.Width);
        string fadeDistance = $"max({Cast(distance, width).Code}, {Cast(Constant(.00001), width).Code})";
        Expr scene = SceneDepth(context, new JObject());
        Expr pixel = PixelDepth();
        string depthDifference = Cast(Arithmetic(scene, pixel, "-"), width).Code;
        return new Expr($"({Cast(opacity, width).Code} * clamp({depthDifference} / {fadeDistance}, 0.0, 1.0))", width);
    }

    private Expr PixelDepth()
    {
        if (volumeCoordinates) return new Expr("lfx_volume_material_pixel_depth()", 1);
        if (compilingVertex) {
            vertexPixelDepthUsed = true;
            return new Expr("lfx_vertex_material_pixel_depth()", 1);
        }
        depthProjectionUsed = true;
        return new Expr("lfx_pixel_depth()", 1);
    }

    private Expr SphericalParticleOpacity(Context context, JObject properties)
    {
        Expr density = Input(context, properties["Density"], () => Constant(SourceAsset.Number(properties["ConstantDensity"], 1)));
        Expr radius = ParticleMaterial("radius");
        sphericalOpacityUsed = true;
        depthProjectionUsed = true;
        sceneDepthUsed = true;
        return new Expr($"lfx_spherical_opacity({Cast(density, 1).Code}, effectParticleCenter, {radius.Code}, effectPosition.xzy * 100.0)", 1);
    }

    private Expr ScreenPosition(int output)
    {
        if (output is < 0 or > 1) throw new ConversionException($"Invalid ScreenPosition output {output}");
        if (volumeCoordinates) return Vector([0, 0]);
        if (compilingVertex) {
            vertexSceneViewportUsed = true;
            return new Expr(output == 1
                    ? "(lfx_vertex_scene_viewport_uv() / vec2(Geometry.w, CameraUp.w))"
                    : "lfx_vertex_scene_viewport_uv()", 2);
        }
        screenPositionUsed = true;
        return new Expr(output == 1 ? "(lfx_depth_viewport_uv() / vec2(Geometry.w, CameraUp.w))" : "lfx_depth_viewport_uv()", 2);
    }

    private Expr ParticleRandom()
    {
        particleRandomUsed = true;
        return new Expr("effectParticle.y", 1);
    }

    private Expr ParticleMacroUV()
    {
        if (!spriteCoordinates) return new Expr("vec2(0.5)", 2);
        macroUVUsed = true;
        screenPositionUsed = true;
        return new Expr("((vec2(lfx_depth_viewport_uv().x * 2.0 - 1.0, 1.0 - lfx_depth_viewport_uv().y * 2.0) - MacroUV.xy) * MacroUV.zw + vec2(0.5))", 2);
    }

    private Expr ViewProperty(JObject properties, int output)
    {
        string property = (properties.Value<string>("Property") ?? "MEVP_FieldOfView").Split("::").Last();
        bool inverse = output == 1;
        switch (property) {
            case "MEVP_BufferSize": case "MEVP_ViewSize":
                return ViewSize(inverse);
            case "MEVP_FieldOfView": case "MEVP_TanHalfFieldOfView":
                viewProjectionUsed = true;
                previousViewUsed |= compilingPreviousFrame;
                string tangent = compilingPreviousFrame ? "(1.0 / abs(MotionPreviousProjection.xy))"
                        : "(1.0 / abs(vec2(ProjMat[0][0], ProjMat[1][1])))";
                string value = property == "MEVP_FieldOfView" ? $"(2.0 * atan({tangent}))" : tangent;
                return new Expr(inverse ? $"(1.0 / {value})" : value, 2);
            case "MEVP_ResolutionFraction":
                return new Expr(inverse ? "EffectTimeData.z" : "EffectTimeData.y", 1);
            default:
                throw new ConversionException($"Unsupported material view property {property}");
        }
    }

    private Expr ViewSize(bool inverse)
    {
        if (volumeCoordinates) return new Expr(inverse ? "VolumeViewport.xy" : "(1.0 / VolumeViewport.xy)", 2);
        viewUniformUsed = true;
        return new Expr(inverse ? "vec2(Geometry.w, CameraUp.w)" : "(1.0 / vec2(Geometry.w, CameraUp.w))", 2);
    }

    private Expr IsOrthographic()
    {
        viewProjectionUsed = true;
        return new Expr("(ProjMat[3][3] >= 1.0 ? 1.0 : 0.0)", 1);
    }

    private Expr SceneDepth(Context context, JObject properties)
    {
        sceneDepthUsed = true;
        depthProjectionUsed = true;
        vertexSceneDepthUsed |= compilingVertex;
        string mode = (properties.Value<string>("InputMode") ?? "Coordinates").Split("::").Last();
        bool connected = properties["Input"] is JObject source && source.Value<int>("expression") != 0;
        if (mode == "Coordinates") {
            if (!connected) {
                if (!compilingVertex) return new Expr("lfx_scene_depth()", 1);
                vertexSceneViewportUsed = true;
                return new Expr("lfx_scene_depth_at(lfx_vertex_scene_viewport_uv())", 1);
            }
            Expr uv = Cast(RequiredInput(context, properties, "Input"), 2);
            if (volumeCoordinates) return new Expr($"lfx_volume_material_scene_depth_at({uv.Code})", 1);
            return new Expr($"lfx_scene_depth_at(clamp({uv.Code}, vec2(Geometry.w, CameraUp.w) * 0.5, vec2(1.0) - vec2(Geometry.w, CameraUp.w) * 0.5))", 1);
        }
        if (mode == "OffsetFraction") {
            Expr offset = connected ? Cast(RequiredInput(context, properties, "Input"), 2)
                : Vector(new[] { SourceAsset.Number(properties["ConstInput"]?["X"]), SourceAsset.Number(properties["ConstInput"]?["Y"]) });
            vertexSceneViewportUsed |= compilingVertex;
            string viewport = compilingVertex ? "lfx_vertex_scene_viewport_uv()" : "lfx_depth_viewport_uv()";
            return new Expr($"lfx_scene_depth_at(clamp({viewport} + {offset.Code}, vec2(0.0005), vec2(0.9995)))", 1);
        }
        throw new ConversionException($"Unknown scene depth input mode {mode}");
    }

    private Expr CameraVector()
    {
        if (volumeCoordinates) return Vector([0, 0, 0]);
        cameraVectorUsed = true;
        return new Expr("lfx_camera_vector()", 3);
    }

    private Expr MaterialTime(JObject properties)
    {
        bool periodic = properties.Value<bool?>("bOverride_Period") == true;
        double period = periodic ? SourceAsset.Number(properties["Period"]) : 0;
        return MaterialClock(properties.Value<bool?>("bIgnorePause") == true, periodic, period);
    }

    private Expr MaterialClock(bool real, bool periodic, double period)
    {
        if (periodic && period == 0) return Constant(0);
        previousClockUsed |= compilingPreviousFrame;
        string clock = compilingPreviousFrame ? real ? "MotionClock.y" : "MotionClock.z"
                : real ? "EffectClock.z" : "EffectClock.w";
        return ZeroGradient(new Expr(periodic ? $"lfx_fmod({clock}, {Number(period)})" : clock, 1));
    }

    private Expr ParticlePosition(JObject properties)
    {
        string origin = (properties.Value<string>("OriginType") ?? "Absolute").Split("::").Last();
        string translatedPosition = volumeCoordinates ? "vec3(0.0)" : compilingVertex
                ? "lfxVertexMaterialParticleCenter" : "effectParticleCenter";
        return origin switch {
            "CameraRelative" => new Expr(translatedPosition, 3),
            "Absolute" => AbsolutePosition(translatedPosition),
            _ => throw new ConversionException($"Unknown particle position origin {origin}")
        };
    }

    private Expr WorldPosition(JObject properties)
    {
        string mode = (properties.Value<string>("WorldPositionShaderOffset") ?? "WPT_Default").Split("::").Last();
        bool noOffsets = mode is "WPT_CameraRelativeNoOffsets" or "WPT_ExcludeAllShaderOffsets";
        if (volumeCoordinates) {
            return mode switch {
                "WPT_CameraRelative" or "WPT_CameraRelativeNoOffsets" => Vector([0, 0, 0]),
                "WPT_Default" => AbsolutePosition("(effectPosition.xzy * 100.0)"),
                "WPT_ExcludeAllShaderOffsets" => AbsolutePosition("vec3(0.0)"),
                _ => throw new ConversionException($"Unknown world position mode {mode}")
            };
        }
        string relative;
        if (lightFunctionCoordinates) {
            lightFunctionUsesPositionOrCoordinates = true;
            relative = "lfxLightFunctionTranslatedWorld";
        }
        else if (!decalCoordinates && !compilingVertex && noOffsets) {
            worldNoOffsetsUsed = true;
            relative = "(lfx_material_position_no_offsets().xzy * 100.0)";
        } else {
            relative = decalCoordinates ? "(lfxDecalWorldPosition.xzy * 100.0)" : compilingVertex
                    ? "(lfxVertexMaterialPosition.xzy * 100.0)" : "(effectPosition.xzy * 100.0)";
        }
        Expr result = mode switch {
            "WPT_CameraRelative" or "WPT_CameraRelativeNoOffsets" => new Expr(relative, 3),
            "WPT_Default" or "WPT_ExcludeAllShaderOffsets" => AbsolutePosition(relative),
            _ => throw new ConversionException($"Unknown world position mode {mode}") };
        if (!compilingVertex && naniteCoordinates)
            result = result with { DerivativeX = $"lfx_material_world_dx({(noOffsets ? "true" : "false")})",
                DerivativeY = $"lfx_material_world_dy({(noOffsets ? "true" : "false")})" };
        return result;
    }

    private Expr VertexColor(int output)
    {
        if (volumeCoordinates) return output switch {
            0 => Vector([0, 0, 0]), 1 or 2 or 3 or 4 => Constant(0),
            _ => throw new ConversionException("Invalid vertex color output") };
        if (!meshCoordinates && compilingVertex) return output switch {
            0 => new Expr("effectColor.rgb", 3),
            >= 1 and <= 4 => new Expr("effectColor." + "rgba"[output - 1], 1),
            _ => throw new ConversionException("Invalid vertex color output") };
        if (!meshCoordinates) return output switch {
            0 => new Expr("vec3(1.0)", 3), 1 or 2 or 3 or 4 => Constant(1),
            _ => throw new ConversionException("Invalid vertex color output") };
        return output switch {
            0 => new Expr("effectVertexColor.rgb", 3),
            >= 1 and <= 4 => new Expr("effectVertexColor." + "rgba"[output - 1], 1),
            _ => throw new ConversionException("Invalid vertex color output") };
    }

    private Expr SpriteRotation(int output)
    {
        if (output is < 0 or > 1) throw new ConversionException("Invalid particle sprite rotation output");
        if (!spriteCoordinates) return Constant(0);
        return output == 0 ? new Expr("effectSpriteRotation", 1)
            : new Expr($"(effectSpriteRotation * {Number((float)57.2957795131)})", 1);
    }

    private Expr SubUVProperties(int output)
    {
        if (!spriteCoordinates && !meshCoordinates)
            throw new ConversionException("Particle SubUV properties require a Niagara sprite or mesh renderer");
        subUVUsed = true;
        return output switch {
            0 => new Expr(meshCoordinates ? "effectMeshSubUV0" : "effectUV", 2),
            1 => new Expr(meshCoordinates ? "effectMeshSubUV1" : "effectNextUV", 2),
            2 => new Expr("effectSubImageLerp", 1),
            _ => throw new ConversionException("Invalid particle SubUV property output") };
    }

    private Expr ParticleMaterial(string field)
    {
        if (volumeCoordinates) return field switch {
            "direction" => Vector([0, 0, 0]),
            "size" => Vector([0, 0]),
            "speed" or "radius" => Constant(0),
            _ => throw new ConversionException($"Unknown particle material field {field}")
        };
        if (!spriteCoordinates && !meshCoordinates) ribbonParticleData = true;
        string velocity = spriteCoordinates ? "effectSpriteVelocity" : meshCoordinates ? "effectMeshVelocity" : "effectRibbonVelocity";
        string size = spriteCoordinates ? "effectSpriteSize" : "effectRibbonSize";
        return field switch {
            "direction" => new Expr(velocity + ".xyz", 3),
            "speed" => new Expr(velocity + ".w", 1),
            "size" when meshCoordinates => new Expr("vec2(0.0)", 2),
            "size" => new Expr(size, 2),
            "radius" when meshCoordinates => Constant(1),
            "radius" when spriteCoordinates => new Expr($"(0.5 * min({size}.x, {size}.y))", 1),
            "radius" => new Expr($"(0.5 * {size}.x)", 1),
            _ => throw new ConversionException($"Unknown particle material field {field}")
        };
    }

    private Expr DynamicParameter(JObject p, int output)
    {
        double[] defaults = p["DefaultValue"] is null ? new[] { 1d, 1d, 1d, 1d } : Color(p["DefaultValue"], 4);
        int index = p.Value<int?>("ParameterIndex") ?? 0;
        if (index < 0 || index > 3) throw new ConversionException($"Invalid dynamic material parameter slot {index}");
        dynamicSlots |= 1 << index;
        if (index == 0) dynamicDefaults ??= defaults;
        string input = index == 0 ? "effectCustom" : $"effectCustom{index}";
        int mask = 1 << (index * 4);
        Expr value = new($"mix({Vector(defaults).Code}, {input}, bvec4((int(effectDynamicValid) & {mask}) != 0, " +
            $"(int(effectDynamicValid) & {mask << 1}) != 0, (int(effectDynamicValid) & {mask << 2}) != 0, " +
            $"(int(effectDynamicValid) & {mask << 3}) != 0))", 4);
        return output switch {
            >= 0 and < 4 => Swizzle(value, "rgba"[output].ToString()),
            4 => Swizzle(value, "rgb"), 5 => value,
            _ => throw new ConversionException($"Invalid dynamic material parameter output {output}") };
    }

    private Expr Clamp(Context context, JObject p)
    {
        Expr input = RequiredInput(context, p, "Input");
        Expr min = Input(context, p["Min"], () => Constant(SourceAsset.Number(p["MinDefault"])));
        Expr max = Input(context, p["Max"], () => Constant(SourceAsset.Number(p["MaxDefault"], 1)));
        Expr low = Cast(min, input.Width), high = Cast(max, input.Width);
        string mode = (p.Value<string>("ClampMode") ?? "CMODE_Clamp").Split("::").Last();
        if (UsesMirTranslation) return mode switch {
            "CMODE_Clamp" => MirTernary("Clamp", input, min, max),
            "CMODE_ClampMin" => ExtremumValue(input, min, "max"),
            "CMODE_ClampMax" => ExtremumValue(input, max, "min"),
            _ => throw new ConversionException($"Unknown material clamp mode {mode}")
        };
        return mode switch {
            "CMODE_Clamp" => ExtremumValue(ExtremumValue(input, low, "max"), high, "min"),
            "CMODE_ClampMin" => ExtremumValue(input, low, "max"),
            "CMODE_ClampMax" => ExtremumValue(input, high, "min"),
            _ => throw new ConversionException($"Unknown material clamp mode {mode}") };
    }
    private Expr Trigonometric(Context context, JObject p, string operation)
    {
        Expr value = RequiredInput(context, p, "Input");
        float multiplier = TrigonometricMultiplier(p, operation);
        if (multiplier == 1) return PrecisionUnary(value, operation);
        if (value.LargeWorld) return PrecisionUnary(Arithmetic(value, Constant(multiplier), "*"), operation);
        return new Expr($"{operation}({value.Code} * {Number(multiplier)})", value.Width);
    }

    private static float TrigonometricMultiplier(JObject properties, string operation)
    {
        float period = (float)SourceAsset.Number(properties["Period"], 1);
        if (operation == "sin" && period <= 0) return 1;
        return period == 0 ? 0 : (2.0f * (float)Math.PI) / period;
    }
    private Expr Switch(Context context, JObject p)
    {
        Expr condition = Input(context, p["Value"], () => Constant(p.Value<bool?>("DefaultValue") == true ? 1 : 0));
        if (double.TryParse(condition.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out double constant))
            return RequiredInput(context, p, constant != 0 ? "A" : "B");
        Expr a = RequiredInput(context, p, "A"), b = RequiredInput(context, p, "B");
        int width = Math.Max(a.Width, b.Width);
        if (a.LargeWorld || b.LargeWorld)
            return new Expr($"lfx_lwc_select({condition.Code} != 0.0, {Promote(Cast(a, width))}, {Promote(Cast(b, width))})",
                    width, LargeWorld: true);
        return new Expr($"({condition.Code} != 0.0 ? {Cast(a, width).Code} : {Cast(b, width).Code})", width);
    }

    private Expr Desaturate(Context context, JObject p)
    {
        Expr input = DemoteLargeWorld(Cast(RequiredInput(context, p, "Input"), 3));
        Expr luminance = Vector(p["LuminanceFactors"] is null ? new[] { .3, .59, .11 } : Color(p["LuminanceFactors"], 3));
        Expr grey = Dot(input, luminance);
        if (p["Fraction"] is not JObject fractionInput ||
            fractionInput.Value<int>("expression") == 0 && fractionInput.Value<bool?>("constant") != true)
            return grey;
        Expr fraction = Input(context, p["Fraction"], () => Constant(1));
        return Interpolate(input, grey, fraction);
    }

    private Expr ColorSpaceConvert(Context context, JObject p, bool rgbToHsv)
    {
        Expr input = DemoteLargeWorld(RequiredInput(context, p, "Input"));
        string function = rgbToHsv ? "lfx_rgb_to_hsv" : "lfx_hsv_to_rgb";
        if (!UsesMirTranslation)
            return input.Width is 3 or 4 ? new Expr($"{function}({input.Code})", input.Width) : input;

        if (input.Sampler)
            throw new ConversionException("A MIR color conversion requires a primitive input");
        input = AsFloat(input);
        if (rgbToHsv && !UsesMirStrictProfile && input.Width == 1)
            return new Expr($"vec3(0.0, 0.0, {input.Code})", 3, Uniform: input.Uniform);
        if (input.Width is not (3 or 4))
            throw new ConversionException("A MIR color conversion requires a 3D or 4D vector");
        return new Expr($"{function}({input.Code})", input.Width, Uniform: input.Uniform,
                MirColumnVector: input.MirColumnVector);
    }

    private Expr FloatToUInt(Context context, JObject p)
    {
        Expr input = RequiredInput(context, p, "Input");
        if (UsesMirTranslation) return ZeroGradient(CastSigned(input, input.Width));
        string mode = (p.Value<string>("Mode") ?? "Truncate").Split("::").Last();
        string function = mode switch {
            "Truncate" or "0" => "trunc",
            "Floor" or "1" => "floor",
            "Round" or "2" => "roundEven",
            "Ceil" or "3" => "ceil",
            _ => throw new ConversionException($"Unsupported FloatToUInt mode {mode}")
        };
        return ZeroGradient(AsUInt(PrecisionUnary(AsFloat(input), function)));
    }

    private Expr UIntToFloat(Context context, JObject p)
        => AsFloat(RequiredInput(context, p, "Input"));

    private Expr MaterialModulo(Context context, JObject p)
    {
        Expr a = RequiredInput(context, p, "A"), b = RequiredInput(context, p, "B");
        if (UsesMirTranslation) return MirModulo(a, b);
        if (!a.UnsignedInt) return RequiredBinary(context, p, "A", "B", "lfx_fmod");
        Expr right = CastUnsigned(b, a.Width);
        return ZeroGradient(new Expr($"({a.Code} % {right.Code})", a.Width, UnsignedInt: true));
    }

    private static Expr AsFloat(Expr value)
    {
        if (!value.IsInteger && !value.ShaderBoolean) return value;
        return value with { RawCode = $"{Type(value.Width)}({value.Code})", UnsignedInt = false, SignedInt = false, ShaderBoolean = false };
    }

    private static Expr AsUInt(Expr value)
    {
        if (value.UnsignedInt) return value;
        Expr numeric = DemoteLargeWorld(value);
        string uType = Type(numeric.Width, unsignedInt: true);
        if (numeric.SignedInt || numeric.ShaderBoolean) return ZeroGradient(numeric with {
            RawCode = $"{uType}({numeric.Code})", SignedInt = false, UnsignedInt = true, ShaderBoolean = false
        });
        string zero = numeric.Width == 1 ? "0.0" : $"vec{numeric.Width}(0.0)";
        return new Expr($"{uType}(max({numeric.Code}, {zero}))", numeric.Width, ZeroConstant: numeric.ZeroConstant, UnsignedInt: true);
    }

    private static Expr CastUnsigned(Expr value, int width)
    {
        Expr unsignedValue = AsUInt(value);
        if (unsignedValue.Width == width) return unsignedValue;
        if (width == 1) return new Expr($"({unsignedValue.Code}).x", 1, ZeroConstant: unsignedValue.ZeroConstant, UnsignedInt: true);
        if (unsignedValue.Width == 1) return new Expr($"uvec{width}({unsignedValue.Code})", width, ZeroConstant: unsignedValue.ZeroConstant, UnsignedInt: true);
        if (unsignedValue.Width > width) return new Expr($"({unsignedValue.Code}).{"xyzw"[..width]}", width, ZeroConstant: unsignedValue.ZeroConstant, UnsignedInt: true);
        return new Expr($"uvec{width}({unsignedValue.Code}, {string.Join(", ", Enumerable.Repeat("0u", width - unsignedValue.Width))})",
            width, ZeroConstant: unsignedValue.ZeroConstant, UnsignedInt: true);
    }

    private Expr ChannelMaskParameter(Context context, JObject p)
    {
        Expr input = Cast(RequiredInput(context, p, "Input"), 4);
        if (p.Value<bool?>("bUseCustomPrimitiveData") == true)
            return Dot(input, PrimitiveCustomMaterialParameter(p, 4));
        string name = p.Value<string>("ParameterName") ?? "Param";
        JToken? overrideValue = ParameterValue(context, InstanceParameterType.Vector, name);
        double[] defaults = overrideValue is not null
            ? Color(overrideValue, 4)
            : p["DefaultValue"] is not null
                ? Color(p["DefaultValue"], 4)
                : (p.Value<string>("MaskChannel") ?? "Red").Split("::").Last() switch {
                    "Green" or "1" => new[] { 0d, 1d, 0d, 0d },
                    "Blue" or "2" => new[] { 0d, 0d, 1d, 0d },
                    "Alpha" or "3" => new[] { 0d, 0d, 0d, 1d },
                    _ => new[] { 1d, 0d, 0d, 0d }
                };
        Expr mask = RuntimeParameter(context, name, "vector", defaults, Vector(defaults) with { ParameterLiteral = true });
        return Dot(input, mask);
    }

    private Expr LocalPosition(JObject p)
    {
        string origin = (p.Value<string>("LocalOrigin") ?? "Instance").Split("::").Last();
        if (origin is "InstancePreSkinning" or "1") return PreSkinnedInput(false, 0);
        string offsets = (p.Value<string>("IncludedOffsets") ?? "IncludeOffsets").Split("::").Last();
        bool exclude = offsets is "ExcludeOffsets" or "1";
        Expr world = WorldPosition(exclude
            ? new JObject { ["WorldPositionShaderOffset"] = "WPT_ExcludeAllShaderOffsets" }
            : new JObject());
        MaterialSpace target = origin is "Primitive" or "2" ? MaterialSpace.Local : MaterialSpace.Instance;
        return TransformMaterialValue(MaterialSpace.World, target, world, position: true);
    }

    private Expr VectorParameter(Context context, JObject p, int output)
    {
        Expr result;
        if (p.Value<bool?>("bUseCustomPrimitiveData") == true) result = PrimitiveCustomMaterialParameter(p, 4);
        else {
            string name = p.Value<string>("ParameterName") ?? "Param";
            double[] defaults = Color(ParameterValue(context, InstanceParameterType.Vector, name) ?? p["DefaultValue"], 4);
            result = RuntimeParameter(context, name, "vector", defaults, Vector(defaults) with { ParameterLiteral = true });
        }
        if (p["Outputs"] is JArray outputs && output < outputs.Count && outputs[output] is JObject value && value.Value<int>("Mask") != 0)
            return Swizzle(result, string.Concat(new[] { "R", "G", "B", "A" }.Where(channel => value.Value<int>("Mask" + channel) != 0)).ToLowerInvariant());
        return output switch {
            0 => Swizzle(result, "rgb"),
            1 => Swizzle(result, "r"), 2 => Swizzle(result, "g"),
            3 => Swizzle(result, "b"), 4 => Swizzle(result, "a"),
            5 => result,
            _ => throw new ConversionException($"Invalid vector parameter output {output}") };
    }

    private Expr Texture(Context context, JObject p, string? sourceDefault = null)
    {
        string? name = p.Value<string>("ParameterName");
        JToken? value = name is null ? null : ParameterValue(context, InstanceParameterType.Texture, name);
        int reference = p.Value<int?>("Texture") ?? 0;
        string? authored = reference != 0 ? context.Asset.ObjectPath(reference) : p["Texture"] is null ? sourceDefault : null;
        string path = value?.Value<string>() ?? authored ?? throw new ConversionException("Material texture expression has no texture object");
        if (textureAliases?.TryGetValue(path, out string? replacement) == true) path = replacement;
        string? staticSource = (name is null ? null : FunctionParameterValue(context, InstanceParameterType.Texture, name)?.Value<string>()) ?? authored;
        if (staticSource is not null && textureAliases?.TryGetValue(staticSource, out string? replacementStatic) == true)
            staticSource = replacementStatic;
        var samplerType = ReadSamplerType(p);
        if (IsVirtualSampler(samplerType)) {
            var asset = library.Load(path).ResolveObject(path, value => value.ClassName == "Texture2D");
            if (asset.Properties.Value<bool?>("VirtualTextureStreaming") != true)
                throw new ConversionException($"Virtual sampler requires an authored streaming Texture2D: {path}");
            return StreamedVirtualTexture(context, path, name, samplerType, staticSource);
        }
        if (samplerType == MaterialSamplerType.Data) {
            var asset = library.Load(path).ResolveObject(path, _ => true);
            if (asset.ClassName == "Texture2D" && asset.Properties.Value<bool?>("VirtualTextureStreaming") == true)
                return StreamedVirtualTexture(context, path, name, samplerType, staticSource);
        }
        Expr sampler = RegisterTextureSampler(context, path, name, samplerType) with { StaticTextureSource = staticSource };
        if (value is not null && authored is not null) {
            if (textureAliases?.TryGetValue(authored, out string? replacementAuthored) == true) authored = replacementAuthored;
            if (ReadTextureSettings(authored) != TextureShape(sampler.Code))
                throw new ConversionException($"Material texture parameter {name} changes its sampler dimension");
        }
        if (name is not null && context.ParameterAssociation == "GlobalParameter" && context.ParameterIndex == -1
                && rendererConstants.Texture(name) is null && rendererBindings.Find(name, "texture") is { } binding)
        {
            string baseDefault = staticSource ?? throw new ConversionException($"Material texture parameter {name} has no authored default");
            ReadTextureSettings(baseDefault);
            runtimeTextures[sampler.Code] = new CompiledMaterialTexture(binding, sampler.Code, baseDefault);
        }
        else if (name is not null && (context.ParameterAssociation != "GlobalParameter"
                || rendererConstants.Texture(name) is null || runtimeDynamicParameters || sourceDynamicMaterial))
            textureParameterSamplers[sampler.Code] = new(name, context.ParameterAssociation, context.ParameterIndex, sampler.Code);
        return sampler;
    }

    private Expr RegisterTextureSampler(Context context, string path, string? name, MaterialSamplerType samplerType)
    {
        SamplerKey key = new(path, name is null ? null : SourceNameOrder.Canonical(name), name is null ? "" : context.ParameterAssociation,
                name is null ? -1 : context.ParameterIndex);
        string sampler = RegisterTextureSampler(key);
        return new Expr(sampler, 0, true, samplerType, TextureDimensions: TextureCoordinateWidth(TextureShape(sampler)));
    }

    private MaterialTextureShape ReadTextureSettings(string path)
    {
        if (materialTextureShapes.TryGetValue(path, out var shape)) return shape;
        if (textureSettings.ContainsKey(path)) {
            materialTextureShapes.Add(path, MaterialTextureShape.Texture2D);
            return MaterialTextureShape.Texture2D;
        }
        SourceAsset texture = library.Load(path);
        SourceObject source = texture.ResolveObject(path, value => value.ClassName is "Texture2D" or "TextureLightProfile" or "TextureRenderTarget2D"
                or "CurveLinearColorAtlas" or "Texture2DArray" or "TextureRenderTarget2DArray" or "TextureCube" or "TextureRenderTargetCube"
                or "TextureCubeArray" or "VolumeTexture" or "TextureRenderTargetVolume");
        shape = source.ClassName switch {
            "Texture2DArray" or "TextureRenderTarget2DArray" => MaterialTextureShape.Array2D,
            "TextureCube" or "TextureRenderTargetCube" => MaterialTextureShape.Cube,
            "TextureCubeArray" => MaterialTextureShape.CubeArray,
            "VolumeTexture" or "TextureRenderTargetVolume" => MaterialTextureShape.Volume,
            _ => MaterialTextureShape.Texture2D
        };
        materialTextureShapes.Add(path, shape);
        if (shape != MaterialTextureShape.Texture2D || source.ClassName is "TextureRenderTarget2D" or "CurveLinearColorAtlas")
            renderTargetTextures.Add(path);
        else textureSettings.Add(path, Textures.Read(texture, path));
        return shape;
    }

    private Expr AntialiasedTextureMask(Context context, JObject properties)
    {
        if ((properties.Value<int?>("Texture") ?? 0) == 0)
            throw new ConversionException("Antialiased texture mask requires its source texture");
        Expr texture = Texture(context, properties);
        if (TextureShape(texture.Code) != MaterialTextureShape.Texture2D)
            throw new ConversionException("Antialiased texture mask requires a 2D texture");
        Expr uv = Cast(Input(context, properties["Coordinates"], () => Coordinates(new JObject {
            ["CoordinateIndex"] = properties.Value<int?>("ConstCoordinate") ?? 0 })), 2);
        JToken? channelValue = properties["Channel"];
        string channel = (channelValue?.Value<string>() ?? "TCC_Alpha").Split("::").Last();
        int index = channelValue?.Type == JTokenType.Integer ? channelValue.Value<int>() : channel switch {
            "TCC_Red" => 0, "TCC_Green" => 1, "TCC_Blue" => 2, "TCC_Alpha" => 3,
            "TCC_MAX" => -1, _ => throw new ConversionException($"Unknown texture mask channel {channel}") };
        double[] mask = Enumerable.Range(0, 4).Select(i => i == index ? 1.0 : 0.0).ToArray();
        if (!texture.VirtualTexture) usedTextureSamplers.Add(texture.Code);
        string sampled = texture.VirtualTexture ? TextureSample(context, properties, texture, uv.Code, false, uv.DerivativeX, uv.DerivativeY)
                : $"texture({texture.Code}, {uv.Code})";
        Expr sample = Shared(new Expr($"dot({Vector(mask).Code}, {sampled})", 1));
        string threshold = Number((float)SourceAsset.Number(properties["Threshold"], .5));
        return new Expr($"clamp(({sample.Code} - {threshold}) / max(abs(dFdx({sample.Code})), abs(dFdy({sample.Code}))) + {threshold}, 0.0, 1.0)", 1);
    }

    private Expr SampleTexture(Context context, JObject p, int output, MaterialTextureShape? expectedShape = null)
    {
        if (lightFunctionCoordinates && Connected(p["Coordinates"])) lightFunctionUsesPositionOrCoordinates = true;
        string? sourceDefault = expectedShape switch {
            MaterialTextureShape.Texture2D => DefaultMaterialTexture2D,
            MaterialTextureShape.Cube => "/Engine/EngineResources/DefaultTextureCube.DefaultTextureCube",
            MaterialTextureShape.Volume => "/Engine/EngineResources/DefaultVolumeTexture.DefaultVolumeTexture",
            _ => null
        };
        Expr sampler = Input(context, p["TextureObject"], () => Texture(context, p, sourceDefault));
        if (expectedShape is { } shape && TextureShape(sampler.Code) != shape)
            throw new ConversionException($"Texture sample requires a {shape} asset");
        Expr coordinates = TextureSampleCoordinates(p, sampler, Input(context, p["Coordinates"], () => sampler.TextureDimensions != 2
            ? throw new ConversionException("Dimensional texture sample requires an explicit coordinate input")
            : Coordinates(new JObject { ["CoordinateIndex"] = p.Value<int?>("ConstCoordinate") ?? 0 })));
        bool automaticBias = p.Value<bool?>("AutomaticViewMipBias") ?? true;
        if (Connected(p["AutomaticViewMipBiasValue"]))
            automaticBias = RequiredInput(context, p, "AutomaticViewMipBiasValue").StaticBool ?? automaticBias;
        Expr rgba = ApplySamplerType(new Expr(TextureSample(context, p, sampler, coordinates.Code, automaticBias,
            coordinates.DerivativeX, coordinates.DerivativeY), 4), sampler);
        return output switch { 0 => Swizzle(rgba, "rgb"), 1 => Swizzle(rgba, "r"), 2 => Swizzle(rgba, "g"), 3 => Swizzle(rgba, "b"), 4 => Swizzle(rgba, "a"),
            5 => rgba, _ => throw new ConversionException($"Invalid texture sample output {output}") };
    }

    private Expr SampleSubUV(Context context, JObject p, int output, string? sourceDefault = null)
    {
        if (!spriteCoordinates && !meshCoordinates) throw new ConversionException("SubUV texture sampling requires a Niagara sprite or mesh renderer");
        subUVUsed = true;
        if (naniteCoordinates && !compilingVertex) pixelCoordinateMask |= 1;
        Expr sampler = Input(context, p["TextureObject"], () => Texture(context, p, sourceDefault));
        if (TextureShape(sampler.Code) != MaterialTextureShape.Texture2D)
            throw new ConversionException("SubUV sampling requires a 2D texture");
        string? dx = naniteCoordinates && !compilingVertex ? "lfx_material_coordinate_dx(0)" : null;
        string? dy = dx is null ? null : "lfx_material_coordinate_dy(0)";
        Expr first = ApplySamplerType(new Expr(TextureSample(context, p, sampler, meshCoordinates ? "effectMeshSubUV0" : "effectUV", false, dx, dy), 4), sampler);
        Expr rgba = first;
        if (p.Value<bool?>("bBlend") ?? true)
        {
            Expr second = ApplySamplerType(new Expr(TextureSample(context, p, sampler, meshCoordinates ? "effectMeshSubUV1" : "effectNextUV", false, dx, dy), 4), sampler);
            rgba = Shared(new Expr($"mix({first.Code}, {second.Code}, effectSubImageLerp)", 4));
        }
        return output switch { 0 => Swizzle(rgba, "rgb"), 1 => Swizzle(rgba, "r"), 2 => Swizzle(rgba, "g"), 3 => Swizzle(rgba, "b"), 4 => Swizzle(rgba, "a"), 5 => rgba,
            _ => throw new ConversionException($"Invalid texture sample output {output}") };
    }

    private string TextureSample(Context context, JObject properties, Expr sampler, string coordinates, bool automaticBias,
        string? derivativeX = null, string? derivativeY = null)
    {
        var shape = TextureShape(sampler.Code);
        MaterialTextureMipMode mode = ReadTextureMipMode(properties);
        var gather = TextureGather(properties);
        automaticBias = automaticBias && !compilingVertex && !lightFunctionCoordinates && !volumeCoordinates
            && shape == MaterialTextureShape.Texture2D && gather == MaterialTextureGather.None
            && (int)library.MaterialPermutation.EffectiveFeatureLevel >= (int)MaterialFeatureInput.Sm5;
        if (!sampler.VirtualTexture) {
            sampler = TextureWithSamplerSource(sampler, properties, shape, ref automaticBias);
            usedTextureSamplers.Add(sampler.Code);
        }
        if (gather != MaterialTextureGather.None) {
            if (sampler.VirtualTexture || shape == MaterialTextureShape.Volume || sampler.SamplerType == MaterialSamplerType.External)
                throw new ConversionException("Texture gather requires a non-virtual 2D, 2D array or cube sampler");
            if (mode != MaterialTextureMipMode.Automatic) throw new ConversionException("Texture gather does not accept a mip override");
            materialTextureGatherUsed = true;
            return $"textureGather({sampler.Code}, {coordinates}, {(int)gather - 1})";
        }
        if (sampler.VirtualTexture) return SampleStreamedVirtualTexture(context, properties, sampler.Code, coordinates, automaticBias, derivativeX, derivativeY);
        if (shape == MaterialTextureShape.Array2D && derivativeX is not null && derivativeY is not null) {
            derivativeX = $"({derivativeX}).xy";
            derivativeY = $"({derivativeY}).xy";
        } else if (shape == MaterialTextureShape.CubeArray && derivativeX is not null && derivativeY is not null) {
            derivativeX = $"({derivativeX}).xyz";
            derivativeY = $"({derivativeY}).xyz";
        }
        if (compilingVertex) {
            if (mode == MaterialTextureMipMode.Bias) throw new ConversionException("Texture MipBias is only supported in the pixel shader");
            Expr mip;
            if (mode == MaterialTextureMipMode.Automatic) mip = Constant(0);
            else if (mode == MaterialTextureMipMode.Level)
                mip = Input(context, properties["MipValue"], () => Constant(SourceAsset.Number(properties["ConstMipValue"], -1)));
            else {
                mip = RequiredInput(context, properties, "CoordinatesDX");
                RequiredInput(context, properties, "CoordinatesDY");
            }
            return $"textureLod({sampler.Code}, {coordinates}, {Cast(mip, 1).Code})";
        }
        if (mode == MaterialTextureMipMode.Automatic && volumeCoordinates) return $"lfx_volume_texture({sampler.Code}, {coordinates}, 0.0)";
        if (mode == MaterialTextureMipMode.Automatic && naniteCoordinates && derivativeX is not null && derivativeY is not null)
            return $"lfx_sample_grad_{sampler.Code}({coordinates}, {derivativeX}, {derivativeY}, {(automaticBias ? "EffectClock.y" : "0.0")})";
        if (mode == MaterialTextureMipMode.Automatic) return automaticBias ? $"texture({sampler.Code}, {coordinates}, EffectClock.y)"
            : $"lfx_sample_{sampler.Code}({coordinates})";
        if (mode is MaterialTextureMipMode.Level or MaterialTextureMipMode.Bias)
        {
            Expr mip = Cast(Input(context, properties["MipValue"], () => Constant(SourceAsset.Number(properties["ConstMipValue"], -1))), 1);
            string level = automaticBias ? $"({mip.Code} + EffectClock.y)" : mip.Code;
            if (mode == MaterialTextureMipMode.Bias && naniteCoordinates && derivativeX is not null && derivativeY is not null)
                return $"lfx_sample_grad_{sampler.Code}({coordinates}, {derivativeX}, {derivativeY}, {level})";
            return mode == MaterialTextureMipMode.Level ? $"textureLod({sampler.Code}, {coordinates}, {level})"
                : volumeCoordinates ? $"lfx_volume_texture({sampler.Code}, {coordinates}, {level})"
                : $"texture({sampler.Code}, {coordinates}, {level})";
        }
        int width = TextureGradientWidth(shape);
        Expr dx = Cast(RequiredInput(context, properties, "CoordinatesDX"), width);
        Expr dy = Cast(RequiredInput(context, properties, "CoordinatesDY"), width);
        string factor = automaticBias ? " * exp2(EffectClock.y)" : "";
        return $"textureGrad({sampler.Code}, {coordinates}, {dx.Code}{factor}, {dy.Code}{factor})";
    }

    private Expr Coordinates(JObject p, bool explicitNode = false)
    {
        if (lightFunctionCoordinates && explicitNode) lightFunctionUsesPositionOrCoordinates = true;
        int index = p.Value<int>("CoordinateIndex");
        if (decalCoordinates) decalTexcoordsUsed = true;
        if (index < 0 || index >= 8) throw new ConversionException($"Invalid texture coordinate channel {index}");
        string coordinate;
        if (decalCoordinates) coordinate = "effectUV";
        else if (compilingVertex) coordinate = RawCoordinate(index);
        else {
            pixelCoordinateMask |= 1 << index;
            coordinate = $"lfx_material_coordinate({index})";
        }
        string rawCoordinate = coordinate;
        bool unmirrorU = p.Value<bool?>("UnMirrorU") == true, unmirrorV = p.Value<bool?>("UnMirrorV") == true;
        if (unmirrorU || unmirrorV) {
            string sign = meshCoordinates ? "effectUnmirrored * " : "";
            string x = unmirrorU ? $"(({coordinate}).x * {sign}0.5 + 0.5)" : $"({coordinate}).x";
            string y = unmirrorV ? $"(({coordinate}).y * {sign}0.5 + 0.5)" : $"({coordinate}).y";
            coordinate = $"vec2({x}, {y})";
        }
        float u = (float)SourceAsset.Number(p["UTiling"], 1), v = (float)SourceAsset.Number(p["VTiling"], 1);
        string? Gradient(string axis) {
            if (compilingVertex || !naniteCoordinates) return null;
            string gradient = $"lfx_material_coordinate_{axis}({index})";
            if (unmirrorU || unmirrorV) {
                string signGradient = $"lfx_material_unmirrored_{axis}()";
                string x = unmirrorU ? $"(0.5 * (({gradient}).x * effectUnmirrored + ({rawCoordinate}).x * {signGradient}))" : $"({gradient}).x";
                string y = unmirrorV ? $"(0.5 * (({gradient}).y * effectUnmirrored + ({rawCoordinate}).y * {signGradient}))" : $"({gradient}).y";
                gradient = $"vec2({x}, {y})";
            }
            return $"({gradient} * vec2({Number(u)}, {Number(v)}))";
        }
        string? dx = Gradient("dx"), dy = Gradient("dy");
        if (MathF.Abs(u - v) > 1.0e-8f)
            return new Expr($"({coordinate} * vec2({Number(u)}, {Number(v)}))", 2, DerivativeX: dx, DerivativeY: dy);
        return MathF.Abs(1f - u) > 1.0e-8f
            ? new Expr($"({coordinate} * {Number(u)})", 2, DerivativeX: dx, DerivativeY: dy)
            : new Expr(coordinate, 2, DerivativeX: dx, DerivativeY: dy);
    }

    private static Expr Literal(JToken value) => value is JObject ? Vector(Color(value, 4)) : Constant(SourceAsset.Number(value));
    private Expr Shared(Expr value)
    {
        if (value.Sampler || double.TryParse(value.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out _)) return value;
        var key = (value.RawCode, value.Width, value.LargeWorld, value.ParameterLiteral, value.DerivativeX, value.DerivativeY,
                value.Uniform, value.UnsignedInt, value.SignedInt, value.ShaderBoolean, value.MirColumnVector);
        if (expressionValues.TryGetValue(key, out var previous)) return previous;
        var result = new Expr("lfx_value_" + expressionValues.Count, value.Width, ZeroConstant: value.ZeroConstant,
                LargeWorld: value.LargeWorld, ParameterLiteral: value.ParameterLiteral,
                DerivativeX: value.DerivativeX, DerivativeY: value.DerivativeY, Uniform: value.Uniform, UnsignedInt: value.UnsignedInt, SignedInt: value.SignedInt,
                ShaderBoolean: value.ShaderBoolean, MirColumnVector: value.MirColumnVector);
        expressions.Add($"{Type(value.Width, value.LargeWorld, value.UnsignedInt, value.SignedInt, value.ShaderBoolean)} {result.RawCode} = {value.RawCode};");
        expressionValues.Add(key, result);
        return result;
    }
    private static Expr Constant(double value) => new(Number(value), 1, ZeroConstant: value == 0, DerivativeX: "0.0", DerivativeY: "0.0", Uniform: true);
    private static Expr StaticBool(bool value) => Constant(value ? 1 : 0) with { StaticBool = value };
    private static Expr Vector(IEnumerable<double> values)
    {
        double[] array = values.ToArray();
        return array.Length == 1 ? Constant(array[0]) : new Expr($"vec{array.Length}({string.Join(", ", array.Select(Number))})", array.Length,
                ZeroConstant: array.All(value => value == 0), DerivativeX: $"vec{array.Length}(0.0)", DerivativeY: $"vec{array.Length}(0.0)", Uniform: true);
    }
    private static double[] Color(JToken? value, int count) => Enumerable.Range(0, count)
        .Select(index => SourceAsset.Number(value?["RGBA"[index].ToString()] ?? value?["XYZW"[index].ToString()])).ToArray();
    private static string Number(double value) => value.ToString("0.0################", CultureInfo.InvariantCulture);
    private static string Type(int width, bool largeWorld = false, bool unsignedInt = false, bool signedInt = false,
            bool shaderBoolean = false) =>
        shaderBoolean ? width == 1 ? "bool" : "bvec" + width
                : signedInt ? width == 1 ? "int" : "ivec" + width
                : unsignedInt ? width == 1 ? "uint" : "uvec" + width
                : largeWorld ? "LfxLWC" + width : width == 1 ? "float" : "vec" + width;
    private static bool IsLiteralZero(Expr value) => value.Width == 1 && !value.Sampler
        && double.TryParse(value.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out double literal) && literal == 0;
    private static int ArithmeticWidth(Expr a, Expr b)
    {
        if (a.Sampler || b.Sampler) throw new ConversionException("A sampler cannot be used as a numeric material input");
        return ArithmeticWidth(a.Width, b.Width);
    }
    private static int ArithmeticWidth(int a, int b)
    {
        if (a != b && a != 1 && b != 1)
            throw new ConversionException($"Arithmetic between material inputs of width {a} and {b} is undefined");
        return Math.Max(a, b);
    }
    private static Expr Coerce(Expr value, int width)
    {
        if (value.Width != width && value.Width != 1 && width != 1)
            throw new ConversionException($"Cannot coerce a material input of width {value.Width} to width {width}");
        return Cast(value, width);
    }
    private static Expr Cast(Expr value, int width)
        => CastComponents(value, width) with { ParameterLiteral = value.ParameterLiteral, Uniform = value.Uniform,
            DerivativeX = CastDerivative(value.DerivativeX, value.Width, width),
            DerivativeY = CastDerivative(value.DerivativeY, value.Width, width) };

    private static string? CastDerivative(string? derivative, int sourceWidth, int width)
        => derivative is null ? null : derivative == ZeroDerivative(sourceWidth)
                ? ZeroDerivative(width) : CastComponents(new Expr(derivative, sourceWidth), width).Code;

    private static Expr CastComponents(Expr value, int width)
    {
        if (value.Sampler) throw new ConversionException("A sampler cannot be used as a numeric material input");
        value = AsFloat(value);
        if (value.Width == width) return value;
        if (value.LargeWorld) return LargeWorldCast(value, width);
        if (width == 1) return new Expr($"({value.Code}).x", 1, ZeroConstant: value.ZeroConstant);
        if (value.Width == 1) return new Expr($"vec{width}({value.Code})", width, ZeroConstant: value.ZeroConstant);
        if (value.Width > width) return new Expr($"({value.Code}).{"xyzw"[..width]}", width, ZeroConstant: value.ZeroConstant);
        return new Expr($"vec{width}({value.Code}, {string.Join(", ", Enumerable.Repeat("0.0", width - value.Width))})", width,
                ZeroConstant: value.ZeroConstant);
    }
    private static Expr Swizzle(Expr value, string mask)
        => SwizzleComponents(value, mask) with { ParameterLiteral = value.ParameterLiteral, Uniform = value.Uniform,
            DerivativeX = SwizzleDerivative(value.DerivativeX, value.Width, mask),
            DerivativeY = SwizzleDerivative(value.DerivativeY, value.Width, mask) };

    private static string? SwizzleDerivative(string? derivative, int sourceWidth, string mask)
        => derivative is null ? null : derivative == ZeroDerivative(sourceWidth)
                ? ZeroDerivative(mask.Length) : SwizzleComponents(new Expr(derivative, sourceWidth), mask).Code;

    private static Expr SwizzleComponents(Expr value, string mask)
    {
        if (mask.Length == 0) throw new ConversionException("An empty material component mask is invalid");
        if (value.LargeWorld) return LargeWorldSwizzle(value, mask);
        if (value.Width == 1) return mask.Length == 1 ? value
                : new Expr($"{Type(mask.Length, unsignedInt: value.UnsignedInt, signedInt: value.SignedInt, shaderBoolean: value.ShaderBoolean)}({value.Code})", mask.Length,
                        ZeroConstant: value.ZeroConstant, UnsignedInt: value.UnsignedInt, SignedInt: value.SignedInt,
                        ShaderBoolean: value.ShaderBoolean);
        if (mask.Any(channel => "rgba".IndexOf(channel) >= value.Width))
            throw new ConversionException($"Material component mask {mask} exceeds input width {value.Width}");
        if (value.MirColumnVector && mask == "rgba"[..value.Width]) return value;
        return new Expr($"({value.Code}).{mask}", mask.Length, ZeroConstant: value.ZeroConstant,
                UnsignedInt: value.UnsignedInt, SignedInt: value.SignedInt, ShaderBoolean: value.ShaderBoolean);
    }
}
