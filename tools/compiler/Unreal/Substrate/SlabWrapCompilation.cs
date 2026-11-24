namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record SlabWrap(Expr MeanFreePath, Expr PhaseAnisotropy, Expr Type);

    private SlabWrap CompileSlabWrap(Expr rawMfp, Expr scaledMfp, Expr thickness, int type, Expr phase)
    {
        Expr normalized = Shared(new Expr($"max({scaledMfp.Code} * (100.0 / max(1e-10, {thickness.Code})), vec3(0.0))", 3));
        Expr mode = Shared(new Expr($"(any(greaterThan({rawMfp.Code}, vec3(0.0))) ? {Number(type)} : 0.0)", 1));
        return new SlabWrap(normalized, phase, mode);
    }

    private SlabWrap? BlendSlabWrap(SlabWrap? from, SlabWrap? to, Expr mix, bool add)
    {
        if (from is null && to is null) return null;
        Expr zero = Constant(0);
        Expr mfp = Shared(SlabMix(from?.MeanFreePath ?? Cast(zero, 3), to?.MeanFreePath ?? Cast(zero, 3), mix));
        Expr phase = add ? zero : Shared(SlabMix(from?.PhaseAnisotropy ?? zero, to?.PhaseAnisotropy ?? zero, mix));
        Expr type = from is null ? to!.Type : to is null ? from.Type : Shared(new Expr($"max({from.Type.Code}, {to.Type.Code})", 1));
        return new SlabWrap(mfp, phase, type);
    }

    private static SlabWrap? SelectSlabWrap(SlabWrap? first, SlabWrap? second, Func<Expr, Expr, Expr> select)
    {
        if (first is null && second is null) return null;
        Expr zero = Constant(0);
        return new SlabWrap(select(first?.MeanFreePath ?? Cast(zero, 3), second?.MeanFreePath ?? Cast(zero, 3)),
            select(first?.PhaseAnisotropy ?? zero, second?.PhaseAnisotropy ?? zero), select(first?.Type ?? zero, second?.Type ?? zero));
    }
}
