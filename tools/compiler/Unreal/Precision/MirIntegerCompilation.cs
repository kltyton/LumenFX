using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private bool UsesMirTranslation => precision.NewTranslatorMode != 0
        && (compiledMaterialProperties.Value<bool?>("bEnableNewHLSLGeneratorOverridden") == true
                ? compiledMaterialProperties.Value<bool?>("bEnableNewHLSLGenerator") == true
                : precision.NewTranslatorMode == 2);

    private bool UsesMirStrictProfile
    {
        get {
            if (compiledMaterialProperties.Value<bool?>("bMaterialTranslatorProfileOverridden") != true) return precision.MirStrictProfile;
            string profile = (compiledMaterialProperties.Value<string>("MaterialTranslatorProfile") ?? "Compatibility").Split("::").Last();
            return profile switch {
                "0" or "Compatibility" => false, "1" or "Strict" => true,
                _ => throw new ConversionException($"Unknown source material translator profile {profile}")
            };
        }
    }

    private static Expr CastSigned(Expr value, int width)
    {
        if (value.Sampler) throw new ConversionException("A texture cannot be cast to a material integer");
        value = DemoteLargeWorld(value);
        if (!value.SignedInt) value = value with {
            RawCode = $"{Type(value.Width, signedInt: true)}({value.Code})",
            SignedInt = true, UnsignedInt = false, ShaderBoolean = false
        };
        if (value.Width == width) return value;
        string result = width == 1 ? $"({value.Code}).x"
                : value.Width == 1 ? $"ivec{width}({value.Code})"
                : value.Width > width ? $"({value.Code}).{"xyzw"[..width]}"
                : $"ivec{width}({value.Code}, {string.Join(", ", Enumerable.Repeat("0", width - value.Width))})";
        return value with { RawCode = result, Width = width, MirColumnVector = width > 1 && value.MirColumnVector };
    }

    private static Expr MirIntegralBinary(Expr a, Expr b, string operation)
    {
        int width = Math.Max(a.Width, b.Width);
        Expr left = CastSigned(a, width), right = CastSigned(b, width);
        string code = operation switch {
            "min" or "max" => $"{operation}({left.Code}, {right.Code})",
            // Signed GLSL remainder with a negative operand is not portable. MIR uses truncating division.
            "%" => $"({left.Code} - ({left.Code} / {right.Code}) * {right.Code})",
            _ => $"({left.Code} {operation} {right.Code})"
        };
        return ZeroGradient(new Expr(code, width, Uniform: a.Uniform && b.Uniform, SignedInt: true));
    }

    private static Expr MirModulo(Expr a, Expr b)
    {
        if (a.IsInteger && b.IsInteger) return MirIntegralBinary(a, b, "%");
        int width = Math.Max(a.Width, b.Width);
        Expr left = Cast(DemoteLargeWorld(a), width), right = Cast(DemoteLargeWorld(b), width);
        return new Expr($"({left.Code} - {right.Code} * trunc({left.Code} / {right.Code}))", width, Uniform: a.Uniform && b.Uniform);
    }

    private Expr MirOperator(Context context, JObject p)
    {
        if (!UsesMirTranslation)
            throw new ConversionException("MaterialExpressionOperator requires the source material's new translator");
        string operation = MirOperatorName(p);
        var pins = p["DynamicInputs"] as JArray
                ?? throw new ConversionException("MaterialExpressionOperator has no saved DynamicInputs");
        int arity = MirArity(operation);
        bool variable = arity == 2 && (p.Value<bool?>("bAllowAddPin") ?? true);
        int count = variable ? pins.Count : arity;
        if (pins.Count < (variable ? 1 : arity))
            throw new ConversionException($"Material operator {operation} requires {arity} saved inputs");
        Expr InputAt(int index)
        {
            var pin = pins[index] as JObject ?? throw new ConversionException($"Invalid material operator pin {index}");
            return Input(context, pin["ExpressionInput"], () => Constant(SourceAsset.Number(pin["ConstValue"], 1)));
        }
        Expr value = InputAt(0);
        if (arity == 1 && !variable) {
            if (operation == "Transpose") return MirTranspose(value);
            if (value.IsInteger) value = CastSigned(value, value.Width);
            var result = operation switch {
                "Not" when IsMirBoolean(value) => MirLogical("!", value),
                "BitwiseNot" when value.IsInteger => ZeroGradient(new Expr($"(~{value.Code})", value.Width, SignedInt: true)),
                "Negate" when value.IsInteger => ZeroGradient(new Expr($"(-{value.Code})", value.Width, SignedInt: true)),
                "Abs" or "Sign" when value.IsInteger => ZeroGradient(new Expr(
                        $"{operation.ToLowerInvariant()}({value.Code})", value.Width, SignedInt: true)),
                "Negate" => Arithmetic(Constant(0), value, "-"),
                "Abs" or "Sign" => PrecisionUnary(AsFloat(value), operation.ToLowerInvariant()),
                _ => MirUnaryMath(operation, value)
            };
            return AsMirOrientation(result, value.MirColumnVector);
        }
        if (arity == 3 && !variable) {
            var b = InputAt(1);
            var c = InputAt(2);
            bool column = MirCommonOrientation(value, b, c);
            return AsMirOrientation(MirTernary(operation, value, b, c), column);
        }
        for (int i = 1; i < count; i++) {
            Expr right = InputAt(i);
            if (operation == "MatrixMultiply") {
                value = MirMatrixMultiply(value, right);
                continue;
            }
            bool column = operation switch {
                "Dot" => value.MirColumnVector,
                "Cross" when !value.MirColumnVector => false,
                "Min" or "Max" when !UsesMirStrictProfile => value.MirColumnVector,
                _ => MirCommonOrientation(value, right)
            };
            var result = operation switch {
                "Add" => Arithmetic(value, right, "+"),
                "Subtract" => Arithmetic(value, right, "-"),
                "Multiply" => Arithmetic(value, right, "*"),
                "Divide" or "DividePrecise" => Arithmetic(value, right, "/"),
                "Min" or "Max" => ExtremumValue(value, right, operation.ToLowerInvariant()),
                "Modulo" => MirModulo(value, right),
                "BitwiseAnd" or "BitwiseOr" or "BitwiseXor" or "BitShiftLeft" or "BitShiftRight"
                        when value.IsInteger && right.IsInteger => MirIntegralBinary(value, right, operation switch {
                            "BitwiseAnd" => "&", "BitwiseOr" => "|", "BitwiseXor" => "^", "BitShiftLeft" => "<<", _ => ">>"
                        }),
                _ => MirBinaryMath(operation, value, right)
            };
            value = AsMirOrientation(result, column);
        }
        return value;
    }
}
