using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private const int SubstrateHairSurfaceModel = 13;

    private sealed record HairSubstrate(Expr BaseColor, Expr Scatter, Expr Specular, Expr Roughness,
        Expr Backlit, Expr Fiber, Expr Emissive, Expr Coverage) : SubstrateSurface(Emissive, Coverage);

    private HairSubstrate CompileSubstrateHair(Context context, JObject properties)
        => new(SubstrateFloat(context, properties, "BaseColor", 0, 3), SubstrateFloat(context, properties, "Scatter", 0),
            SubstrateFloat(context, properties, "Specular", .5), SubstrateFloat(context, properties, "Roughness", .5),
            SubstrateFloat(context, properties, "Backlit", 0), SubstrateWorldBasis(context, properties["Tangent"], true),
            SubstrateFloat(context, properties, "EmissiveColor", 0, 3), Constant(1));

    private DefaultLitSurface CompileHairSurface(HairSubstrate hair, Expr ao)
        => new(hair.BaseColor, hair.Scatter, hair.Specular, hair.Roughness, ao, hair.Fiber, Constant(0), null,
            CompileSurfaceDecalResponse(), SubstrateHairSurfaceModel,
            $"vec4(0.0, 0.0, {hair.Backlit.Code}, {hair.Coverage.Code})", null, null, null);
}
