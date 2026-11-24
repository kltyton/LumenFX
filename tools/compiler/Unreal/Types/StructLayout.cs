namespace LumenFx.Compiler.Unreal.Types;

/** A linked source struct keeps unrounded PropertiesSize separate from its allocated structure size. */
internal sealed record StructLayout(string Path, string Name, int PropertiesSize, int Alignment, IReadOnlyList<StructField> Fields)
{
    internal int Size => Align(PropertiesSize, Alignment);
    internal static int Align(int value, int alignment)
    {
        if (value < 0 || alignment <= 0 || (alignment & (alignment - 1)) != 0)
            throw new ConversionException("Invalid reflected struct size or alignment");
        return checked(value + alignment - 1) & -alignment;
    }
}

internal enum StructFieldKind { Double, Float, Int32, UInt32, UInt16, Enum, Byte, Bool, Struct }

internal sealed record StructField(string Name, StructFieldKind Kind, int Offset, int ElementSize, int ArrayDim,
    int Alignment, StructLayout? Structure = null)
{
    internal int Size => checked(ElementSize * ArrayDim);
}
