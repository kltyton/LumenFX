using System.Text;
using LumenFx.Compiler.Unreal.Decals;
using LumenFx.Compiler.Unreal.Attributes;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private CompiledMaterial CompileDecalMaterial(SourceAsset asset, SourceObject material, string sourceObject, string? blendOverride)
    {
        string domain = (material.Properties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last();
        if (domain != "MD_DeferredDecal")
        {
            if (sourceObject == DecalRendererCompiler.DefaultMaterial)
                throw new ConversionException($"Default deferred-decal material has invalid domain {domain}: {sourceObject}");
            return new MaterialCompiler(library, textureAliases, MaterialVertexFactory.Decal, precision)
                .ConfigureShaderSources(customEngineSourceRoot, customShaderDirectoryMappings)
                .Compile(DecalRendererCompiler.DefaultMaterial);
        }
        SourceObject data = material.Properties["EditorOnlyData"] is { } editor ? asset.Object(editor.Value<int>()) : material;
        Context context = new(nextContext++, asset, new Dictionary<int, FunctionInputBinding>());
        rasterNormalSource = (context, data);
        string sourceBlend = (blendOverride ?? material.Properties.Value<string>("BlendMode") ?? "BLEND_Opaque").Split("::").Last();
        DecalSubstrate? substrate = null;
        if (Connected(data.Properties["FrontMaterial"])) {
            substrate = CompileSubstrateSurface(context, data.Properties["FrontMaterial"], new()) as DecalSubstrate
                ?? throw new ConversionException($"Deferred decal Front Material requires Convert To Decal: {sourceObject}");
        }
        string blend = sourceBlend switch {
            "BLEND_AlphaComposite" => "alpha_composite",
            "BLEND_Modulate" when substrate is null => "modulate",
            "BLEND_Translucent" or "BLEND_Opaque" or "BLEND_Masked" or "BLEND_Additive" or "BLEND_AlphaHoldout"
                or "BLEND_TranslucentGreyTransmittance" or "BLEND_TranslucentColoredTransmittance"
                or "BLEND_ColoredTransmittanceOnly" or "BLEND_Modulate" => "translucent",
            _ => throw new ConversionException($"Unknown decal blend mode {sourceBlend}: {sourceObject}")
        };
        ResolvedAttribute Read(MaterialAttribute attribute, double fallback)
            => MaterialInput(context, data, attribute, () => Constant(fallback));
        Expr baseColor, metallic, specular, roughness, worldNormal, emissive, coverage, backgroundCoverage;
        int outputs;
        if (substrate is { } front) {
            var slab = front.Slab;
            Expr diffuse = Shared(Saturate(slab.Diffuse));
            Expr f0 = Shared(Saturate(slab.F0));
            Expr peak = Shared(new Expr($"max({f0.Code}.r, max({f0.Code}.g, {f0.Code}.b))", 1));
            metallic = Shared(new Expr($"clamp(({peak.Code} - 0.08) / 0.32, 0.0, 1.0)", 1));
            specular = Saturate(new Expr($"({peak.Code} / 0.08)", 1));
            baseColor = SlabMix(diffuse, f0, metallic);
            roughness = slab.Roughness;
            worldNormal = slab.WorldNormal;
            emissive = front.Emissive;
            coverage = front.Coverage;
            backgroundCoverage = blend == "alpha_composite" ? Read(MaterialAttribute.Opacity, 1).Value : coverage;
            outputs = slab.DecalOutputs;
        } else {
            ResolvedAttribute normalInput = RasterNormalInput();
            worldNormal = PixelWorldNormal();
            ResolvedAttribute baseInput = Read(MaterialAttribute.BaseColor, 0);
            ResolvedAttribute metallicInput = Read(MaterialAttribute.Metallic, 0);
            ResolvedAttribute specularInput = Read(MaterialAttribute.Specular, .5);
            ResolvedAttribute roughnessInput = Read(MaterialAttribute.Roughness, .5);
            ResolvedAttribute emissiveInput = Read(MaterialAttribute.EmissiveColor, 0);
            baseColor = baseInput.Value; metallic = metallicInput.Value; specular = specularInput.Value;
            roughness = roughnessInput.Value; emissive = emissiveInput.Value;
            coverage = backgroundCoverage = Read(MaterialAttribute.Opacity, 1).Value;
            outputs = (baseInput.Connected ? 1 : 0) | (normalInput.Connected ? 2 : 0)
                | (metallicInput.Connected || specularInput.Connected || roughnessInput.Connected ? 4 : 0)
                | (emissiveInput.Connected ? 8 : 0);
        }
        ResolvedAttribute aoInput = Read(MaterialAttribute.AmbientOcclusion, 1);
        if (aoInput.Connected) outputs |= 16;
        if (blend == "alpha_composite") outputs &= ~2;
        StringBuilder shader = new(MaterialShaderHeader());
        shader.AppendLine("#moj_import <minecraft:projection.glsl>");
        shader.AppendLine("layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; vec4 EffectTimeData; };");
        AppendMaterialCollections(shader, false);
        shader.AppendLine("layout(std140) uniform EffectView { vec4 CameraRight; vec4 CameraUp; vec4 Geometry; vec4 DepthTransform; };");
        AppendMaterialView(shader);
        AppendMaterialPassSwitchHelpers(shader, vertex: false);
        AppendVirtualTextureReadDeclarations(shader);
        shader.AppendLine($"#define LFX_DECAL_TEXCOORDS {(decalTexcoordsUsed ? 1 : 0)}");
        shader.AppendLine("#moj_import <lumenfx:decal_material.glsl>");
        AppendSpecularProfileLookup(shader);
        AppendTextureSamplers(shader);
        MaterialShaderMath.Append(shader);
        AppendMaterialNoise(shader);
        AppendLargeWorldMath(shader);
        AppendPeriodicWorldMath(shader);
        if (sphericalOpacityUsed) MaterialShaderMath.AppendSphericalOpacity(shader);
        foreach (var sampler in UsedTextureSamplers()) AppendSampler(shader, sampler);
        foreach (string function in functions) shader.AppendLine(function);
        shader.AppendLine("void main() {");
        shader.AppendLine("    lfx_initialize_decal();");
        foreach (string expression in expressions) shader.AppendLine("    " + expression);
        string emission = Cast(emissive, 3).Code;
        if (substrate is not null || material.Properties.Value<bool?>("bAllowNegativeEmissiveColor") != true)
            emission = $"max({emission}, vec3(0.0))";
        string roughnessValue = substrate is null && material.Properties.Value<bool?>("bFullyRough") == true
            ? "1.0" : $"clamp({Cast(roughness, 1).Code}, 0.0, 1.0)";
        shader.AppendLine($"    lfx_write_decal(clamp({Cast(baseColor, 3).Code}, 0.0, 1.0), ({worldNormal.Code}).xzy,");
        shader.AppendLine($"        vec3(clamp({Cast(metallic, 1).Code}, 0.0, 1.0), clamp({Cast(specular, 1).Code}, 0.0, 1.0), {roughnessValue}),");
        shader.AppendLine($"        {emission}, {Saturate(Cast(coverage, 1)).Code}, {Saturate(Cast(backgroundCoverage, 1)).Code},"
            + $" {Saturate(Cast(aoInput.Value, 1)).Code}, {outputs});");
        AppendVirtualTextureFeedback(shader);
        shader.AppendLine("}");
        return new CompiledMaterial(shader.ToString(), CompiledTextureBindings(),
                new Dictionary<string, TextureSettings>(textureSettings, StringComparer.Ordinal), blend == "alpha_composite" ? "premultiplied" : "alpha", false, false,
                dynamicDefaults ?? new[] { 1d, 1d, 1d, 1d }, false, false, "before_dof", dynamicSlots, false, true, true, false) {
            SourceObject = sourceObject,
            MaterialView = materialViewUsed, RuntimeParameters = runtimeParameters.ToArray(), RuntimeTextures = CompiledRuntimeTextures(),
            TextureSamplerSources = CompiledTextureSamplerSources(),
            MaterialCollections = materialCollections.ToArray(),
            VirtualTextureReads = virtualTextureReads.Values.ToArray(),
            VirtualTextureFeedback = virtualTextureFeedbackStacks.Count != 0,
            Decal = new CompiledDecalMaterial(outputs, blend),
            SpecularProfiles = SpecularProfileSources(),
            SubstratePunctualVolume = substrate?.Slab.UsesVolumeLookup == true,
            FontSamplers = fontSamplers.Values.ToArray(),
            TextureParameterSamplers = CompiledTextureParameterSamplers(),
            FontAssets = new Dictionary<string, IReadOnlyDictionary<int, string>>(fontAssets, SourceNameOrder.Instance),
            RenderTargetTextures = new HashSet<string>(renderTargetTextures, StringComparer.Ordinal)
        };
    }

    private Expr DecalColor(int output)
    {
        Expr rgba = decalCoordinates ? new Expr("DecalColorValue", 4) : new Expr("vec4(1.0)", 4);
        return output switch {
            0 => Swizzle(rgba, "rgb"), 1 => Swizzle(rgba, "r"), 2 => Swizzle(rgba, "g"), 3 => Swizzle(rgba, "b"),
            4 => Swizzle(rgba, "a"), 5 => rgba, _ => throw new ConversionException("Invalid decal color output")
        };
    }

    private Expr DecalDerivative(int output)
    {
        if (output is < 0 or > 1) throw new ConversionException("Invalid decal derivative output");
        if (!decalCoordinates) throw new ConversionException("Decal derivatives require the deferred-decal material domain");
        return new Expr(output == 1 ? "lfx_decal_ddy()" : "lfx_decal_ddx()", 2);
    }

    private Expr DecalMipmapLevel(Context context, JObject properties)
    {
        if (!decalCoordinates) throw new ConversionException("Decal mipmap level requires the deferred-decal material domain");
        Expr size = Input(context, properties["TextureSize"], () => Vector(new[] {
            SourceAsset.Number(properties["ConstWidth"], 256), SourceAsset.Number(properties["ConstHeight"], 256) }));
        if (size.Width != 2) throw new ConversionException("Decal mipmap texture size must have two float components");
        return new Expr($"lfx_decal_mipmap_level({size.Code})", 1);
    }
}
