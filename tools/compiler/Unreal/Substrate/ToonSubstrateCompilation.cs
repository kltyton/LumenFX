using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private const int SubstrateToonSurfaceModel = 15;
    private const string DefaultToonProfile = "None";
    private readonly Dictionary<string, int> toonProfiles = new(SourceNameOrder.Instance);

    private sealed record ToonSubstrate(Expr BaseColor, Expr Metallic, Expr Specular, Expr Roughness, Expr WorldNormal,
        Expr Anisotropy, Expr? WorldTangent, Expr Profile, Expr PatternUvs, Expr Emissive, Expr Coverage)
        : SubstrateSurface(Emissive, Coverage);

    private ToonSubstrate CompileSubstrateToon(Context context, JObject properties)
    {
        ToonProfileSlot(DefaultToonProfile);
        Expr anisotropy = SubstrateFloat(context, properties, "Anisotropy", 0);
        Expr? tangent = anisotropy.ZeroConstant ? null : SubstrateWorldBasis(context, properties["Tangent"], tangent: true);
        Expr normal = SubstrateWorldBasis(context, properties["Normal"], tangent: false);
        if (rasterTwoSided && compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") != false)
            normal = Shared(new Expr($"({normal.Code} * {RasterTwoSidedSign().Code})", 3));
        Expr pattern = Connected(properties["PatternUVs"]) ? SubstrateFloat(context, properties, "PatternUVs", 0, 2)
            : Arithmetic(Coordinates(new JObject()), Constant(10), "*");
        Expr profile = Connected(properties["ToonProfile"]) ? SubstrateFloat(context, properties, "ToonProfile", 0)
            : ToonProfileSlot(DefaultToonProfile);
        return new ToonSubstrate(SubstrateFloat(context, properties, "BaseColor", .18, 3),
            SubstrateFloat(context, properties, "Metallic", 0), SubstrateFloat(context, properties, "Specular", .5),
            SubstrateFloat(context, properties, "Roughness", .5), normal, anisotropy, tangent, profile, pattern,
            SubstrateFloat(context, properties, "EmissiveColor", 0, 3), Constant(1));
    }

    private DefaultLitSurface CompileToonSurface(ToonSubstrate toon, Expr ao)
        => new(toon.BaseColor, toon.Metallic, toon.Specular, toon.Roughness, ao, toon.WorldNormal, toon.Anisotropy,
            toon.WorldTangent, CompileSurfaceDecalResponse(), SubstrateToonSurfaceModel,
            $"lfx_pack_toon_surface({toon.PatternUvs.Code}, {toon.Profile.Code}, {toon.Coverage.Code})", null, null, null);

    private Expr CompileToonProfileParameter(Context context, JObject properties)
    {
        string name = properties.Value<string>("ParameterName") ?? "ToonProfileParameterName";
        if (SourceNameOrder.Equal(name, "None")) return ToonProfileSlot(DefaultToonProfile);
        string? source = ParameterValue(context, InstanceParameterType.ToonProfile, name)?.Value<string>();
        if (source is null && properties.Value<int?>("ToonProfile") is { } reference && reference != 0)
            source = context.Asset.ObjectPath(reference);
        string resolved = source ?? DefaultToonProfile;
        if (runtimeDynamicParameters || sourceDynamicMaterial)
        {
            ToonProfileSlot(resolved);
            int index = runtimeParameters.FindIndex(parameter => parameter.Kind == "toon_profile"
                && parameter.Association == context.ParameterAssociation && parameter.ParameterIndex == context.ParameterIndex
                && SourceNameOrder.Equal(parameter.Name, name));
            if (index < 0)
            {
                index = runtimeParameters.Count;
                runtimeParameters.Add(new CompiledMaterialParameter(null, "toon_profile", new float[4]) {
                    Name = name, Association = context.ParameterAssociation, ParameterIndex = context.ParameterIndex,
                    DefaultProfile = resolved
                });
            }
            int offset = runtimeParameters.Take(index).Sum(parameter => parameter.Default.Length / 4);
            return UniformExpression($"LfxMaterialParameterValues[{offset}].x", 1);
        }
        return ToonProfileSlot(resolved);
    }

    private Expr SelectToonProfile(Context context, JObject properties)
    {
        Expr a = RequiredInput(context, properties, "A"), b = RequiredInput(context, properties, "B");
        Expr select = SubstrateFloat(context, properties, "SelectValue", 0);
        string threshold = Number(SourceAsset.Number(properties["Threshold"], .5));
        return new Expr($"({select.Code} <= {threshold} ? {Cast(a, 1).Code} : {Cast(b, 1).Code})", 1);
    }

    private Expr ToonProfileSlot(string source)
    {
        if (!toonProfiles.TryGetValue(source, out int index)) {
            index = toonProfiles.Count;
            toonProfiles.Add(source, index);
        }
        return new Expr($"float(LFX_TOON_PROFILE_ID_{index})", 1);
    }

    private string[] ToonProfileSources()
        => toonProfiles.OrderBy(profile => profile.Value).Select(profile => profile.Key).ToArray();
}
