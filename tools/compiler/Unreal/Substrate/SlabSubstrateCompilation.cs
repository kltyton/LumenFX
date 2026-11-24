using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record SlabSubstrate(Expr Diffuse, Expr F0, Expr F90, Expr Roughness, Expr AmbientOcclusion,
        Expr WorldNormal, Expr Anisotropy, Expr? WorldTangent, Expr SecondRoughness, Expr SecondWeight,
        Expr SimpleCoat, Expr? CoatNormalOct,
        Expr FuzzColor, Expr FuzzAmount, Expr FuzzRoughness,
        Expr MeanFreePath, Expr Thickness, bool RequiresDeferredMedium, bool UsesVolumeLookup,
        bool AuthoredF90, int DecalResponse, Expr Emissive, Expr Coverage) : SubstrateSurface(Emissive, Coverage)
    {
        public int DecalOutputs { get; init; }
        public SlabWrap? Wrap { get; init; }
        public Expr? SpecularProfile { get; init; }
        public bool UpperLayer { get; init; }
        public Expr? VolumePhase { get; init; }
        public SlabDiffusionProfile? DiffusionProfile { get; init; }
        public Expr? DiffusionPhase { get; init; }
    }

    private SlabSubstrate CompileSlabSubstrate(Context context, SourceObject node, Expr thickness, bool upperLayer)
    {
        var properties = node.Properties;
        if (Connected(properties["GlintValue"]))
            throw new ConversionException($"Substrate glints require their deferred closure payload: {context.Asset.Name}:{node.Name}");
        // The Slab expression constructor defaults to Diffusion, with no effect until a profile or MFP is supplied.
        string mediumType = (properties.Value<string>("SubSurfaceType") ?? "MSS_Diffusion").Split("::").Last();
        Expr Read(string name, double defaultValue, int width = 1) => SubstrateFloat(context, properties, name, defaultValue, width);
        Expr anisotropy = Read("Anisotropy", 0);
        Expr? tangent = anisotropy.ZeroConstant ? null : SubstrateWorldBasis(context, properties["Tangent"], true);
        Expr normal = SubstrateWorldNormal(context, properties["Normal"]);
        Expr secondWeight = Read("SecondRoughnessWeight", 0);
        Expr secondRoughness = secondWeight.ZeroConstant ? Constant(0) : Read("SecondRoughness", 0);
        Expr roughness = Read("Roughness", .5);
        Expr fuzzAmount = Read("FuzzAmount", 0);
        Expr fuzzColor = fuzzAmount.ZeroConstant ? Cast(Constant(0), 3) : Read("FuzzColor", 0, 3);
        Expr fuzzRoughness = Connected(properties["FuzzRoughness"]) ? Read("FuzzRoughness", .5) : roughness;
        bool hasMedium = Connected(properties["SSSMFP"]) && mediumType != "MSS_None";
        Expr rawMfp = hasMedium ? Read("SSSMFP", 0, 3) : Cast(Constant(0), 3);
        int profileRef = !upperLayer && !decalCoordinates && !lightFunctionCoordinates && !volumeCoordinates
            && mediumType == "MSS_Diffusion" ? properties.Value<int?>("SubsurfaceProfile") ?? 0 : 0;
        Expr scale = hasMedium || profileRef != 0 ? Read("SSSMFPScale", 1) : Constant(1);
        Expr meanFreePath = hasMedium ? Shared(Arithmetic(rawMfp, Saturate(scale), "*")) : rawMfp;
        SlabDiffusionProfile? profile = profileRef == 0 ? null : CompileSlabDiffusionProfile(context.Asset, profileRef, scale);
        SlabWrap? wrap = !upperLayer && hasMedium && mediumType is ("MSS_Wrap" or "MSS_TwoSidedWrap")
            ? CompileSlabWrap(rawMfp, meanFreePath, thickness, mediumType == "MSS_Wrap" ? 1 : 2, Read("SSSPhaseAnisotropy", 0)) : null;
        return new SlabSubstrate(Read("DiffuseAlbedo", .18, 3), Read("F0", .04, 3), Read("F90", 1, 3),
            roughness, Constant(1), normal, anisotropy, tangent, secondRoughness, secondWeight, Constant(0), null,
            fuzzColor, fuzzAmount, fuzzRoughness,
            meanFreePath, thickness, !upperLayer && library.MaterialPermutation.AdaptiveSubstrate
                && (profile is not null || hasMedium && wrap is null), false, Connected(properties["F90"]), 0,
            Read("EmissiveColor", 0, 3), Constant(1)) {
            DecalOutputs = SlabDecalOutputs(properties, false), Wrap = wrap, SpecularProfile = CompileSlabSpecularProfile(context, node),
            UpperLayer = upperLayer, VolumePhase = upperLayer && hasMedium ? Read("SSSPhaseAnisotropy", 0) : null,
            DiffusionProfile = profile,
            DiffusionPhase = !upperLayer && hasMedium && wrap is null ? Read("SSSPhaseAnisotropy", 0) : null
        };
    }

    private SlabSubstrate CompileSimpleClearCoat(Context context, SourceObject node, Expr thickness)
    {
        var properties = node.Properties;
        Expr Read(string name, double defaultValue, int width = 1) => SubstrateFloat(context, properties, name, defaultValue, width);
        Expr normal = SubstrateWorldNormal(context, properties["Normal"]);
        Expr? coatNormalOct = Connected(properties["BottomNormal"])
            ? CompileSlabCoatNormal(normal, SubstrateWorldNormal(context, properties["BottomNormal"])) : null;
        Expr roughness = Read("Roughness", .5);
        return new SlabSubstrate(Read("DiffuseAlbedo", .18, 3), Read("F0", .04, 3), Cast(Constant(1), 3),
            roughness, Constant(1), normal, Constant(0), null, Read("ClearCoatRoughness", .1), Read("ClearCoatCoverage", 1),
            Constant(1), coatNormalOct, Cast(Constant(0), 3), Constant(0), roughness,
            Cast(Constant(0), 3), thickness, false, false, false, 0, Read("EmissiveColor", 0, 3), Constant(1)) {
            DecalOutputs = SlabDecalOutputs(properties, true)
        };
    }

    private Expr CompileSlabCoatNormal(Expr top, Expr bottom)
    {
        string sign = rasterTwoSided && compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") != false
            ? RasterTwoSidedSign().Code : "1.0";
        SubstrateInclude("slab_coat_normal");
        return Shared(new Expr($"lfx_encode_slab_coat_normal(({top.Code} * {sign}).xzy, ({bottom.Code} * {sign}).xzy)", 2));
    }

    private static int SlabDecalOutputs(JObject properties, bool simpleCoat)
    {
        bool albedo = Connected(properties["DiffuseAlbedo"]);
        bool explicitColorPair = !simpleCoat && (albedo || Connected(properties["F0"]));
        return (albedo || explicitColorPair ? 1 : 0) | (Connected(properties["Normal"]) ? 2 : 0)
            | (explicitColorPair || simpleCoat || Connected(properties["Roughness"]) ? 4 : 0)
            | (Connected(properties["EmissiveColor"]) ? 8 : 0);
    }

    private void AppendSlabSubstrateSurface(StringBuilder shader, SlabSubstrate surface)
    {
        shader.AppendLine("#if LFX_PARTICLE_SURFACE && !LFX_PARTICLE_MOTION");
        shader.AppendLine($"    lfx_write_slab_surface({surface.Diffuse.Code}, {surface.F0.Code}, {surface.F90.Code},");
        shader.AppendLine($"        {surface.Roughness.Code}, {surface.AmbientOcclusion.Code}, ({surface.WorldNormal.Code}).xzy, effectPosition,");
        string tangent = surface.WorldTangent is { } direction ? direction.Code : "vec3(0.0)";
        shader.AppendLine($"        {surface.Anisotropy.Code}, ({tangent}).xzy, {surface.Coverage.Code}, {surface.DecalResponse},");
        shader.AppendLine($"        {surface.SecondRoughness.Code}, {surface.SecondWeight.Code}, {surface.SimpleCoat.Code},"
            + $" {surface.CoatNormalOct?.Code ?? "vec2(0.0)"},");
        shader.Append($"        {surface.FuzzColor.Code}, {surface.FuzzAmount.Code}, {surface.FuzzRoughness.Code}");
        if (surface.Wrap is { } wrap) {
            string thin = compiledMaterialProperties.Value<bool?>("bIsThinSurface") == true ? "true" : "false";
            shader.Append($", {wrap.MeanFreePath.Code}, {wrap.PhaseAnisotropy.Code}, int({wrap.Type.Code}), {thin}");
        } else shader.Append(", vec3(0.0), 0.0, 0, false");
        shader.Append($", {surface.SpecularProfile?.Code ?? "0.0"}");
        shader.AppendLine(");");
        shader.AppendLine("#endif");
    }

    private sealed record TranslucentSlabModel(SubstrateSurface Root, IReadOnlyList<SlabLayer> Layers,
        Expr Coverage, Expr SurfaceTransmittance, bool PreweightedEmissive, string NormalSign);

    private TranslucentSlabModel CompileTranslucentSlabModel(SubstrateSurface surface, Expr ambientOcclusion, bool thinSurface)
    {
        string normalSign = rasterTwoSided && compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") != false
            ? RasterTwoSidedSign().Code : "1.0";
        SlabSubstrate PrepareSlab(SlabSubstrate slab)
        {
            Expr normal = normalSign == "1.0" ? slab.WorldNormal : Shared(new Expr($"({slab.WorldNormal.Code} * {normalSign})", 3));
            return slab with { AmbientOcclusion = ambientOcclusion, WorldNormal = normal, DecalResponse = 0 };
        }
        Expr view = CameraVector();
        (Expr surfaceTransmittance, _) = ComputeTranslucentSlabSurfaceTransmittance(surface, view, thinSurface, normalSign);
        if (surface is LayeredSlabs layered) {
            var preparedLayers = layered.Layers
                .Select(layer => layer with { Slab = PrepareSlab(layer.Slab) })
                .ToArray();
            return new TranslucentSlabModel(layered, preparedLayers, layered.Coverage, surfaceTransmittance, true, normalSign);
        }
        var single = PrepareSlab((SlabSubstrate)surface);
        Expr cov = Shared(Saturate(single.Coverage));
        SlabLayer[] singleLayer = [new(single, Cast(cov, 3), cov, Constant(1), Constant(0), Cast(Constant(1), 3), null, true)];
        return new TranslucentSlabModel(single, singleLayer, single.Coverage, surfaceTransmittance, false, normalSign);
    }

    private (Expr Transmittance, Expr Coverage) ComputeTranslucentSlabSurfaceTransmittance(
        SubstrateSurface surface, Expr view, bool thinSurface, string normalSign)
    {
        if (surface is SlabSubstrate slab) {
            Expr normal = normalSign == "1.0" ? slab.WorldNormal : new Expr($"({slab.WorldNormal.Code} * {normalSign})", 3);
            Expr cov = Shared(Saturate(slab.Coverage));
            if (!thinSurface && slab.MeanFreePath.ZeroConstant) {
                return (Cast(Constant(0), 3), cov);
            }
            Expr noV = Shared(new Expr($"clamp(abs(dot({normal.Code}, {view.Code})) + 1e-5, 0.0, 1.0)", 1));
            Expr fc = Shared(new Expr($"pow(clamp(1.0 - {noV.Code}, 0.0, 1.0), 5.0)", 1));
            Expr f0 = Shared(new Expr($"clamp({slab.F0.Code}, 0.0, 1.0)", 3));
            Expr f90 = Shared(new Expr($"clamp({slab.F90.Code}, 0.0, 1.0)", 3));
            Expr fresnel = Shared(new Expr(
                $"mix({f0.Code}, {f90.Code} * clamp(50.0 * max({f0.Code}.r, max({f0.Code}.g, {f0.Code}.b)), 0.0, 1.0), {fc.Code})", 3));
            Expr mfp = Shared(new Expr($"max({slab.MeanFreePath.Code}, vec3(0.0))", 3));
            Expr thickness = Shared(new Expr($"(max({slab.Thickness.Code}, 0.0001) / {noV.Code})", 1));
            Expr sigma = Shared(new Expr($"mix(vec3(1e6), vec3(1.0) / max({mfp.Code}, vec3(1e-4)), greaterThan({mfp.Code}, vec3(1e-5)))", 3));
            string fuzzScale = slab.FuzzAmount.ZeroConstant ? "1.0"
                : $"(1.0 - clamp({slab.FuzzAmount.Code}, 0.0, 1.0) * clamp(mix(0.12 + 0.58 * clamp({slab.FuzzRoughness.Code}, 0.02, 1.0), 0.45 + 0.52 * clamp({slab.FuzzRoughness.Code}, 0.02, 1.0), pow(1.0 - {noV.Code}, 2.5)), 0.0, 0.98))";
            Expr slabT = Shared(new Expr(
                $"(exp(-{sigma.Code} * {thickness.Code}) * (vec3(1.0) - {fresnel.Code}) * (vec3(1.0) - {fresnel.Code}) * {fuzzScale})", 3));
            return (slabT, cov);
        }
        if (surface is LayeredSlabs layered) {
            Expr innerT, innerCov;
            if (layered.Topology is { } topo) {
                (Expr leftT, Expr leftCov) = ComputeTranslucentSlabSurfaceTransmittance(topo.Left, view, thinSurface, normalSign);
                (Expr rightT, Expr rightCov) = ComputeTranslucentSlabSurfaceTransmittance(topo.Right, view, thinSurface, normalSign);
                switch (topo.Operator) {
                    case SlabOperator.Horizontal:
                        innerCov = Shared(new Expr($"mix({leftCov.Code}, {rightCov.Code}, {topo.Mix.Code})", 1));
                        innerT = Shared(new Expr(
                            $"(mix({leftCov.Code} * {leftT.Code}, {rightCov.Code} * {rightT.Code}, {topo.Mix.Code}) / max(1e-10, {innerCov.Code}))", 3));
                        break;
                    case SlabOperator.Add:
                        SubstrateInclude("parameter_blending");
                        innerCov = Shared(new Expr($"clamp({leftCov.Code} + {rightCov.Code}, 0.0, 1.0)", 1));
                        Expr leftMix = Shared(new Expr($"lfx_slab_parameter_weight({rightCov.Code}, {leftCov.Code}, 1.0, 1.0)", 1));
                        innerT = Shared(new Expr($"mix({rightT.Code}, {leftT.Code}, {leftMix.Code})", 3));
                        break;
                    default:
                        SubstrateInclude("closure_tree");
                        innerCov = Shared(new Expr($"lfx_slab_layer_coverage({leftCov.Code}, {rightCov.Code})", 1));
                        innerT = Shared(new Expr(
                            $"(lfx_slab_layer_transmission({leftCov.Code}, {rightCov.Code}, {leftT.Code}, {rightT.Code}) / max(1e-10, {innerCov.Code}))", 3));
                        break;
                }
            } else {
                (innerT, innerCov) = ComputeTranslucentSlabSurfaceTransmittance(layered.Layers[0].Slab, view, thinSurface, normalSign);
            }
            if (layered.OutsideWeight is { } outside)
                innerCov = Shared(new Expr($"clamp({innerCov.Code} * clamp({outside.Code}, 0.0, 1.0), 0.0, 1.0)", 1));
            return (innerT, innerCov);
        }
        return (Cast(Constant(0), 3), Constant(0));
    }

    private void AppendTranslucentSlabColor(StringBuilder shader, TranslucentSlabModel model, string mode)
    {
        shader.AppendLine($"    float lfxSlabCoverage = clamp({(mode == "BLEND_AlphaComposite" ? "opacity" : model.Coverage.Code)}, 0.0, 1.0);");
        shader.AppendLine("    vec3 lfxSlabLuminance = emission;");
        if (mode == "BLEND_Additive") {
            shader.AppendLine("    fragColor = vec4(lfxSlabLuminance * lfx_material_fog().a, 0.0);");
            return;
        }
        if (mode == "BLEND_AlphaComposite") {
            shader.AppendLine("    fragColor = lfx_apply_material_fog(vec4(lfxSlabLuminance, lfxSlabCoverage));");
            return;
        }
        shader.AppendLine($"    vec3 lfxSlabTransmittance = clamp({model.SurfaceTransmittance.Code}, 0.0, 1.0);");
        if (mode is "BLEND_Modulate" or "BLEND_ColoredTransmittanceOnly") {
            shader.AppendLine("    fragColor = vec4(mix(vec3(1.0), lfxSlabTransmittance, lfx_material_fog().a * lfxSlabCoverage), 1.0);");
            return;
        }
        shader.AppendLine("    vec4 lfxSlabFog = lfx_material_fog();");
        shader.AppendLine("    vec3 lfxSlabAdd = lfxSlabCoverage * lfxSlabFog.rgb + lfxSlabFog.a * lfxSlabLuminance;");
        shader.AppendLine("    vec3 lfxSlabMul = mix(vec3(1.0), lfxSlabFog.a * lfxSlabTransmittance, lfxSlabCoverage);");
        if (mode == "BLEND_TranslucentColoredTransmittance") {
            shader.AppendLine("""
#if LFX_SUBSTRATE_MODULATION_ONLY && !LFX_PARTICLE_MOTION
    fragColor = vec4(lfxSlabMul, 1.0);
#elif LFX_SUBSTRATE_DUAL_SOURCE && !LFX_PARTICLE_MOTION
    fragColor = vec4(lfxSlabAdd, 0.0);
    lfxDualTransmittance = vec4(lfxSlabMul, 1.0);
#else
    float lfxSlabAlpha = clamp(1.0 - dot(lfxSlabMul, vec3(1.0 / 3.0)), 0.0, 1.0);
    fragColor = vec4(lfxSlabAdd, lfxSlabAlpha);
#endif
""");
            return;
        }
        shader.AppendLine("    float lfxSlabAlpha = clamp(1.0 - dot(lfxSlabMul, vec3(1.0 / 3.0)), 0.0, 1.0);");
        shader.AppendLine(mode == "BLEND_AlphaHoldout"
            ? "    fragColor = vec4(0.0, 0.0, 0.0, lfxSlabAlpha);"
            : "    fragColor = vec4(lfxSlabAdd, lfxSlabAlpha);");
    }
}
