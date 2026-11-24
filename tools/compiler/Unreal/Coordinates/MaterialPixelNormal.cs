using System.Text;
using LumenFx.Compiler.Unreal.Attributes;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private (Context Context, SourceObject Data)? rasterNormalSource;
    private ResolvedAttribute? rasterNormalInput;
    private Expr? rasterWorldNormal;
    private bool resolvingRasterNormal, rasterTwoSided, rasterNaniteTwoSidedUsed;

    private ResolvedAttribute RasterNormalInput()
    {
        if (resolvingRasterNormal)
            throw new ConversionException("PixelNormalWS and the default ReflectionVectorWS cannot read the normal being compiled");
        if (rasterNormalInput is { } input) return input;
        var source = rasterNormalSource
            ?? throw new ConversionException("The material domain has no raster normal input");
        resolvingRasterNormal = true;
        try {
            bool tangent = compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") != false;
            if (!tangent && compiledMaterialProperties.Value<bool?>("bUseMaterialAttributes") != true
                    && source.Data.Properties["Normal"]?.Value<bool?>("constant") == true)
                return rasterNormalInput = new ResolvedAttribute(DefaultAttribute(MaterialAttribute.Normal), false);
            return rasterNormalInput = MaterialInput(source.Context, source.Data, MaterialAttribute.Normal,
                    () => DefaultAttribute(MaterialAttribute.Normal));
        } finally {
            resolvingRasterNormal = false;
        }
    }

    private Expr PixelWorldNormal()
    {
        if (compilingVertex) throw new ConversionException("PixelNormalWS requires pixel or compute shading");
        if (volumeCoordinates) {
            // Normal is inactive in the Volume domain and the sample parameters have a zero TwoSidedSign.
            return Vector([0, 0, compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") == false ? 1 : 0]);
        }
        if (rasterWorldNormal is not null) return rasterWorldNormal;
        Expr normal = Cast(RasterNormalInput().Value, 3);
        if (compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") == false)
            return rasterWorldNormal = Shared(new Expr($"normalize({normal.Code})", 3));
        string direction = normal.Code;
        if ((int)library.MaterialPermutation.EffectiveFeatureLevel >= (int)MaterialFeatureInput.Sm5)
            direction = $"normalize({direction})";
        if (decalCoordinates)
            return rasterWorldNormal = Shared(new Expr($"lfx_decal_tangent_normal({direction}).xzy", 3));
        return rasterWorldNormal = Shared(new Expr(
            $"(normalize({MaterialTangentBasis()} * {direction}) * {RasterTwoSidedSign().Code})", 3));
    }

    private Expr RasterTwoSidedSign()
    {
        if (compilingVertex) throw new ConversionException("TwoSidedSign requires a raster face");
        if (volumeCoordinates) return Constant(0);
        if (!rasterTwoSided) return Constant(1);
        PrimitiveLinear(MaterialSpace.Local, true, false);
        rasterNaniteTwoSidedUsed = true;
        return new Expr("lfx_material_two_sided_sign()", 1);
    }

    private void AppendRasterTwoSidedSign(StringBuilder shader)
    {
        if (!rasterNaniteTwoSidedUsed) return;
        string transform = PrimitiveMatrix("PrimitiveCurrent", false);
        shader.AppendLine("#if LFX_NANITE_MATERIAL");
        shader.AppendLine("float lfx_material_two_sided_sign() { return lfxNaniteGeometry.twoSidedSign; }");
        shader.AppendLine("#else");
        shader.AppendLine($"float lfx_material_two_sided_sign() {{ return (gl_FrontFacing ? 1.0 : -1.0) * (determinant({transform}) < 0.0 ? -1.0 : 1.0); }}");
        shader.AppendLine("#endif");
    }

    private Expr ReflectionVector(Context context, JObject properties)
    {
        if (compilingVertex) throw new ConversionException("ReflectionVectorWS requires pixel or compute shading");
        bool custom = Connected(properties["CustomWorldNormal"]);
        Expr normal = custom
            ? Cast(RequiredInput(context, properties, "CustomWorldNormal"), 3)
            : PixelWorldNormal();
        string direction = normal.Code;
        if (custom && properties.Value<bool?>("bNormalizeCustomWorldNormal") == true)
            direction = $"normalize({direction})";
        return new Expr($"reflect(-{CameraVector().Code}, {direction})", 3);
    }

    private Expr Fresnel(Context context, JObject properties)
    {
        Expr normal = Input(context, properties["Normal"], PixelWorldNormal);
        Expr exponent = Input(context, properties["ExponentIn"],
            () => Constant(SourceAsset.Number(properties["Exponent"], 5)));
        Expr fraction = Input(context, properties["BaseReflectFractionIn"],
            () => Constant(SourceAsset.Number(properties["BaseReflectFraction"], .04)));
        Expr one = Constant(1);
        Expr facing = ExtremumValue(Constant(0), Dot(normal, CameraVector()), "max");
        Expr distance = MaterialAbs(Arithmetic(one, facing, "-"));
        Expr power = Power(ExtremumValue(distance, Constant(1e-4), "max"), exponent);
        return Arithmetic(Arithmetic(power, Arithmetic(one, fraction, "-"), "*"), fraction, "+");
    }
}
