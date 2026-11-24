using System.Globalization;
using System.Text;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr PeriodicWorldOrigin(Expr scale)
    {
        materialViewUsed |= 64;
        bool powerOfTwo = !scale.ParameterLiteral && double.TryParse(scale.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out double constant)
                && (BitConverter.SingleToInt32Bits((float)constant) & 0x007fffff) == 0;
        return new Expr($"lfx_periodic_world_origin{(powerOfTwo ? "_pow2" : "")}({Cast(scale, 3).Code})", 3);
    }

    private void AppendPeriodicWorldMath(StringBuilder shader)
    {
        if ((MaterialDeclarationViewInputs & 64) == 0) return;
        if (precision.ForceFloatOperations) {
            shader.AppendLine("vec3 lfx_periodic_world_origin(vec3 scale) { return fract(MaterialPreViewTranslationHigh.xyz * (vec3(1.0) / scale)) * scale; }");
            shader.AppendLine("vec3 lfx_periodic_world_origin_pow2(vec3 scale) { return lfx_fmod(MaterialPreViewTranslationHigh.xyz, scale); }");
            return;
        }
        shader.AppendLine("void lfx_periodic_two_product(vec3 lhs, vec3 rhs, out vec3 high, out vec3 low) {");
        if (precision.SupportsFma) {
            shader.AppendLine("precise vec3 product = lhs * rhs; high = product; low = fma(lhs, rhs, -product);");
        } else {
            shader.AppendLine("precise vec3 px = lhs * 4097.0; precise vec3 hx = (lhs - px) + px; precise vec3 tx = lhs - hx;");
            shader.AppendLine("precise vec3 py = rhs * 4097.0; precise vec3 hy = (rhs - py) + py; precise vec3 ty = rhs - hy;");
            shader.AppendLine("precise vec3 p = hx * hy; precise vec3 q = hx * ty + tx * hy; precise vec3 z = p + q;");
            shader.AppendLine("precise vec3 e = ((p - z) + q) + tx * ty; precise vec3 sum = z + e; high = sum; low = e - (sum - z);");
        }
        shader.AppendLine("}");
        shader.AppendLine("vec3 lfx_periodic_world_origin(vec3 scale) { vec3 inverseScale = vec3(1.0) / scale; vec3 high, low; lfx_periodic_two_product(MaterialPreViewTranslationHigh.xyz, inverseScale, high, low); low += MaterialPreViewTranslationLow.xyz * inverseScale; return fract(fract(high) + fract(low)) * scale; }");
        shader.AppendLine("vec3 lfx_periodic_world_origin_pow2(vec3 scale) { return fract(fract(MaterialPreViewTranslationHigh.xyz / scale) + fract(MaterialPreViewTranslationLow.xyz / scale)) * scale; }");
    }
}
