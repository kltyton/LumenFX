using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record SlabDiffusionProfile(Expr Index, Expr RadiusScale);
    private readonly Dictionary<string, int> slabDiffusionProfiles = new(SourceNameOrder.Instance);

    private SlabDiffusionProfile CompileSlabDiffusionProfile(SourceAsset owner, int reference, Expr scale)
    {
        string source = owner.ObjectPath(reference);
        if (!slabDiffusionProfiles.TryGetValue(source, out int slot)) {
            slot = slabDiffusionProfiles.Count;
            slabDiffusionProfiles.Add(source, slot);
        }
        Expr radius = Shared(Saturate(scale));
        Expr index = Shared(new Expr($"({radius.Code} > 0.0 ? float(LFX_SLAB_SUBSURFACE_PROFILE_{slot}) : -1.0)", 1));
        return new SlabDiffusionProfile(index, radius);
    }

    private SlabDiffusionProfile? BlendSlabDiffusionProfiles(SlabDiffusionProfile? from, SlabDiffusionProfile? to,
        Expr lightingMix, Expr coverageMix, bool add)
    {
        if (from is null && to is null) return null;
        var first = from ?? new SlabDiffusionProfile(Constant(-1), Constant(0));
        var second = to ?? new SlabDiffusionProfile(Constant(-1), Constant(0));
        Expr chooseSecond = Shared(new Expr($"({second.Index.Code} >= 0.0 && ({first.Index.Code} < 0.0 || "
            + (add ? "true" : $"{lightingMix.Code} > 0.5") + ") ? 1.0 : 0.0)", 1));
        Expr index = Shared(SlabMix(first.Index, second.Index, chooseSecond));
        Expr radius = Shared(SlabMix(first.RadiusScale, second.RadiusScale, add ? chooseSecond : coverageMix));
        return new SlabDiffusionProfile(index, radius);
    }

    private SlabDiffusionProfile? SelectSlabDiffusionProfiles(SlabDiffusionProfile? first, SlabDiffusionProfile? second,
        Func<Expr, Expr, Expr> select)
        => first is null && second is null ? null : new SlabDiffusionProfile(
            select(first?.Index ?? Constant(-1), second?.Index ?? Constant(-1)),
            select(first?.RadiusScale ?? Constant(0), second?.RadiusScale ?? Constant(0)));

    private DefaultLitSurface CompileBlendableSlabProfile(SlabSubstrate slab, SlabDiffusionProfile profile)
    {
        Expr f0 = BakeSlabF90(slab);
        Expr peak = Shared(new Expr($"max({f0.Code}.r, max({f0.Code}.g, {f0.Code}.b))", 1));
        Expr metallic = Shared(new Expr($"clamp(({peak.Code} - 0.08) / 0.32, 0.0, 1.0)", 1));
        Expr specular = Shared(new Expr($"clamp({peak.Code} / 0.08, 0.0, 1.0)", 1));
        Expr baseColor = Shared(SlabMix(slab.Diffuse, f0, metallic));
        Expr roughness = slab.SecondWeight.ZeroConstant ? slab.Roughness : Shared(new Expr(
            $"({slab.SimpleCoat.Code} > 0.0 ? {slab.Roughness.Code} : "
            + SlabMix(slab.Roughness, QuantizedHaze(slab.SecondRoughness, false), QuantizedHaze(slab.SecondWeight, true)).Code + ")", 1));
        return new DefaultLitSurface(baseColor, metallic, specular, roughness, slab.AmbientOcclusion, slab.WorldNormal,
            slab.Anisotropy, slab.WorldTangent, slab.DecalResponse, 5,
            $"vec4(0.0, 0.0, 0.0, {profile.RadiusScale.Code})", null, null, null) {
            DynamicProfile = profile.Index
        };
    }
}
