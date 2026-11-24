using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr BlackBody(Context context, JObject properties)
    {
        Expr temperature = Cast(RequiredInput(context, properties, "Temp"), 1);
        string temp = temperature.Code;

        Expr u = Shared(new Expr(
            $"((0.860117757 + 1.54118254e-4 * {temp} + 1.28641212e-7 * {temp} * {temp}) / " +
            $"(1.0 + 8.42420235e-4 * {temp} + 7.08145163e-7 * {temp} * {temp}))", 1));
        Expr v = Shared(new Expr(
            $"((0.317398726 + 4.22806245e-5 * {temp} + 4.20481691e-8 * {temp} * {temp}) / " +
            $"(1.0 - 2.89741816e-5 * {temp} + 1.61456053e-7 * {temp} * {temp}))", 1));

        Expr denominator = Shared(new Expr($"(2.0 * {u.Code} - 8.0 * {v.Code} + 4.0)", 1));
        Expr x = Shared(new Expr($"(3.0 * {u.Code} / {denominator.Code})", 1));
        Expr y = Shared(new Expr($"(2.0 * {v.Code} / {denominator.Code})", 1));
        Expr z = Shared(new Expr($"(1.0 - {x.Code} - {y.Code})", 1));

        Expr X = Shared(new Expr($"(1.0 / {y.Code} * {x.Code})", 1));
        Expr Z = Shared(new Expr($"(1.0 / {y.Code} * {z.Code})", 1));

        // UE's default working space is linear sRGB; these rows match XYZ_2_Linear_sRGB_MAT.
        Expr red = Shared(new Expr(
            $"dot(vec3(3.2409699419, -1.5373831776, -0.4986107603), vec3({X.Code}, 1.0, {Z.Code}))", 1));
        Expr green = Shared(new Expr(
            $"dot(vec3(-0.9692436363, 1.8759675015, 0.0415550574), vec3({X.Code}, 1.0, {Z.Code}))", 1));
        Expr blue = Shared(new Expr(
            $"dot(vec3(0.0556300797, -0.2039769589, 1.0569715142), vec3({X.Code}, 1.0, {Z.Code}))", 1));
        Expr linearRgb = Shared(new Expr($"max(vec3(0.0), vec3({red.Code}, {green.Code}, {blue.Code}))", 3));
        Expr scale = Shared(new Expr($"pow(0.0004 * {temp}, 4.0)", 1));
        return Shared(new Expr($"({linearRgb.Code} * {scale.Code})", 3));
    }
}
