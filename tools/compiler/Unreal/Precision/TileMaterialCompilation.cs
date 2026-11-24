using System.Text;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr AbsolutePosition(string translatedCentimeters, bool? previousFrame = null)
    {
        materialViewUsed |= 8;
        string view = (previousFrame ?? compilingPreviousFrame) ? "Motion" : "Material";
        return new Expr($"lfx_lwc_subtract({translatedCentimeters}, LfxLWC3(-{view}WorldTile.xyz, {view}PreViewTranslation.xyz))",
                3, LargeWorld: true);
    }

    private Expr WorldCameraPosition()
    {
        materialViewUsed |= 8;
        string view = compilingPreviousFrame ? "Motion" : "Material";
        return new Expr($"LfxLWC3({view}WorldTile.xyz, -{view}PreViewTranslation.xyz)", 3, LargeWorld: true);
    }

    private Expr WorldViewPosition()
    {
        // Tile-offset view history explicitly stores the previous camera origin as the previous view origin.
        if (compilingPreviousFrame) return WorldCameraPosition();
        materialViewUsed |= 24;
        return new Expr("LfxLWC3(MaterialWorldTile.xyz, MaterialWorldViewOrigin.xyz)", 3, LargeWorld: true);
    }

    private string MaterialShaderHeader()
    {
        var shader = new StringBuilder(virtualTextureFeedbackStacks.Count != 0 || materialTextureQueryLevelsUsed ? "#version 430\n"
                : (MaterialDeclarationViewInputs & 72) != 0 || doubleVectorParameterUsed || collectionLwcUsed || customLwcUsed
                    || materialLwcTileUsed || materialTextureGatherUsed || materialTextureShapes.ContainsValue(MaterialTextureShape.CubeArray)
                ? "#version 400\n" : "#version 330\n");
        AppendRuntimeParameters(shader);
        return shader.ToString();
    }

    private bool materialLwcTileUsed;

    private void AppendLargeWorldMath(StringBuilder shader)
    {
        if ((MaterialDeclarationViewInputs & 8) != 0 || doubleVectorParameterUsed || collectionLwcUsed
                || customLwcUsed || materialLwcTileUsed) LargeWorldTileMath.Append(shader);
        AppendDoubleVectorMath(shader);
    }

    private Expr TruncateLargeWorld(Expr value) => precision.TruncateMode is 1 or 2 ? DemoteLargeWorld(value) : value;

    private static Expr DemoteLargeWorld(Expr value) => value.LargeWorld
            ? value with { RawCode = value.Code, LargeWorld = false } : value;

    private static bool IsUnsignedArithmetic(Expr a, Expr b)
        => !a.LargeWorld && !b.LargeWorld && (a.UnsignedInt && (b.UnsignedInt || b.Width == 1)
                || b.UnsignedInt && a.Width == 1);

    private Expr Arithmetic(Expr a, Expr b, string operation)
    {
        bool column = UsesMirTranslation && MirCommonOrientation(a, b);
        if (UsesMirTranslation && a.IsInteger && b.IsInteger)
            return AsMirOrientation(MirIntegralBinary(a, b, operation), column);
        if (!UsesMirTranslation && IsUnsignedArithmetic(a, b)) {
            int unsignedWidth = ArithmeticWidth(a, b);
            if (a.UnsignedInt && b.UnsignedInt) {
                Expr left = CastUnsigned(a, unsignedWidth), right = CastUnsigned(b, unsignedWidth);
                return ZeroGradient(new Expr($"({left.Code} {operation} {right.Code})", unsignedWidth, UnsignedInt: true));
            }
            // The legacy translator emits the mixed HLSL operation and stores it in the result's uint type.
            Expr leftFloat = Cast(a, unsignedWidth), rightFloat = Cast(b, unsignedWidth);
            return ZeroGradient(AsUInt(new Expr($"({leftFloat.Code} {operation} {rightFloat.Code})", unsignedWidth)));
        }
        a = AsFloat(a); b = AsFloat(b);
        bool relative = operation == "-" && a.LargeWorld && b.LargeWorld && a.Width == b.Width;
        int width = UsesMirTranslation ? Math.Max(a.Width, b.Width) : ArithmeticWidth(a, b);
        a = Cast(a, width); b = Cast(b, width);
        if (!a.LargeWorld && !b.LargeWorld) {
            Expr value = new($"({a.Code} {operation} {b.Code})", width, Uniform: a.Uniform && b.Uniform, MirColumnVector: column);
            return WithBinaryGradient(value, a, b, (left, right) => operation switch {
                "+" or "-" => $"({left} {operation} {right})",
                "*" => $"(({left}) * ({b.Code}) + ({a.Code}) * ({right}))",
                "/" => $"((({left}) * ({b.Code}) - ({a.Code}) * ({right})) / (({b.Code}) * ({b.Code})))",
                _ => throw new ConversionException($"Invalid material arithmetic operation {operation}")
            });
        }
        string function = operation switch {
            "+" => "add", "-" => "subtract", "*" => "multiply", "/" => "divide",
            _ => throw new ConversionException($"Invalid large-world arithmetic operation {operation}")
        };
        Expr result = new($"lfx_lwc_{function}({a.RawCode}, {b.RawCode})", width, LargeWorld: true,
                Uniform: a.Uniform && b.Uniform, MirColumnVector: column);
        if (AnalyticDerivatives) {
            string QuotientGradient(string dx, string dy)
            {
                string reciprocalSquare = b.LargeWorld
                        ? $"lfx_lwc_rcp(lfx_lwc_multiply({b.RawCode}, {b.RawCode}))"
                        : $"(1.0 / (({b.Code}) * ({b.Code})))";
                string Scale(Expr value) => value.LargeWorld
                        ? $"lfx_lwc_demote(lfx_lwc_multiply({value.RawCode}, {reciprocalSquare}))"
                        : $"(({value.Code}) * {reciprocalSquare})";
                return $"(({Scale(b)}) * ({dx}) + (-{Scale(a)}) * ({dy}))";
            }
            result = WithBinaryGradient(result, a, b, (dx, dy) => operation switch {
                "+" or "-" => $"(({dx}) {operation} ({dy}))",
                "*" => $"(({dx}) * ({b.Code}) + ({a.Code}) * ({dy}))",
                "/" => QuotientGradient(dx, dy),
                _ => throw new ConversionException($"Invalid large-world derivative operation {operation}")
            });
        }
        return relative && precision.TruncateMode == 2 ? DemoteLargeWorld(result) : result;
    }

    private Expr Interpolate(Expr a, Expr b, Expr alpha)
    {
        a = AsFloat(a); b = AsFloat(b); alpha = AsFloat(alpha);
        int width = ArithmeticWidth(a, b);
        a = Cast(a, width); b = Cast(b, width);
        bool largeWorld = a.LargeWorld || b.LargeWorld;
        Expr result = largeWorld ? new Expr($"lfx_lwc_lerp({Promote(a)}, {Promote(b)}, {alpha.Code})", width, LargeWorld: true)
                : new Expr($"mix({a.Code}, {b.Code}, {alpha.Code})", width);
        if (largeWorld && !AnalyticDerivatives) return result;
        string delta = largeWorld ? $"lfx_lwc_demote(lfx_lwc_subtract({Promote(b)}, {Promote(a)}))" : $"(({b.Code}) - ({a.Code}))";
        string? Gradient(string? left, string? right, string? weight) => left is null || right is null || weight is null
                ? null : $"(mix({left}, {right}, {alpha.Code}) + ({weight}) * {delta})";
        return result with { DerivativeX = Gradient(a.DerivativeX, b.DerivativeX, alpha.DerivativeX),
                DerivativeY = Gradient(a.DerivativeY, b.DerivativeY, alpha.DerivativeY) };
    }

    private static string Promote(Expr value) => value.LargeWorld ? value.RawCode : $"lfx_lwc_promote({value.Code})";

    private static Expr ArithmeticResult(Expr value, int width, bool largeWorld)
    {
        value = Cast(value, width);
        return largeWorld && !value.LargeWorld ? value with { RawCode = Promote(value), LargeWorld = true } : value;
    }

    private static Expr ArithmeticConstant(double value, int width, bool largeWorld) => ArithmeticResult(Constant(value), width, largeWorld);

    private static Expr LargeWorldCast(Expr value, int width)
    {
        Expr tile = Cast(new Expr($"({value.RawCode}).Tile", value.Width), width);
        Expr offset = Cast(new Expr($"({value.RawCode}).Offset", value.Width), width);
        return new Expr($"LfxLWC{width}({tile.Code}, {offset.Code})", width, ZeroConstant: value.ZeroConstant, LargeWorld: true);
    }

    private static Expr LargeWorldSwizzle(Expr value, string mask)
    {
        Expr tile = Swizzle(new Expr($"({value.RawCode}).Tile", value.Width), mask);
        Expr offset = Swizzle(new Expr($"({value.RawCode}).Offset", value.Width), mask);
        return new Expr($"LfxLWC{mask.Length}({tile.Code}, {offset.Code})", mask.Length,
                ZeroConstant: value.ZeroConstant, LargeWorld: true);
    }

    private static Expr LargeWorldAppend(Expr a, Expr b)
    {
        int width = a.Width + b.Width;
        string Tile(Expr value) => value.LargeWorld ? $"({value.RawCode}).Tile" : $"{Type(value.Width)}(0.0)";
        string Offset(Expr value) => value.LargeWorld ? $"({value.RawCode}).Offset" : value.Code;
        return new Expr($"LfxLWC{width}(vec{width}({Tile(a)}, {Tile(b)}), vec{width}({Offset(a)}, {Offset(b)}))",
                width, LargeWorld: true);
    }

    private static Expr PrecisionUnary(Expr value, string function)
    {
        value = AsFloat(value);
        if (!value.LargeWorld) return new Expr($"{function}({value.Code})", value.Width, MirColumnVector: value.MirColumnVector);
        if (function is "dFdx" or "dFdy") return new Expr(
                $"({function}(({value.RawCode}).Tile) * LFX_LWC_TILE_SIZE + {function}(({value.RawCode}).Offset))",
                value.Width, MirColumnVector: value.MirColumnVector);
        string? largeFunction = function switch {
            "abs" => "abs", "ceil" => "ceil", "floor" => "floor", "roundEven" => "round", "trunc" => "trunc",
            "sign" => "sign", "fract" => "frac_demote", "sqrt" => "sqrt", "normalize" => "normalize",
            "sin" or "cos" or "tan" or "asin" or "acos" or "atan" => function, _ => null
        };
        return largeFunction is null ? new Expr($"{function}({value.Code})", value.Width, MirColumnVector: value.MirColumnVector)
                : new Expr($"lfx_lwc_{largeFunction}({value.RawCode})", value.Width,
                        LargeWorld: function is "abs" or "ceil" or "floor" or "roundEven" or "trunc",
                        MirColumnVector: value.MirColumnVector);
    }

    private Expr ExtremumValue(Expr a, Expr b, string function)
    {
        bool mir = UsesMirTranslation;
        bool compatibility = mir && !UsesMirStrictProfile;
        bool column = mir && (compatibility ? a.MirColumnVector : MirCommonOrientation(a, b));
        if (mir && a.IsInteger && (b.IsInteger || compatibility))
            return AsMirOrientation(MirIntegralBinary(a, compatibility ? CastSigned(b, a.Width) : b, function), column);
        if (!mir && a.UnsignedInt) {
            Expr right = CastUnsigned(b, a.Width);
            return ZeroGradient(new Expr($"{function}({a.Code}, {right.Code})", a.Width, UnsignedInt: true));
        }
        a = AsFloat(a); b = AsFloat(b);
        if (mir) {
            int width = compatibility ? a.Width : Math.Max(a.Width, b.Width);
            a = Cast(a, width);
            b = Cast(compatibility && !a.LargeWorld ? DemoteLargeWorld(b) : b, width);
        } else b = Coerce(b, a.Width);
        bool large = a.LargeWorld || mir && b.LargeWorld;
        Expr result = large
                ? new Expr($"lfx_lwc_{function}({Promote(a)}, {Promote(b)})", a.Width, LargeWorld: true, MirColumnVector: column)
                : new Expr($"{function}({a.Code}, {b.Code})", a.Width, MirColumnVector: column);
        if (!AnalyticDerivatives) return result;
        bool minimum = function == "min";
        string ordered = large
                ? $"lfx_lwc_{(minimum ? "less" : "greater")}({Promote(a)}, {Promote(b)})"
                : a.Width == 1 ? $"{a.Code} {(minimum ? "<" : ">")} {b.Code}"
                : $"{(minimum ? "lessThan" : "greaterThan")}({a.Code}, {b.Code})";
        return WithBinaryGradient(result, a, b, (dx, dy) => SelectGradient(ordered, dx, dy, a.Width));
    }

    private static Expr Saturate(Expr value) => value.LargeWorld
            ? new Expr($"lfx_lwc_saturate({value.RawCode})", value.Width)
            : new Expr($"clamp({value.Code}, 0.0, 1.0)", value.Width);

    private Expr PrecisionConditionalValue(Expr a, Expr b, Expr greater, Expr? equal, Expr less,
            double threshold, int width, int compareWidth)
    {
        a = Cast(a, compareWidth); b = Cast(b, compareWidth);
        string left = a.Code, right = b.Code;
        string ordered, outside;
        if (a.LargeWorld || b.LargeWorld) {
            ordered = $"lfx_lwc_greater_equal({Promote(a)}, {Promote(b)})";
            Expr difference = Arithmetic(a, b, "-");
            string absolute = Promote(PrecisionUnary(difference, "abs"));
            outside = $"lfx_lwc_greater({absolute}, {Promote(Cast(Constant(threshold), compareWidth))})";
        } else {
            ordered = compareWidth == 1 ? $"({left} >= {right})" : $"greaterThanEqual({left}, {right})";
            outside = compareWidth == 1 ? $"(abs({left} - {right}) > {Number(threshold)})"
                    : $"greaterThan(abs({left} - {right}), vec{compareWidth}({Number(threshold)}))";
        }
        greater = Cast(greater, width); less = Cast(less, width);
        if (equal is not null) equal = Cast(equal, width);
        bool large = greater.LargeWorld || less.LargeWorld || equal?.LargeWorld == true;
        string Value(Expr value) => large ? Promote(value) : value.Code;
        string Select(string condition, string yes, string no) => large ? $"lfx_lwc_select({condition}, {yes}, {no})"
                : SelectGradient(condition, yes, no, compareWidth);
        string result = Select(ordered, Value(greater), Value(less));
        if (equal is not null) result = Select(outside, result, Value(equal));
        Expr expression = new(result, width, LargeWorld: large);
        if (!AnalyticDerivatives) return expression;
        string? Gradient(string? gt, string? lt, string? eq)
        {
            if (gt is null || lt is null || (equal is not null && eq is null)) return null;
            string zero = ZeroDerivative(width);
            if (gt == zero && lt == zero && (equal is null || eq == zero)) return zero;
            string selected = SelectGradient(ordered, gt, lt, compareWidth);
            return eq is null ? selected : SelectGradient(outside, selected, eq, compareWidth);
        }
        return expression with {
            DerivativeX = Gradient(greater.DerivativeX, less.DerivativeX, equal?.DerivativeX),
            DerivativeY = Gradient(greater.DerivativeY, less.DerivativeY, equal?.DerivativeY)
        };
    }
}
