using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr Panner(Context context, JObject properties)
    {
        Expr time = Input(context, properties["Time"], () => MaterialClock(false, false, 0));
        Expr speedX, speedY;
        if (Connected(properties["Speed"]))
        {
            Expr speed = RequiredInput(context, properties, "Speed");
            speedX = Swizzle(speed, "r");
            speedY = Swizzle(speed, "g");
        }
        else
        {
            speedX = Constant(SourceAsset.Number(properties["SpeedX"]));
            speedY = Constant(SourceAsset.Number(properties["SpeedY"]));
        }

        bool fractional = properties.Value<bool?>("bFractionalPart") == true;
        Expr Offset(Expr speed)
        {
            Expr offset = Arithmetic(time, speed, "*");
            // PeriodicHint adds a fractional opcode to native preshader inputs.
            return fractional || offset.Uniform ? MaterialFraction(offset) : offset;
        }

        return Arithmetic(Append(Offset(speedX), Offset(speedY)), TextureCoordinateInput(context, properties), "+");
    }

    private Expr Rotator(Context context, JObject properties)
    {
        Expr time = Input(context, properties["Time"], () => MaterialClock(false, false, 0));
        Expr angle = Arithmetic(time, Constant(SourceAsset.Number(properties["Speed"], .25)), "*");
        Expr Trig(string operation) => angle.Uniform
            ? UniformExpression($"{operation}({Cast(angle, 1).Code})", 1)
            : AnalyticDerivatives ? DerivativeTrigonometric(angle, operation) : PrecisionUnary(angle, operation);
        Expr sine = Trig("sin"), cosine = Trig("cos");
        Expr rowX = Append(cosine, Arithmetic(Constant(-1), sine, "*"));
        Expr rowY = Append(sine, cosine);
        Expr origin = Vector(new[] { SourceAsset.Number(properties["CenterX"], .5), SourceAsset.Number(properties["CenterY"], .5) });
        Expr coordinate = TextureCoordinateInput(context, properties);
        bool preserveZ = coordinate.Width == 3 && !coordinate.LargeWorld;
        Expr relative = Arithmetic(preserveZ ? Swizzle(coordinate, "rg") : coordinate, origin, "-");
        Expr rotated = Arithmetic(Append(Dot(rowX, relative), Dot(rowY, relative)), origin, "+");
        return preserveZ ? Append(rotated, Swizzle(coordinate, "b")) : rotated;
    }

    private Expr RotateAboutAxis(Context context, JObject properties)
    {
        float period = (float)SourceAsset.Number(properties["Period"], 1);
        float radians = 2f * MathF.PI / period;
        Expr scale = float.IsFinite(radians) ? Constant(radians)
            : new Expr($"uintBitsToFloat(0x{unchecked((uint)BitConverter.SingleToInt32Bits(radians)):x8}u)", 1);
        Expr axis = Shared(DemoteLargeWorld(Cast(RequiredInput(context, properties, "NormalizedRotationAxis"), 3)));
        Expr angle = Shared(DemoteLargeWorld(Cast(Arithmetic(RequiredInput(context, properties, "RotationAngle"), scale, "*"), 1)));
        Expr pivot = Cast(RequiredInput(context, properties, "PivotPoint"), 3);
        Expr position = Cast(RequiredInput(context, properties, "Position"), 3);
        bool largeWorld = pivot.LargeWorld || position.LargeWorld;
        pivot = Shared(ArithmeticResult(pivot, 3, largeWorld));
        position = Shared(ArithmeticResult(position, 3, largeWorld));

        Expr displacement = DemoteLargeWorld(Arithmetic(position, pivot, "-"));
        Expr projected = Arithmetic(axis, Dot(axis, displacement), "*");
        Expr closest = Shared(Arithmetic(pivot, projected, "+"));
        Expr radial = Shared(DemoteLargeWorld(Arithmetic(position, closest, "-")));
        Expr tangent = new Expr($"cross({axis.Code}, {radial.Code})", 3);
        Expr rotated = Arithmetic(Arithmetic(radial, PrecisionUnary(angle, "cos"), "*"),
            Arithmetic(tangent, PrecisionUnary(angle, "sin"), "*"), "+");
        Expr offset = largeWorld ? Arithmetic(rotated, radial, "-")
            : Arithmetic(Arithmetic(closest, rotated, "+"), position, "-");
        // The source translator emits an opaque code chunk for this expression.
        return new Expr(offset.Code, 3);
    }
}
