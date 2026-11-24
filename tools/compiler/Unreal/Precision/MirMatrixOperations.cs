namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    // GLSL shares the component storage for row and column vectors. The MIR shape remains part of the value.
    private static Expr MirTranspose(Expr value)
    {
        if (value.Sampler) throw new ConversionException("A MIR transpose requires a primitive operand");
        return value.Width == 1 ? value : value with { MirColumnVector = !value.MirColumnVector };
    }

    private static Expr AsMirOrientation(Expr value, bool column)
        => value with { MirColumnVector = value.Width > 1 && column };

    private static bool MirCommonOrientation(Expr left, Expr right)
    {
        if (left.Width > 1 && right.Width > 1 && left.MirColumnVector != right.MirColumnVector)
            throw new ConversionException("MIR row and column vectors have no common arithmetic type");
        return left.MirColumnVector || right.MirColumnVector;
    }

    private static bool MirCommonOrientation(Expr first, Expr second, Expr third)
    {
        bool column = MirCommonOrientation(first, second);
        return MirCommonOrientation(AsMirOrientation(first.Width >= second.Width ? first : second, column), third);
    }

    private Expr MirMatrixMultiply(Expr a, Expr b)
    {
        if (a.Sampler || b.Sampler || IsMirBoolean(a) || IsMirBoolean(b))
            throw new ConversionException("A MIR matrix multiply requires arithmetic operands");

        // In the source translator a vector on the right is interpreted as a column.
        int leftColumns = a.MirColumnVector ? 1 : a.Width;
        int rightRows = b.Width;
        if (leftColumns != rightRows)
            throw new ConversionException($"MIR matrix multiply has incompatible inner dimensions {leftColumns} and {rightRows}");

        if (rightRows == 1)
            return AsMirOrientation(Arithmetic(DemoteLargeWorld(a), DemoteLargeWorld(b), "*"), a.MirColumnVector);
        if (!a.IsInteger || !b.IsInteger)
            return Dot(DemoteLargeWorld(AsFloat(a)), DemoteLargeWorld(AsFloat(b)));
        return MirSignedDot(a, b);
    }

    private static Expr MirSignedDot(Expr a, Expr b)
    {
        Expr left = CastSigned(a, a.Width);
        Expr right = CastSigned(b, a.Width);
        string expression = string.Join(" + ", Enumerable.Range(0, a.Width)
            .Select(index => $"({Component(left, index)} * {Component(right, index)})"));
        return ZeroGradient(new Expr($"({expression})", 1, Uniform: a.Uniform && b.Uniform, SignedInt: true));
    }

    private Expr MirDot(Expr a, Expr b)
    {
        if (a.Sampler || IsMirBoolean(a))
            throw new ConversionException("A MIR dot requires an arithmetic left operand");
        if (a.IsInteger) return MirSignedDot(a, b);
        var right = Cast(a.LargeWorld ? b : DemoteLargeWorld(b), a.Width);
        if (a.LargeWorld && !right.LargeWorld) right = right with { RawCode = Promote(right), LargeWorld = true };
        return Dot(a, right);
    }

    private Expr MirCross(Expr a, Expr b)
    {
        if (a.Width != 3 || a.MirColumnVector || a.Sampler || IsMirBoolean(a))
            throw new ConversionException("A MIR cross requires a three-component arithmetic row vector");
        if (!a.IsInteger) {
            var left = DemoteLargeWorld(a);
            return CrossFloat(left, Cast(DemoteLargeWorld(b), 3));
        }
        var signedLeft = CastSigned(a, 3);
        var signedRight = CastSigned(b, 3);
        return ZeroGradient(WithMirComponents(3, true, false, index => {
            int first = (index + 1) % 3;
            int second = (index + 2) % 3;
            return $"({Component(signedLeft, first)} * {Component(signedRight, second)} - " +
                    $"{Component(signedLeft, second)} * {Component(signedRight, first)})";
        }));
    }

    private Expr MirLargeWorldTile(Expr value)
    {
        if (value.Width != 3 || value.MirColumnVector || value.Sampler || value.IsInteger || IsMirBoolean(value) || value.LargeWorld)
            throw new ConversionException("A MIR LWCTile requires a float3 row vector");
        materialLwcTileUsed = true;
        return new Expr($"LfxLWC3({value.Code}, vec3(0.0))", 3, LargeWorld: true, Uniform: value.Uniform);
    }
}
