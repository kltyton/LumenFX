using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private bool AnalyticDerivatives => naniteCoordinates && !compilingVertex;

    private static string ZeroDerivative(int width) => width == 1 ? "0.0" : $"{Type(width)}(0.0)";

    private static Expr UniformExpression(string code, int width, bool largeWorld = false)
        => ZeroGradient(new Expr(code, width, LargeWorld: largeWorld, Uniform: true));

    private static Expr ZeroGradient(Expr value)
    {
        string zero = ZeroDerivative(value.Width);
        return value with { DerivativeX = zero, DerivativeY = zero };
    }

    private static Expr WithUnaryGradient(Expr result, Expr source, Func<string, string> gradient)
    {
        string Axis(string derivative) => derivative == ZeroDerivative(source.Width) ? ZeroDerivative(result.Width) : gradient(derivative);
        return result with {
            DerivativeX = source.DerivativeX is { } x ? Axis(x) : null,
            DerivativeY = source.DerivativeY is { } y ? Axis(y) : null
        };
    }

    private static Expr WithBinaryGradient(Expr result, Expr left, Expr right, Func<string, string, string> gradient)
    {
        string Axis(string a, string b) => a == ZeroDerivative(left.Width) && b == ZeroDerivative(right.Width)
                ? ZeroDerivative(result.Width) : gradient(a, b);
        return result with {
            DerivativeX = left.DerivativeX is { } lx && right.DerivativeX is { } rx ? Axis(lx, rx) : null,
            DerivativeY = left.DerivativeY is { } ly && right.DerivativeY is { } ry ? Axis(ly, ry) : null
        };
    }

    private static string SelectGradient(string condition, string whenTrue, string whenFalse, int width) =>
        width == 1 ? $"(({condition}) ? ({whenTrue}) : ({whenFalse}))" : $"mix({whenFalse}, {whenTrue}, {condition})";

    private static string DotExpression(string a, string b, int width) =>
        width == 1 ? $"(({a}) * ({b}))" : $"dot({a}, {b})";

    private Expr MaterialAbs(Expr value)
    {
        Expr result = PrecisionUnary(value, "abs");
        if (!AnalyticDerivatives) return result;
        string number = Type(value.Width);
        string zero = $"{number}(0.0)", one = $"{number}(1.0)";
        string positive = value.LargeWorld ? $"lfx_lwc_greater_equal({value.RawCode}, lfx_lwc_promote({zero}))"
                : value.Width == 1 ? $"{value.Code} >= 0.0" : $"greaterThanEqual({value.Code}, {zero})";
        string sign = SelectGradient(positive, one, $"-{one}", value.Width);
        return WithUnaryGradient(result, value, derivative => $"({sign}) * ({derivative})");
    }

    private Expr MaterialFraction(Expr value)
    {
        Expr result = PrecisionUnary(value, "fract");
        return AnalyticDerivatives ? result with { DerivativeX = value.DerivativeX, DerivativeY = value.DerivativeY } : result;
    }

    private Expr MaterialSaturate(Expr value)
    {
        Expr result = Saturate(value);
        if (!AnalyticDerivatives || (value.DerivativeX is null && value.DerivativeY is null)) return result;
        string number = Type(value.Width);
        string zero = $"{number}(0.0)", one = $"{number}(1.0)";
        if (value.LargeWorld) {
            string difference = $"lfx_lwc_subtract({value.RawCode}, {result.Code})";
            string equal = value.Width == 1 ? $"(({difference}).Tile * LFX_LWC_TILE_SIZE == -({difference}).Offset)"
                    : $"equal(({difference}).Tile * LFX_LWC_TILE_SIZE, -({difference}).Offset)";
            return WithUnaryGradient(result, value, derivative => SelectGradient(equal, derivative, zero, value.Width));
        }
        string above = value.Width == 1 ? $"{value.Code} > 0.0" : $"greaterThan({value.Code}, {zero})";
        string below = value.Width == 1 ? $"{value.Code} < 1.0" : $"lessThan({value.Code}, {one})";
        return WithUnaryGradient(result, value, derivative => SelectGradient(above,
                SelectGradient(below, derivative, zero, value.Width), zero, value.Width));
    }

    private Expr MaterialLength(Expr value)
    {
        Expr result = value.LargeWorld ? new Expr($"lfx_lwc_length({value.RawCode})", 1, LargeWorld: true)
                : new Expr(value.Width == 1 ? $"abs({value.Code})" : $"length({value.Code})", 1);
        if (!AnalyticDerivatives || (value.DerivativeX is null && value.DerivativeY is null)) return result;
        Expr square = Shared(MaterialSquaredLength(value));
        string inverse = square.LargeWorld ? $"lfx_lwc_rsqrt(lfx_lwc_max({square.RawCode}, lfx_lwc_promote(0.00001)))"
                : $"inversesqrt(max({square.Code}, 0.00001))";
        return WithUnaryGradient(result, value, derivative => {
            string dot = DotExpression(value.Code, derivative, value.Width);
            return $"(0.5 * {inverse} * (({dot}) + ({dot})))";
        });
    }

    private Expr MaterialNormalize(Expr value)
    {
        if (!AnalyticDerivatives || (value.DerivativeX is null && value.DerivativeY is null))
            return PrecisionUnary(value, "normalize");
        if (value.DerivativeX == ZeroDerivative(value.Width) && value.DerivativeY == ZeroDerivative(value.Width))
            return ZeroGradient(PrecisionUnary(value, "normalize"));
        Expr square = Shared(MaterialSquaredLength(value));
        string reciprocal = square.LargeWorld ? $"lfx_lwc_rsqrt({square.RawCode})" : $"inversesqrt({square.Code})";
        string reciprocalLength = Shared(new Expr(reciprocal, 1)).Code;
        string reciprocalSquare = square.LargeWorld ? $"lfx_lwc_rcp({square.RawCode})" : $"(1.0 / {square.Code})";
        string analytic = value.LargeWorld
                ? $"lfx_lwc_demote(lfx_lwc_multiply({value.RawCode}, {Type(value.Width)}({reciprocalLength})))"
                : $"({reciprocalLength} * {value.Code})";
        Expr result = new($"lfx_material_derivative_value({PrecisionUnary(value, "normalize").Code}, {analytic})", value.Width);
        return WithUnaryGradient(result, value, derivative => {
            string dot = DotExpression(value.Code, derivative, value.Width);
            string slope = $"(-0.5 * {reciprocalLength} * {reciprocalSquare} * (({dot}) + ({dot})))";
            return $"(({slope}) * {value.Code} + {reciprocalLength} * ({derivative}))";
        });
    }

    private static Expr MaterialSquaredLength(Expr value) => value.LargeWorld
            ? new Expr($"lfx_lwc_dot({value.RawCode}, {value.RawCode})", 1, LargeWorld: true)
            : new Expr(DotExpression(value.Code, value.Code, value.Width), 1);

    private Expr DerivativeTrigonometric(Context context, JObject properties, string operation)
    {
        Expr value = RequiredInput(context, properties, "Input");
        float multiplier = TrigonometricMultiplier(properties, operation);
        Expr scaled = multiplier == 1 ? value : Arithmetic(value, Constant(multiplier), "*");
        return DerivativeTrigonometric(scaled, operation);
    }

    private Expr DerivativeTrigonometric(Expr scaled, string operation)
    {
        Expr result = PrecisionUnary(scaled, operation);
        string sin = PrecisionUnary(scaled, "sin").Code;
        string cos = PrecisionUnary(scaled, "cos").Code;
        string derivative = operation switch {
            "sin" => cos,
            "cos" => $"-{sin}",
            "tan" => $"(1.0 / ({cos} * {cos}))",
            _ => throw new ConversionException($"Unsupported derivative trigonometric operation {operation}")
        };
        return WithUnaryGradient(result, scaled, gradient => $"({derivative}) * ({gradient})");
    }

    private Expr DerivativeUnaryFunction(Context context, JObject properties, string inputName, string operation,
            Func<string, string, string> derivativeFactor, bool fast = false)
    {
        Expr value = RequiredInput(context, properties, inputName);
        if (fast && value.Uniform) return UniformExpression($"{operation}({Cast(value, 1).Code})", 1);
        if (!fast && !AnalyticDerivatives) return PrecisionUnary(value, operation);
        string function = fast ? $"lfx_fast_{operation}" : operation;
        string code = $"{function}({value.Code})";
        Expr result = new(code, value.Width, Uniform: value.Uniform);
        if (!fast && value.LargeWorld && operation is ("asin" or "acos" or "atan")) {
            string finite = PrecisionUnary(value, operation).Code;
            result = new Expr($"lfx_material_derivative_value({finite}, {code})", value.Width);
        }
        string factor = derivativeFactor(value.Code, code);
        return AnalyticDerivatives ? WithUnaryGradient(result, value, gradient => $"({factor}) * ({gradient})") : result;
    }

    private Expr DerivativeSqrt(Context context, JObject properties) =>
        MaterialSquareRoot(RequiredInput(context, properties, "Input"));

    private Expr MaterialSquareRoot(Expr value)
    {
        Expr result = PrecisionUnary(value, "sqrt");
        if (!AnalyticDerivatives) return result;
        string inverse = value.LargeWorld
                ? $"lfx_lwc_rsqrt(lfx_lwc_max({value.RawCode}, lfx_lwc_promote({Type(value.Width)}(0.00001))))"
                : $"inversesqrt(max({value.Code}, 0.00001))";
        return WithUnaryGradient(result, value, derivative => $"(0.5 * {inverse}) * ({derivative})");
    }

    private Expr DerivativeExp(Context context, JObject properties, bool baseTwo) => DerivativeUnaryFunction(context, properties, "Input",
            baseTwo ? "exp2" : "exp", (input, value) => baseTwo ? $"({value} * 0.693147)" : $"exp({input})");

    private Expr DerivativeLog(Context context, JObject properties, string inputName, string operation, double scale) =>
            DerivativeUnaryFunction(context, properties, inputName, operation,
                    (input, _) => $"({Number(scale)} / {input})");

    private Expr DerivativeLog10(Context context, JObject properties)
    {
        Expr value = RequiredInput(context, properties, "X");
        Expr result = Logarithm10(value);
        return WithUnaryGradient(result, value, derivative => $"(0.4342945 / {value.Code}) * ({derivative})");
    }

    private Expr DerivativeInverseTrig(Context context, JObject properties, string operation, double sign = 1, bool fast = false) =>
            DerivativeUnaryFunction(context, properties, "Input", operation, (input, _) =>
                    $"({Number(sign)} * inversesqrt(max(1.0 - ({input}) * ({input}), 0.00001)))", fast);

    private Expr DerivativeAtan(Context context, JObject properties, bool fast = false) => DerivativeUnaryFunction(context, properties, "Input",
            "atan", (input, _) => $"(1.0 / (({input}) * ({input}) + 1.0))", fast);

    private Expr DerivativeAtan2(Context context, JObject properties, bool fast = false)
    {
        Expr y = RequiredInput(context, properties, "Y"), x = RequiredInput(context, properties, "X");
        if (fast && y.Uniform && x.Uniform)
            return UniformExpression($"atan({Cast(y, 1).Code}, {Cast(x, 1).Code})", 1);
        int width = ArithmeticWidth(y, x);
        y = Cast(y, width); x = Cast(x, width);
        string function = fast ? "lfx_fast_atan2" : "atan";
        Expr result = new($"{function}({y.Code}, {x.Code})", width, Uniform: y.Uniform && x.Uniform);
        if (!AnalyticDerivatives) return result;
        string denominator = $"(1.0 / (({y.Code}) * ({y.Code}) + ({x.Code}) * ({x.Code})))";
        string dY = $"(({x.Code}) * {denominator})";
        string dX = $"(-({y.Code}) * {denominator})";
        return WithBinaryGradient(result, y, x, (dy, dx) => $"({dY}) * ({dy}) + ({dX}) * ({dx})");
    }
}
