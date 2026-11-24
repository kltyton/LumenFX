using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr SubstrateTransmittanceToMfp(Context context, JObject properties, int output)
    {
        Expr thickness = SubstrateFloat(context, properties, "Thickness", .01);
        if (output == 1) return thickness;
        RequireSubstrateOutput(output);
        Expr transmission = SubstrateFloat(context, properties, "TransmittanceColor", .5, 3);
        SubstrateInclude("media");
        return new Expr($"lfx_substrate_transmittance_mfp({transmission.Code}, {thickness.Code})", 3);
    }

    private Expr SubstrateMetalness(Context context, JObject properties, int output)
    {
        RequireSubstrateOutput(output);
        Expr color = SubstrateFloat(context, properties, "BaseColor", .18, 3);
        Expr metal = Shared(Saturate(SubstrateFloat(context, properties, "Metallic", 0)));
        if (output == 0) return SlabDiffuseAlbedo(color, metal);
        Expr specular = SubstrateFloat(context, properties, "Specular", .5);
        return SlabMetalnessF0(color, metal, specular);
    }

    private static Expr SlabDiffuseAlbedo(Expr color, Expr metallic)
        => new($"({color.Code} - {color.Code} * {metallic.Code})", 3);

    private static Expr SlabMetalnessF0(Expr color, Expr metallic, Expr specular)
        => new($"mix(vec3(0.08 * {specular.Code}), {color.Code}, {metallic.Code})", 3);

    private Expr SubstrateHaziness(Context context, JObject properties, int output)
    {
        RequireSubstrateOutput(output);
        Expr roughness = Saturate(SubstrateFloat(context, properties, "BaseRoughness", .1));
        SubstrateInclude("haze");
        if (output == 0) return new Expr($"lfx_substrate_haze_roughness({roughness.Code})", 1);
        Expr haziness = Saturate(SubstrateFloat(context, properties, "Haziness", .5));
        return new Expr($"lfx_substrate_haze_weight({roughness.Code}, {haziness.Code})", 1);
    }

    private Expr SubstrateThinFilm(Context context, JObject properties, int output)
    {
        RequireSubstrateOutput(output);
        Expr normal = SubstrateWorldNormal(context, properties["Normal"]);
        Expr view = CameraVector();
        Expr f0 = SubstrateFloat(context, properties, "F0", .04, 3);
        Expr f90 = SubstrateFloat(context, properties, "F90", 1, 3);
        Expr thickness = SubstrateFloat(context, properties, "Thickness", 1);
        Expr ior = SubstrateFloat(context, properties, "IOR", 1.44);
        SubstrateInclude("thin_film");
        Expr reflectance = Shared(new Expr(
            $"lfx_substrate_thin_film_f0(dot({normal.Code}, {view.Code}), {f0.Code}, {f90.Code}, {thickness.Code}, {ior.Code})", 3));
        return output == 0 ? reflectance : new Expr($"max({reflectance.Code}, {f90.Code})", 3);
    }

    private Expr SubstrateWorldNormal(Context context, JToken? connection)
        => SubstrateWorldBasis(context, connection, false);

    private Expr SubstrateWorldBasis(Context context, JToken? connection, bool tangent)
    {
        if (!Connected(connection)) return VertexBasis(tangent);
        return SubstrateWorldBasis(Cast(DemoteLargeWorld(Input(context, connection, () => Constant(0))), 3));
    }

    private Expr SubstrateWorldBasis(Expr normal)
    {
        if (compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") == false)
            return new Expr($"normalize({normal.Code})", 3);
        string direction = (int)library.MaterialPermutation.EffectiveFeatureLevel >= (int)MaterialFeatureInput.Sm5
            ? $"normalize({normal.Code})" : normal.Code;
        if (decalCoordinates)
            return new Expr($"lfx_decal_tangent_normal({direction}).xzy", 3);
        return new Expr($"normalize({MaterialTangentBasis()} * {direction})", 3);
    }

    private Expr SubstrateFloat(Context context, JObject properties, string input, double fallback, int components = 1)
        => Cast(DemoteLargeWorld(Input(context, properties[input], () => Constant(fallback))), components);

    private void SubstrateInclude(string file)
    {
        string directive = $"#moj_import <lumenfx:substrate/{file}.glsl>";
        if (!functions.Contains(directive, StringComparer.Ordinal)) functions.Add(directive);
    }

    private static void RequireSubstrateOutput(int output)
    {
        if (output is < 0 or > 1) throw new ConversionException($"Invalid Substrate numeric output {output}");
    }
}
