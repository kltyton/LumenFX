namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private SlabSubstrate CompileSlabVerticalBlend(Context context, SourceObject node,
        HashSet<(int Context, int Expression, int Output)> path, Expr? outerThickness, bool upperLayer)
    {
        var properties = node.Properties;
        Expr thickness = SubstrateFloat(context, properties, "Thickness", .01);
        var top = CompileSubstrateSurface(context, properties["Top"], path, true, thickness, true) as SlabSubstrate;
        var bottom = CompileSubstrateSurface(context, properties["Base"], path, true, outerThickness, upperLayer) as SlabSubstrate;
        if (top is null || bottom is null)
            throw new ConversionException($"Substrate vertical parameter blending requires Slab operands: {context.Asset.Name}:{node.Name}");

        SubstrateInclude("simple_volume_lut");
        SubstrateInclude("parameter_blending");
        SubstrateInclude("vertical_layering");
        Expr view = CameraVector();
        Expr topNoV = Shared(new Expr($"clamp(dot({top.WorldNormal.Code}, {view.Code}), 0.0, 1.0)", 1));
        Expr topF0 = BakeSlabF90(top);
        Expr topMfp = Shared(new Expr($"(max({top.MeanFreePath.Code} * (100.0 / max(1e-10, {top.Thickness.Code})), vec3(0.0)) * 0.01)", 3));
        Expr topAlbedo = Shared(new Expr($"lfx_simple_volume_punctual(LfxSlabPunctualVolume, {topMfp.Code}, {top.Diffuse.Code}, {topNoV.Code}, 1.0)", 3));
        Expr transmittance = Shared(new Expr($"lfx_slab_vertical_transmittance({topMfp.Code}, {topF0.Code}.g, {topNoV.Code})", 3));
        Expr topCoverage = Shared(Saturate(top.Coverage));
        Expr bottomCoverage = Shared(Saturate(bottom.Coverage));
        Expr factors = Shared(new Expr($"lfx_slab_vertical_coverage({topCoverage.Code}, {bottomCoverage.Code})", 3));
        Expr bottomFactor = Shared(new Expr($"(({factors.Code}.x * {transmittance.Code} + {factors.Code}.y) / max(1e-10, {factors.Code}.z))", 3));
        Expr topFactor = Shared(new Expr($"({topCoverage.Code} / max(1e-10, {factors.Code}.z))", 1));
        Expr mix = Shared(new Expr($"clamp(({factors.Code}.x * dot({transmittance.Code}, vec3(0.33)) + {factors.Code}.y)"
            + $" / max(1e-10, {factors.Code}.z), 0.0, 1.0)", 1));
        Expr normal = Shared(new Expr($"normalize(mix({bottom.WorldNormal.Code}, {top.WorldNormal.Code}, 0.5 * {topCoverage.Code}))", 3));
        Expr Combine(Expr a, Expr b) => new($"({topFactor.Code} * {a.Code} + {bottomFactor.Code} * {b.Code})", 3);
        Expr Mix(Expr a, Expr b) => Shared(SlabMix(a, b, mix));
        Expr fuzzAmount = top.FuzzAmount.ZeroConstant && bottom.FuzzAmount.ZeroConstant ? Constant(0) : Mix(top.FuzzAmount, bottom.FuzzAmount);
        Expr secondRoughness = Mix(QuantizedHaze(top.SecondRoughness, false), QuantizedHaze(bottom.SecondRoughness, false));
        Expr secondWeight = top.SecondWeight.ZeroConstant ? Constant(0)
            : Mix(QuantizedHaze(top.SecondWeight, true), QuantizedHaze(bottom.SecondWeight, true));
        Expr combinedThickness = Shared(new Expr($"({bottom.Thickness.Code} + {topCoverage.Code} * {top.Thickness.Code})", 1));
        return new SlabSubstrate(Shared(Saturate(Combine(topAlbedo, bottom.Diffuse))),
            Shared(Saturate(Combine(topF0, BakeSlabF90(bottom)))), Cast(Constant(1), 3),
            Mix(top.Roughness, bottom.Roughness), Constant(1), normal, Constant(0), null, secondRoughness, secondWeight,
            SlabCoatUnion(top.SimpleCoat, bottom.SimpleCoat), BlendSlabCoatNormal(top, bottom, mix),
            Shared(Saturate(Combine(top.FuzzColor, bottom.FuzzColor))), fuzzAmount, Mix(top.FuzzRoughness, bottom.FuzzRoughness),
            Shared(Arithmetic(bottom.MeanFreePath, mix, "*")), combinedThickness, bottom.RequiresDeferredMedium, true, false, 0,
            Shared(Combine(top.Emissive, bottom.Emissive)), new Expr(factors.Code + ".z", 1)) {
            DecalOutputs = top.DecalOutputs | bottom.DecalOutputs, UpperLayer = upperLayer,
            Wrap = bottom.Wrap is { } wrap ? wrap with { MeanFreePath = Shared(Arithmetic(wrap.MeanFreePath, mix, "*")), PhaseAnisotropy = Constant(0) } : null
        };
    }
}
