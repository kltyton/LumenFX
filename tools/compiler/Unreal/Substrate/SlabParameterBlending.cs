namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private SlabSubstrate CompileSlabParameterBlend(Context context, SourceObject node,
        HashSet<(int Context, int Expression, int Output)> path, Expr? thickness, bool upperLayer)
    {
        var properties = node.Properties;
        bool add = node.ClassName == "MaterialExpressionSubstrateAdd";
        // The add operator blends bases from B to A. Horizontal mixing blends from background to foreground.
        var left = CompileSubstrateSurface(context, properties[add ? "A" : "Background"], path, true, thickness, upperLayer) as SlabSubstrate;
        var right = CompileSubstrateSurface(context, properties[add ? "B" : "Foreground"], path, true, thickness, upperLayer) as SlabSubstrate;
        var from = add ? right : left;
        var to = add ? left : right;
        if (from is null || to is null)
            throw new ConversionException($"Substrate parameter blending requires Slab operands: {context.Asset.Name}:{node.Name}");

        SubstrateInclude("parameter_blending");
        Expr mix = add ? Constant(0) : Saturate(SubstrateFloat(context, properties, "Mix", .5));
        Expr blend = Shared(new Expr(add
            ? $"lfx_slab_parameter_weight({from.Coverage.Code}, {to.Coverage.Code}, 1.0, 1.0)"
            : $"lfx_slab_parameter_weight({from.Coverage.Code}, {to.Coverage.Code}, 1.0 - {mix.Code}, {mix.Code})", 1));
        Expr coverage = Shared(add ? Saturate(Arithmetic(from.Coverage, to.Coverage, "+"))
            : SlabMix(from.Coverage, to.Coverage, mix));
        Expr Blend(Expr a, Expr b) => Shared(SlabMix(a, b, blend));
        Expr BlendColor(Expr a, Expr b) => add ? Shared(Saturate(Arithmetic(a, b, "+"))) : Blend(a, b);
        Expr BlendNormal(Expr a, Expr b) => Shared(new Expr($"normalize({SlabMix(a, b, blend).Code})", 3));
        Expr? tangent = from.WorldTangent is { } first && to.WorldTangent is { } second
            ? BlendNormal(first, second) : from.WorldTangent ?? to.WorldTangent;
        Expr emission = add ? Shared(Arithmetic(from.Emissive, to.Emissive, "+")) : Blend(from.Emissive, to.Emissive);
        Expr roughness = Blend(from.Roughness, to.Roughness);
        Expr secondRoughness = Blend(QuantizedHaze(from.SecondRoughness, false), QuantizedHaze(to.SecondRoughness, false));
        Expr secondWeight = Blend(QuantizedHaze(from.SecondWeight, true), QuantizedHaze(to.SecondWeight, true));
        Expr fuzzAmount = from.FuzzAmount.ZeroConstant && to.FuzzAmount.ZeroConstant ? Constant(0) : Blend(from.FuzzAmount, to.FuzzAmount);
        return new SlabSubstrate(BlendColor(from.Diffuse, to.Diffuse), BlendColor(BakeSlabF90(from), BakeSlabF90(to)),
            Cast(Constant(1), 3), roughness, Constant(1), BlendNormal(from.WorldNormal, to.WorldNormal),
            Blend(from.Anisotropy, to.Anisotropy), tangent, secondRoughness, secondWeight,
            SlabCoatUnion(from.SimpleCoat, to.SimpleCoat), BlendSlabCoatNormal(from, to, blend),
            BlendColor(from.FuzzColor, to.FuzzColor), fuzzAmount, Blend(from.FuzzRoughness, to.FuzzRoughness),
            Blend(from.MeanFreePath, to.MeanFreePath), Blend(from.Thickness, to.Thickness),
            from.RequiresDeferredMedium || to.RequiresDeferredMedium, from.UsesVolumeLookup || to.UsesVolumeLookup, false, 0, emission, coverage) {
            DecalOutputs = from.DecalOutputs | to.DecalOutputs, Wrap = BlendSlabWrap(from.Wrap, to.Wrap, blend, add),
            DiffusionProfile = BlendSlabDiffusionProfiles(from.DiffusionProfile, to.DiffusionProfile, mix, blend, add),
            DiffusionPhase = from.DiffusionPhase is null && to.DiffusionPhase is null ? null
                : add ? Constant(0) : Blend(from.DiffusionPhase ?? Constant(0), to.DiffusionPhase ?? Constant(0)),
            UpperLayer = upperLayer, VolumePhase = upperLayer && !add
                ? Blend(from.VolumePhase ?? Constant(0), to.VolumePhase ?? Constant(0)) : null
        };
    }

    private Expr SlabCoatUnion(Expr first, Expr second)
        => first.ZeroConstant ? second : second.ZeroConstant ? first : Shared(new Expr($"max({first.Code}, {second.Code})", 1));

    private Expr? BlendSlabCoatNormal(SlabSubstrate first, SlabSubstrate second, Expr mix)
    {
        if (first.CoatNormalOct is null && second.CoatNormalOct is null) return null;
        Expr oct = SlabMix(first.CoatNormalOct ?? Cast(Constant(0), 2), second.CoatNormalOct ?? Cast(Constant(0), 2), mix);
        return Shared(new Expr($"lfx_quantize_slab_coat_normal({oct.Code})", 2));
    }

    private Expr BakeSlabF90(SlabSubstrate slab)
    {
        if (!slab.AuthoredF90 && slab.SpecularProfile is null) return slab.F0;
        if (slab.AuthoredF90) SubstrateInclude("parameter_blending");
        Expr camera = CameraVector();
        string noV = $"dot({slab.WorldNormal.Code}, {camera.Code})";
        Expr reflectivity = slab.AuthoredF90 ? Shared(new Expr(
            $"lfx_slab_bake_f90({slab.F0.Code}, {slab.F90.Code}, {slab.Roughness.Code}, {noV})", 3)) : slab.F0;
        return slab.SpecularProfile is { } profile ? Shared(new Expr(
            $"({reflectivity.Code} * lfx_specular_profile_light({profile.Code}, clamp({noV}, 0.0, 1.0), 1.0, vec2(1.0)))", 3)) : reflectivity;
    }

    private static Expr QuantizedHaze(Expr value, bool weight)
    {
        string maximum = weight ? "127.0" : "255.0";
        return new Expr($"(floor(clamp({value.Code}, 0.0, 1.0) * {maximum}) / {maximum})", 1);
    }

    private static Expr SlabMix(Expr from, Expr to, Expr mix)
        => new($"mix({from.Code}, {to.Code}, {mix.Code})", from.Width);
}
