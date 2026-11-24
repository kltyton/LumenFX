namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private bool previousFirstPersonUsed;

    private Expr FirstPersonViewProperty(string property, bool inverse)
    {
        materialViewUsed |= 128;
        previousFirstPersonUsed |= compilingPreviousFrame;
        string view = compilingPreviousFrame ? "Motion" : "Material";
        int width = property == "MEVP_FirstPersonScale" ? 1 : 2;
        string value = property switch {
            "MEVP_FirstPersonFieldOfView" => view + "FirstPersonFov.xy",
            "MEVP_FirstPersonTanHalfFieldOfView" => view + "FirstPersonFov.zw",
            "MEVP_FirstPersonScale" => view + "FirstPersonScale.z",
            _ => throw new ConversionException($"Unknown first-person view property {property}")
        };
        return new Expr(inverse ? $"({(width == 1 ? "1.0" : "vec2(1.0)")} / {value})" : value, width);
    }

    private Expr FirstPersonCoordinate(Expr translated, Expr alpha)
    {
        materialViewUsed |= 128;
        previousFirstPersonUsed |= compilingPreviousFrame;
        string view = compilingPreviousFrame ? "Motion" : "Material";
        Expr blend = Saturate(DemoteLargeWorld(Cast(alpha, 1)));
        string position = translated.Code;
        string transformed = $"(mat3({view}FirstPersonTransform0.xyz, {view}FirstPersonTransform1.xyz, {view}FirstPersonTransform2.xyz) * {position})";
        return new Expr($"mix({position}, {transformed}, {blend.Code})", 3);
    }
}
