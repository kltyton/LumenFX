using System.Buffers.Binary;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild.Nanite;

internal sealed record NativeNaniteMesh(byte[] Index, IReadOnlyList<byte[]> StreamedPages)
{
    private const int HeaderSize = 88;
    private const int PageStateSize = 20;

    internal static NativeNaniteMesh? Read(BinaryReader reader)
    {
        int length = reader.ReadInt32();
        if (length == 0) return null;
        if (length < HeaderSize || length > reader.BaseStream.Length - reader.BaseStream.Position)
            throw new ConversionException("Invalid native Nanite index size");
        byte[] index = reader.ReadBytes(length);
        int ReadInt(int offset) => BinaryPrimitives.ReadInt32LittleEndian(index.AsSpan(offset, sizeof(int)));
        int version = ReadInt(4);
        int headerSize = HeaderSize + (version == 2 ? sizeof(int) : 0);
        if (ReadInt(0) != 0x544e464c || (version != 1 && version != 2) || length < headerSize
            || (version == 2 && ReadInt(HeaderSize - sizeof(int)) == 0))
            throw new ConversionException("Invalid native Nanite index header");
        int rootPages = ReadInt(16), pageCount = ReadInt(headerSize - sizeof(int));
        if (rootPages <= 0 || pageCount < rootPages || (long)pageCount * PageStateSize > length - headerSize)
            throw new ConversionException("Invalid native Nanite page table");
        int streamCount = reader.ReadInt32();
        if (streamCount != pageCount - rootPages)
            throw new ConversionException("Native Nanite stream count disagrees with its index");

        var pages = new byte[streamCount][];
        for (int ordinal = 0; ordinal < streamCount; ordinal++)
        {
            int expectedLength = ReadInt(headerSize + (rootPages + ordinal) * PageStateSize + sizeof(int));
            int pageLength = reader.ReadInt32();
            if (pageLength <= 0 || pageLength != expectedLength || pageLength > reader.BaseStream.Length - reader.BaseStream.Position)
                throw new ConversionException($"Invalid native Nanite streaming page {ordinal}");
            pages[ordinal] = reader.ReadBytes(pageLength);
        }
        return new NativeNaniteMesh(index, pages);
    }
}
