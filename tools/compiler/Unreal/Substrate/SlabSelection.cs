namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private SlabSubstrate CompileSlabSelect(Context context, SourceObject node,
        HashSet<(int Context, int Expression, int Output)> path, Expr? thickness, bool upperLayer)
    {
        var properties = node.Properties;
        var first = CompileSubstrateSurface(context, properties["A"], path, true, thickness, upperLayer) as SlabSubstrate;
        var second = CompileSubstrateSurface(context, properties["B"], path, true, thickness, upperLayer) as SlabSubstrate;
        if (first is null || second is null)
            throw new ConversionException($"Substrate Select requires supported lit operands: {context.Asset.Name}:{node.Name}");

        Expr input = SubstrateFloat(context, properties, "SelectValue", 0);
        string threshold = Number(SourceAsset.Number(properties["Threshold"], .5));
        string useSecond = $"({input.Code} > {threshold})";
        Expr Select(Expr a, Expr b) => Shared(new Expr($"({useSecond} ? {b.Code} : {a.Code})", a.Width));
        Expr? tangent = first.WorldTangent is { } a && second.WorldTangent is { } b
            ? Select(b, a) : first.WorldTangent ?? second.WorldTangent;
        Expr fuzz = first.FuzzAmount.ZeroConstant && second.FuzzAmount.ZeroConstant
            ? Constant(0) : Select(first.FuzzAmount, second.FuzzAmount);
        return new SlabSubstrate(Select(first.Diffuse, second.Diffuse), Select(first.F0, second.F0),
            Select(first.F90, second.F90), Select(first.Roughness, second.Roughness), Constant(1),
            Select(first.WorldNormal, second.WorldNormal), Select(first.Anisotropy, second.Anisotropy), tangent,
            Select(first.SecondRoughness, second.SecondRoughness), Select(first.SecondWeight, second.SecondWeight),
            Select(first.SimpleCoat, second.SimpleCoat), first.CoatNormalOct is null && second.CoatNormalOct is null ? null
                : Select(first.CoatNormalOct ?? Cast(Constant(0), 2), second.CoatNormalOct ?? Cast(Constant(0), 2)),
            Select(first.FuzzColor, second.FuzzColor), fuzz, Select(first.FuzzRoughness, second.FuzzRoughness),
            Select(first.MeanFreePath, second.MeanFreePath), Select(first.Thickness, second.Thickness),
            first.RequiresDeferredMedium || second.RequiresDeferredMedium, first.UsesVolumeLookup || second.UsesVolumeLookup,
            first.AuthoredF90 || second.AuthoredF90, 0, Select(first.Emissive, second.Emissive), Select(first.Coverage, second.Coverage)) {
            DecalOutputs = first.DecalOutputs | second.DecalOutputs, Wrap = SelectSlabWrap(first.Wrap, second.Wrap, Select),
            DiffusionProfile = SelectSlabDiffusionProfiles(first.DiffusionProfile, second.DiffusionProfile, Select),
            DiffusionPhase = first.DiffusionPhase is null && second.DiffusionPhase is null ? null
                : Select(first.DiffusionPhase ?? Constant(0), second.DiffusionPhase ?? Constant(0)),
            SpecularProfile = first.SpecularProfile is null && second.SpecularProfile is null ? null
                : Select(first.SpecularProfile ?? Constant(0), second.SpecularProfile ?? Constant(0)),
            UpperLayer = upperLayer, VolumePhase = upperLayer
                ? Select(first.VolumePhase ?? Constant(0), second.VolumePhase ?? Constant(0)) : null
        };
    }
}
