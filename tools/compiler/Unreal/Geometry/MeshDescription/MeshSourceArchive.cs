using System.Text;

namespace LumenFx.Compiler.Unreal.Geometry.MeshDescription;

internal sealed class MeshSourceArchive(byte[] data) : IDisposable
{
    private readonly BinaryReader reader = new(new MemoryStream(data, false), Encoding.UTF8);
    internal int Remaining => checked((int)(reader.BaseStream.Length - reader.BaseStream.Position));
    internal int Int() { Require(4); return reader.ReadInt32(); }
    internal uint UInt() { Require(4); return reader.ReadUInt32(); }
    internal ushort UShort() { Require(2); return reader.ReadUInt16(); }
    internal float Float() { Require(4); return reader.ReadSingle(); }
    internal byte Byte() { Require(1); return reader.ReadByte(); }
    internal int Count(string name, int maximum)
    {
        int value = Int();
        return value >= 0 && value <= maximum ? value
            : throw new ConversionException($"Invalid mesh {name}: {value}");
    }
    internal string String()
    {
        int length = Int();
        if (length == 0) return "";
        if (length == int.MinValue) throw new ConversionException("Invalid mesh FString length");
        bool wide = length < 0;
        long size = (long)Math.Abs(length) * (wide ? 2 : 1);
        if (size > Remaining) throw new ConversionException("Truncated mesh FString");
        byte[] bytes = reader.ReadBytes((int)size);
        if (bytes[^1] != 0 || wide && bytes[^2] != 0)
            throw new ConversionException("Mesh FString has no terminator");
        return (wide ? Encoding.Unicode : Encoding.UTF8).GetString(bytes, 0, bytes.Length - (wide ? 2 : 1));
    }
    internal void Require(long size)
    {
        if (size < 0 || size > Remaining) throw new ConversionException("Truncated MeshDescription payload");
    }
    public void Dispose() => reader.Dispose();
}
