using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    // EMaterialExpressionOperatorKind in the UE 5.9 source export order.
    private static readonly string[] MirOperatorNames = [
        "BitwiseNot", "Negate", "Not", "Abs", "ACos", "ACosFast", "ACosh", "ASin", "ASinFast", "ASinh",
        "ATan", "ATanFast", "ATanh", "Ceil", "Cos", "Cosh", "Exponential", "Exponential2", "Floor", "Frac",
        "IsFinite", "IsInf", "IsNan", "Length", "Logarithm", "Logarithm10", "Logarithm2", "LWCTile", "Reciprocal",
        "Round", "Rsqrt", "Saturate", "Sign", "Sin", "Sinh", "Sqrt", "Tan", "Tanh", "Transpose", "Truncate",
        "Equals", "GreaterThan", "GreaterThanOrEquals", "LessThan", "LessThanOrEquals", "NotEquals", "And", "Or",
        "Add", "Subtract", "Multiply", "MatrixMultiply", "Divide", "DividePrecise", "Modulo",
        "BitwiseAnd", "BitwiseOr", "BitwiseXor", "BitShiftLeft", "BitShiftRight", "ATan2", "ATan2Fast",
        "Cross", "Distance", "Dot", "Fmod", "Max", "Min", "Pow", "Step", "Clamp", "Lerp", "Select", "Smoothstep"
    ];

    private static string MirOperatorName(JObject properties)
    {
        string source = (properties.Value<string>("Operator") ?? "Add").Split("::").Last();
        if (int.TryParse(source, out int ordinal) && ordinal >= 0 && ordinal < MirOperatorNames.Length)
            return MirOperatorNames[ordinal];
        if (Array.IndexOf(MirOperatorNames, source) >= 0) return source;
        throw new ConversionException($"Unknown material operator {source}");
    }

    private static int MirArity(string op) => op switch {
        "BitwiseNot" or "Negate" or "Not" or "Abs" or "ACos" or "ACosFast" or "ACosh" or "ASin" or "ASinFast"
                or "ASinh" or "ATan" or "ATanFast" or "ATanh" or "Ceil" or "Cos" or "Cosh" or "Exponential"
                or "Exponential2" or "Floor" or "Frac" or "IsFinite" or "IsInf" or "IsNan" or "Length"
                or "Logarithm" or "Logarithm10" or "Logarithm2" or "LWCTile" or "Reciprocal" or "Round" or "Rsqrt"
                or "Saturate" or "Sign" or "Sin" or "Sinh" or "Sqrt" or "Tan" or "Tanh" or "Transpose" or "Truncate" => 1,
        "Clamp" or "Lerp" or "Select" or "Smoothstep" => 3,
        _ => 2
    };

    private static bool IsMirBoolean(Expr value) => value.ShaderBoolean || value.StaticBool.HasValue;

    private static string Component(Expr value, int index) => value.Width == 1 ? value.Code : $"({value.Code}).{"xyzw"[index]}";

    private static Expr WithMirComponents(int width, bool signed, bool boolean, Func<int, string> component)
    {
        string code = width == 1 ? component(0)
                : $"{Type(width, signedInt: signed, shaderBoolean: boolean)}({string.Join(", ", Enumerable.Range(0, width).Select(component))})";
        return new Expr(code, width, SignedInt: signed, ShaderBoolean: boolean);
    }

    private static Expr MirBoolean(Expr value, int width)
    {
        if (value.StaticBool is bool constant)
            return WithMirComponents(width, false, true, _ => constant ? "true" : "false");
        if (!value.ShaderBoolean) throw new ConversionException("A MIR logical operation requires Boolean inputs");
        if (value.Width == width) return value;
        return WithMirComponents(width, false, true,
                i => value.Width == 1 ? value.Code : i < value.Width ? Component(value, i) : "false");
    }

    private static Expr MirLogical(string op, Expr a, Expr? b = null)
    {
        int width = Math.Max(a.Width, b?.Width ?? 1);
        var left = MirBoolean(a, width);
        var right = b is null ? null : MirBoolean(b, width);
        return ZeroGradient(WithMirComponents(width, false, true, i => right is null
                ? $"(!{Component(left, i)})" : $"({Component(left, i)} {op} {Component(right, i)})"));
    }

    private static Expr MirCompare(string op, Expr a, Expr b)
    {
        int width = Math.Max(a.Width, b.Width);
        if (a.LargeWorld || b.LargeWorld) {
            var leftLwc = Promote(Cast(a, width));
            var rightLwc = Promote(Cast(b, width));
            string less = $"lfx_lwc_less({leftLwc}, {rightLwc})";
            string greater = $"lfx_lwc_greater({leftLwc}, {rightLwc})";
            string ge = $"lfx_lwc_greater_equal({leftLwc}, {rightLwc})";
            string le = $"lfx_lwc_greater_equal({rightLwc}, {leftLwc})";
            if (op is "==" or "!=") {
                var lg = new Expr(le, width, ShaderBoolean: true);
                var rg = new Expr(ge, width, ShaderBoolean: true);
                var equal = MirLogical("&&", lg, rg);
                return op == "==" ? equal : MirLogical("!", equal);
            }
            return ZeroGradient(new Expr(op switch { "<" => less, ">" => greater, "<=" => le, _ => ge },
                    width, ShaderBoolean: true));
        }
        bool boolean = IsMirBoolean(a) && IsMirBoolean(b);
        if (boolean && op is not ("==" or "!="))
            throw new ConversionException("A MIR ordering operation requires arithmetic inputs");
        bool signed = !boolean && (a.IsInteger && (b.IsInteger || IsMirBoolean(b)) || b.IsInteger && IsMirBoolean(a));
        if (IsMirBoolean(a)) a = MirBoolean(a, width);
        if (IsMirBoolean(b)) b = MirBoolean(b, width);
        Expr left = boolean ? a : signed ? CastSigned(a, width) : Cast(a, width);
        Expr right = boolean ? b : signed ? CastSigned(b, width) : Cast(b, width);
        return ZeroGradient(WithMirComponents(width, false, true,
                i => $"({Component(left, i)} {op} {Component(right, i)})"));
    }

    private Expr MirUnaryMath(string op, Expr value)
    {
        Expr v = IsMirBoolean(value) ? MirBoolean(value, value.Width) : value;
        string function = op switch {
            "ACos" => "acos", "ACosFast" => "lfx_fast_acos", "ACosh" => "acosh", "ASin" => "asin",
            "ASinFast" => "lfx_fast_asin", "ASinh" => "asinh",
            "ATan" => "atan", "ATanFast" => "lfx_fast_atan", "ATanh" => "atanh", "Ceil" => "ceil", "Cos" => "cos", "Cosh" => "cosh",
            "Exponential" => "exp", "Exponential2" => "exp2", "Floor" => "floor", "Frac" => "fract",
            "Logarithm" => "log", "Logarithm2" => "log2", "Round" => "roundEven", "Rsqrt" => "inversesqrt",
            "Sin" => "sin", "Sinh" => "sinh", "Sqrt" => "sqrt", "Tan" => "tan", "Tanh" => "tanh", "Truncate" => "trunc",
            _ => ""
        };
        if (function.Length != 0) return PrecisionUnary(AsFloat(v), function);
        if (op is "IsFinite" or "IsInf" or "IsNan") {
            Expr f = AsFloat(v);
            return ZeroGradient(WithMirComponents(f.Width, false, true, i => op switch {
                "IsInf" => $"isinf({Component(f, i)})",
                "IsNan" => $"isnan({Component(f, i)})",
                _ => $"(!isinf({Component(f, i)}) && !isnan({Component(f, i)}))"
            }));
        }
        return op switch {
            "Length" when v.LargeWorld => new Expr($"lfx_lwc_length({v.RawCode})", 1, LargeWorld: true),
            "Length" => new Expr($"{(v.Width == 1 ? "abs" : "length")}({AsFloat(v).Code})", 1),
            "Saturate" => Saturate(AsFloat(v)),
            "Reciprocal" => new Expr(v.LargeWorld ? $"lfx_lwc_rcp({v.RawCode})" : $"(1.0 / {AsFloat(v).Code})", v.Width),
            "Logarithm10" => Logarithm10(AsFloat(v)),
            "LWCTile" => MirLargeWorldTile(v),
            _ => throw new ConversionException($"Unsupported MIR unary operand type for {op}")
        };
    }

    private Expr MirBinaryMath(string op, Expr a, Expr b)
    {
        if (op is "And" or "Or") return MirLogical(op == "And" ? "&&" : "||", a, b);
        if (op is "Equals" or "NotEquals" or "GreaterThan" or "GreaterThanOrEquals" or "LessThan" or "LessThanOrEquals")
            return MirCompare(op switch { "Equals" => "==", "NotEquals" => "!=", "GreaterThan" => ">",
                    "GreaterThanOrEquals" => ">=", "LessThan" => "<", _ => "<=" }, a, b);
        if (op == "Dot") return MirDot(a, b);
        if (op == "Cross") return MirCross(a, b);
        int width = Math.Max(a.Width, b.Width);
        var left = Cast(a, width);
        var right = Cast(b, width);
        if (op == "Distance") return Distance(left, right);
        if (op == "Fmod") return new Expr(left.LargeWorld
                ? $"lfx_lwc_fmod({left.RawCode}, {right.Code})" : $"lfx_fmod({left.Code}, {right.Code})", width);
        return op switch {
            "ATan2" => new Expr($"atan({left.Code}, {right.Code})", width),
            "ATan2Fast" => new Expr($"lfx_fast_atan2({left.Code}, {right.Code})", width),
            "Pow" => new Expr($"pow({left.Code}, {right.Code})", width),
            "Step" => new Expr(left.LargeWorld || right.LargeWorld
                    ? $"lfx_lwc_step({Promote(left)}, {Promote(right)})" : $"step({left.Code}, {right.Code})", width),
            _ => throw new ConversionException($"Unsupported MIR binary operand types for {op}")
        };
    }

    private Expr MirMaterialIf(Context context, JObject properties)
    {
        Expr a = Input(context, properties["A"], () => Constant(0));
        Expr b = Input(context, properties["B"], () => Constant(SourceAsset.Number(properties["ConstB"])));
        Expr greater = Input(context, properties["AGreaterThanB"], () => Constant(0));
        Expr less = Input(context, properties["ALessThanB"], () => Constant(0));
        Expr result = MirTernary("Select", MirCompare("<", a, b), less, greater);
        if (!Connected(properties["AEqualsB"])) return result;

        Expr equal = RequiredInput(context, properties, "AEqualsB");
        Expr delta = Arithmetic(a, b, "-");
        Expr absolute = delta.IsInteger
                ? ZeroGradient(new Expr($"abs({delta.Code})", delta.Width, SignedInt: true))
                : PrecisionUnary(delta, "abs");
        Expr threshold = Constant(SourceAsset.Number(properties["EqualsThreshold"], .00001));
        return MirTernary("Select", MirCompare("<=", absolute, threshold), equal, result);
    }

    private Expr MirTernary(string op, Expr a, Expr b, Expr c)
    {
        int width = Math.Max(a.Width, Math.Max(b.Width, c.Width));
        if (op == "Lerp") {
            int lerpWidth = UsesMirStrictProfile ? width : Math.Max(a.Width, b.Width);
            return Interpolate(Cast(a, lerpWidth), Cast(b, lerpWidth), Cast(DemoteLargeWorld(c), lerpWidth));
        }
        if (op == "Clamp") {
            bool strict = UsesMirStrictProfile;
            int clampWidth = strict ? width : a.Width;
            bool clampSigned = a.IsInteger && (!strict || b.IsInteger && c.IsInteger);
            bool large = a.LargeWorld || strict && (b.LargeWorld || c.LargeWorld);
            Expr Adjust(Expr value) => clampSigned ? CastSigned(value, clampWidth)
                    : Cast(large ? value : DemoteLargeWorld(value), clampWidth);
            return ExtremumValue(ExtremumValue(Adjust(a), Adjust(b), "max"), Adjust(c), "min");
        }
        if (op == "Smoothstep")
            return new Expr($"smoothstep({Cast(a, width).Code}, {Cast(b, width).Code}, {Cast(c, width).Code})", width);
        var condition = MirBoolean(a, width);
        if (b.LargeWorld || c.LargeWorld) return new Expr(
                $"lfx_lwc_select({condition.Code}, {Promote(Cast(b, width))}, {Promote(Cast(c, width))})", width, LargeWorld: true);
        bool boolean = IsMirBoolean(b) && IsMirBoolean(c);
        bool signed = !boolean && (b.IsInteger && (c.IsInteger || IsMirBoolean(c)) || c.IsInteger && IsMirBoolean(b));
        Expr yes = boolean ? MirBoolean(b, width) : signed ? CastSigned(b, width) : Cast(b, width);
        Expr no = boolean ? MirBoolean(c, width) : signed ? CastSigned(c, width) : Cast(c, width);
        return WithMirComponents(width, signed, boolean,
                i => $"({Component(condition, i)} ? {Component(yes, i)} : {Component(no, i)})");
    }
}
