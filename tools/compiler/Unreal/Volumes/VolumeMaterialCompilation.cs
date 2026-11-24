using System.Text;
using LumenFx.Compiler.Unreal.Attributes;
using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private const string VolumeNoiseSource = "/Engine/EngineMaterials/FastBlueNoise_scalar_128x128x64.FastBlueNoise_scalar_128x128x64";
    private const string VolumeBakeNoiseSource = "/Engine/EngineMaterials/FastBlueNoise_vec2_128x128x64.FastBlueNoise_vec2_128x128x64";
    private readonly Dictionary<SamplerKey, string> sparseVolumeSamplers = new();
    private sealed record VolumeSubstrate(Expr Extinction, Expr Albedo, Expr Emissive, Expr Coverage)
        : SubstrateSurface(Emissive, Coverage);

    private CompiledMaterial CompileVolumeMaterial(SourceAsset asset, SourceObject material, string sourceObject)
    {
        string domain = (material.Properties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last();
        if (domain != "MD_Volume") throw new ConversionException($"Niagara volume renderer requires a volume material: {sourceObject}");
        var data = material.Properties["EditorOnlyData"] is { } editor ? asset.Object(editor.Value<int>()) : material;
        var context = new Context(nextContext++, asset, new Dictionary<int, FunctionInputBinding>());
        Expr extinction, emission, albedo;
        if (Connected(data.Properties["FrontMaterial"])) {
            if (CompileSubstrateSurface(context, data.Properties["FrontMaterial"], new()) is not VolumeSubstrate volume)
                throw new ConversionException($"Volume Front Material requires a Substrate Volumetric Fog Cloud BSDF: {sourceObject}");
            extinction = volume.Extinction;
            emission = volume.Emissive;
            albedo = volume.Albedo;
        } else {
            bool unlit = compiledShadingModel == "MSM_Unlit";
            extinction = unlit ? Constant(0) : MaterialInput(context, data, MaterialAttribute.SubsurfaceColor,
                () => DefaultAttribute(MaterialAttribute.SubsurfaceColor)).Value;
            emission = MaterialInput(context, data, MaterialAttribute.EmissiveColor, () => Constant(0)).Value;
            albedo = unlit ? Constant(0) : MaterialInput(context, data, MaterialAttribute.BaseColor,
                () => DefaultAttribute(MaterialAttribute.BaseColor)).Value;
        }

        var header = MaterialShaderHeader();
        var shader = new StringBuilder("#version 450\n#define LFX_VOLUME_COMPUTE 1\n")
            .Append(header.AsSpan(header.IndexOf('\n') + 1));
        if (viewProjectionUsed) shader.AppendLine("layout(std140) uniform Projection { mat4 ProjMat; };");
        shader.AppendLine("layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; vec4 EffectTimeData; };");
        AppendMaterialCollections(shader, false);
        AppendMaterialView(shader);
        AppendMaterialPassSwitchHelpers(shader, vertex: false);
        AppendVirtualTextureReadDeclarations(shader);
        shader.AppendLine("#moj_import <lumenfx:niagara_volume_material.glsl>");
        AppendSceneColorLookup(shader);
        AppendTextureSamplers(shader);
        AppendSparseVolumeSamplers(shader);
        MaterialShaderMath.Append(shader);
        AppendMaterialNoise(shader);
        AppendLargeWorldMath(shader);
        if (volumeMaterialAffineUsed) AppendPrimitiveMaterialAffine(shader);
        AppendPeriodicWorldMath(shader);
        foreach (var sampler in UsedTextureSamplers()) AppendSampler(shader, sampler);
        AppendPixelCoordinates(shader);
        foreach (string function in functions) shader.AppendLine(function);
        shader.AppendLine("LfxVolumeMaterial lfx_evaluate_volume(vec3 localPosition, vec3 translatedPosition) {");
        shader.AppendLine("    lfx_set_volume_sample(localPosition, translatedPosition);");
        foreach (string expression in expressions) shader.AppendLine("    " + expression);
        shader.AppendLine($"    return LfxVolumeMaterial(clamp({Cast(extinction, 3).Code}, 0.0, 65000.0),");
        shader.AppendLine($"        clamp({Cast(emission, 3).Code}, 0.0, 65000.0), clamp({Cast(albedo, 3).Code}, 0.0, 1.0));");
        shader.AppendLine("}");
        shader.AppendLine("#moj_import <lumenfx:niagara_volume_integrate.glsl>");
        samplerSources["VolumeBlueNoiseSampler"] = VolumeNoiseSource;
        samplerSources["VolumeBakeNoiseSampler"] = VolumeBakeNoiseSource;
        renderTargetTextures.Add(VolumeNoiseSource);
        renderTargetTextures.Add(VolumeBakeNoiseSource);
        return new CompiledMaterial(shader.ToString(), CompiledTextureBindings(),
                new Dictionary<string, TextureSettings>(textureSettings, StringComparer.Ordinal), "premultiplied", false, false,
                dynamicDefaults ?? new[] { 1d, 1d, 1d, 1d }, false, false, "before_dof", dynamicSlots,
                false, depthProjectionUsed, sceneDepthUsed, false) {
            SourceObject = sourceObject, MaterialView = materialViewUsed, ReceivesDecals = false, SceneColor = sceneColorUsed,
            RuntimeParameters = runtimeParameters.ToArray(), RuntimeTextures = CompiledRuntimeTextures(),
            TextureSamplerSources = CompiledTextureSamplerSources(),
            VolumeCompute = true,
            SparseVolumeTextures = sparseVolumeSamplers.ToDictionary(entry => entry.Value, entry => entry.Key.Source),
            MaterialCollections = materialCollections.ToArray(),
            VirtualTextureReads = virtualTextureReads.Values.ToArray(),
            FontSamplers = fontSamplers.Values.ToArray(),
            TextureParameterSamplers = CompiledTextureParameterSamplers(),
            FontAssets = new Dictionary<string, IReadOnlyDictionary<int, string>>(fontAssets, SourceNameOrder.Instance),
            RenderTargetTextures = new HashSet<string>(renderTargetTextures, StringComparer.Ordinal)
        };
    }

    private VolumeSubstrate CompileSubstrateVolume(Context context, JObject properties)
    {
        string baseShadingModel = (compiledMaterialProperties.Value<string>("ShadingModel") ?? "MSM_DefaultLit").Split("::").Last();
        bool emissiveOnly = compiledShadingModel != baseShadingModel ? compiledShadingModel == "MSM_Unlit"
            : properties.Value<bool?>("bEmissiveOnly") == true;
        Expr extinction = emissiveOnly ? Constant(0) : SubstrateFloat(context, properties, "Extinction", 0, 3);
        Expr albedo = emissiveOnly ? Constant(0) : SubstrateFloat(context, properties, "Albedo", 0, 3);
        // The heterogeneous volume material sampler reads these three inlined BSDF values.
        return new VolumeSubstrate(extinction, albedo, SubstrateFloat(context, properties, "EmissiveColor", 0, 3), Constant(1));
    }

    private Expr SparseVolumeTexture(Context context, JObject properties)
    {
        if (!volumeCoordinates) throw new ConversionException("Sparse volume textures require the volume material pipeline");
        string? name = properties.Value<string>("ParameterName");
        string? path = name is null ? null : ParameterValue(context, InstanceParameterType.SparseVolumeTexture, name)?.Value<string>();
        path ??= ObjectPropertyReaderCompiler.ObjectPath(context.Asset, properties["SparseVolumeTexture"]);
        if (string.IsNullOrEmpty(path)) throw new ConversionException("Sparse volume texture expression requires an asset");
        var key = new SamplerKey(path, name is null ? null : SourceNameOrder.Canonical(name),
            name is null ? "" : context.ParameterAssociation, name is null ? -1 : context.ParameterIndex);
        if (!sparseVolumeSamplers.TryGetValue(key, out string? sampler))
            sparseVolumeSamplers.Add(key, sampler = "SparseVolume" + sparseVolumeSamplers.Count);
        return new Expr(sampler, 0, true, MaterialSamplerType.LinearColor, TextureDimensions: 0);
    }

    private Expr SampleSparseVolume(Context context, JObject properties, int output)
    {
        if (output is not (0 or 1)) throw new ConversionException($"Invalid sparse volume attribute output {output}");
        Expr sampler = Input(context, properties["TextureObject"], () => SparseVolumeTexture(context, properties));
        if (!sampler.Sampler || sampler.TextureDimensions != 0)
            throw new ConversionException("Sparse volume sample requires a sparse volume texture object");
        string id = sampler.Code;
        Expr uvw = Cast(Input(context, properties["Coordinates"], () => new Expr("lfxVolumeCoordinates", 3)), 3);
        string mode = (properties.Value<string>("MipValueMode") ?? "TMVM_None").Split("::").Last();
        Expr mip = mode is "TMVM_MipBias" or "TMVM_MipLevel"
            ? Cast(Input(context, properties["MipValue"], () => Constant(SourceAsset.Number(properties["ConstMipValue"]))), 1) : Constant(0);
        string level = mode switch {
            "TMVM_None" or "TMVM_MipBias" => $"lfx_sparse_mip({id}_PagesAndOffset, vec3(0.0), vec3(0.0), {mip.Code})",
            "TMVM_MipLevel" => mip.Code,
            "TMVM_Derivative" => $"lfx_sparse_mip({id}_PagesAndOffset, {Cast(RequiredInput(context, properties, "CoordinatesDX"), 3).Code}, "
                + $"{Cast(RequiredInput(context, properties, "CoordinatesDY"), 3).Code}, 0.0)",
            _ => throw new ConversionException($"Unknown sparse volume mip mode {mode}")
        };
        string address = (properties.Value<string>("SamplerSource") ?? "SSM_FromTextureAsset").Split("::").Last() switch {
            "SSM_FromTextureAsset" or "0" => $"{id}_Address.xyz",
            "SSM_Wrap_WorldGroupSettings" or "1" => "ivec3(0)",
            "SSM_Clamp_WorldGroupSettings" or "SSM_TerrainWeightmapGroupSettings" or "2" or "3" => "ivec3(1)",
            var unknown => throw new ConversionException($"Unknown sparse volume sampler source {unknown}")
        };
        return Shared(new Expr($"lfx_sample_sparse({id}_Table, {id}_{(output == 0 ? "A" : "B")}, "
            + $"{id}_PagesAndOffset, {id}_AtlasAndMip, {uvw.Code}, {address}, {level})", 4));
    }

    private void AppendSparseVolumeSamplers(StringBuilder shader)
    {
        if (sparseVolumeSamplers.Count == 0) return;
        shader.AppendLine("#moj_import <lumenfx:niagara_sparse_volume.glsl>");
        foreach (var id in sparseVolumeSamplers.Values)
        {
            shader.AppendLine($"layout(std140) uniform {id}_Uniforms {{ uvec4 {id}_PagesAndOffset; uvec4 {id}_AtlasAndMip; ivec4 {id}_Address; }};");
            shader.AppendLine($"uniform usampler3D {id}_Table;");
            shader.AppendLine($"uniform sampler3D {id}_A;");
            shader.AppendLine($"uniform sampler3D {id}_B;");
        }
    }
}
