using System.Buffers.Binary;
using LumenFx.Compiler.Unreal.TextureSource;

namespace LumenFx.Compiler.Unreal.BulkData;

internal static class EditorBulkPayload
{
    internal static byte[] Read(SourceAsset asset, ReadOnlySpan<byte> headerData, OodleDataCodec? oodle)
    {
        if (headerData.Length < 48)
            throw new ConversionException($"Truncated editor bulk-data header: {asset.Name}");
        uint flags = BinaryPrimitives.ReadUInt32LittleEndian(headerData);
        long rawSize = BinaryPrimitives.ReadInt64LittleEndian(headerData[40..]);
        if ((flags & (1U | 4U | 256U | 1024U)) != 0)
            throw new ConversionException($"Editor source requires its virtualized, legacy or external payload: {asset.Name}");
        if ((flags & 512U) == 0)
            return ReadInlinePackagePayload(asset, headerData, rawSize, oodle);
        string trailerText = asset.Raw.Value<string>("Trailer")
            ?? throw new ConversionException($"Editor source package has no exported trailer: {asset.Name}");
        byte[] trailer = Convert.FromBase64String(trailerText);
        if (trailer.Length < 48 || BinaryPrimitives.ReadUInt64LittleEndian(trailer) != 0xD1C43B2E80A5F697UL)
            throw new ConversionException($"Invalid editor source package trailer: {asset.Name}");
        int footer = trailer.Length - 20;
        if (BinaryPrimitives.ReadUInt64LittleEndian(trailer.AsSpan(footer)) != 0x29BFCA045138DE76UL
                || BinaryPrimitives.ReadUInt64LittleEndian(trailer.AsSpan(footer + 8)) != (ulong)trailer.Length
                || BinaryPrimitives.ReadUInt32LittleEndian(trailer.AsSpan(footer + 16)) != 0x9E2A83C1U)
            throw new ConversionException($"Invalid editor source package trailer footer: {asset.Name}");
        int version = BinaryPrimitives.ReadInt32LittleEndian(trailer.AsSpan(8));
        int header = checked((int)BinaryPrimitives.ReadUInt32LittleEndian(trailer.AsSpan(12)));
        ulong payloadBytes = BinaryPrimitives.ReadUInt64LittleEndian(trailer.AsSpan(16));
        if (version is < 0 or > 2 || header < 28 || (ulong)header + payloadBytes != (ulong)footer)
            throw new ConversionException($"Unsupported editor source trailer version or size: {asset.Name}");
        int cursor = 24;
        byte legacyMode = version == 0 ? trailer[cursor++] : (byte)0;
        int count = BinaryPrimitives.ReadInt32LittleEndian(trailer.AsSpan(cursor));
        cursor += 4;
        int entryBytes = 44 + (version >= 2 ? 4 : 0) + (version >= 1 ? 1 : 0);
        if (count < 0 || checked((long)cursor + (long)count * entryBytes) != header)
            throw new ConversionException($"Invalid editor source payload lookup table: {asset.Name}");
        ReadOnlySpan<byte> id = headerData.Slice(20, 20);
        int selectedOffset = -1, selectedSize = 0;
        for (int index = 0; index < count; index++, cursor += entryBytes)
        {
            if (!trailer.AsSpan(cursor, 20).SequenceEqual(id)) continue;
            if (selectedOffset >= 0) throw new ConversionException($"Duplicate editor source payload ID: {asset.Name}");
            long offset = BinaryPrimitives.ReadInt64LittleEndian(trailer.AsSpan(cursor + 20));
            ulong size = BinaryPrimitives.ReadUInt64LittleEndian(trailer.AsSpan(cursor + 28));
            ulong tableRawSize = BinaryPrimitives.ReadUInt64LittleEndian(trailer.AsSpan(cursor + 36));
            byte mode = version >= 1 ? trailer[cursor + entryBytes - 1] : offset == -1 ? (byte)2 : legacyMode;
            if (mode != 0) throw new ConversionException($"Editor source payload is not stored locally: {asset.Name}");
            if (offset < 0 || size > int.MaxValue || (ulong)offset + size > payloadBytes
                    || rawSize < 0 || tableRawSize != (ulong)rawSize)
                throw new ConversionException($"Invalid editor source payload extent: {asset.Name}");
            selectedOffset = checked(header + (int)offset);
            selectedSize = checked((int)size);
        }
        if (selectedOffset < 0) throw new ConversionException($"Editor source payload ID is missing from trailer: {asset.Name}");
        byte[] payload = CompressedSourceBuffer.Decode(trailer.AsSpan(selectedOffset, selectedSize), oodle);
        if (payload.LongLength != rawSize) throw new ConversionException($"Editor source payload size mismatch: {asset.Name}");
        return payload;
    }

    private static byte[] ReadInlinePackagePayload(SourceAsset asset, ReadOnlySpan<byte> headerData, long rawSize, OodleDataCodec? oodle)
    {
        if (headerData.Length < 56 || rawSize < 0)
            throw new ConversionException($"Truncated legacy editor bulk-data header: {asset.Name}");
        string? additionalText = asset.Raw.Value<string>("AdditionalFiles");
        if (string.IsNullOrEmpty(additionalText))
            throw new ConversionException($"Editor source payload is outside the exported package trailer: {asset.Name}");
        byte[] additional = Convert.FromBase64String(additionalText);
        long offsetInFile = BinaryPrimitives.ReadInt64LittleEndian(headerData[48..]);
        long exportsEnd = 0;
        foreach (var obj in asset.Objects) {
            long end = (obj.Raw.Value<long?>("SerialOffset") ?? 0) + (obj.Raw.Value<long?>("SerialSize") ?? 0);
            if (end > exportsEnd) exportsEnd = end;
        }
        ReadOnlySpan<byte> id = headerData.Slice(20, 20);
        int relative = offsetInFile >= exportsEnd && offsetInFile - exportsEnd <= additional.Length - 64
            ? checked((int)(offsetInFile - exportsEnd)) : -1;
        if (relative < 0 || !MatchesCompressedBuffer(additional.AsSpan(relative), id, (ulong)rawSize)) {
            relative = FindCompressedBuffer(additional, id, (ulong)rawSize);
        }
        if (relative < 0)
            throw new ConversionException($"Editor source payload is outside the exported package trailer: {asset.Name}");
        ulong compressedSize = BinaryPrimitives.ReadUInt64BigEndian(additional.AsSpan(relative + 24));
        if (compressedSize < 64 || compressedSize > (ulong)(additional.Length - relative))
            throw new ConversionException($"Invalid inline editor source payload extent: {asset.Name}");
        byte[] payload = CompressedSourceBuffer.Decode(additional.AsSpan(relative, checked((int)compressedSize)), oodle);
        if (payload.LongLength != rawSize) throw new ConversionException($"Editor source payload size mismatch: {asset.Name}");
        return payload;
    }

    private static bool MatchesCompressedBuffer(ReadOnlySpan<byte> slice, ReadOnlySpan<byte> id, ulong rawSize) =>
        slice.Length >= 64
        && BinaryPrimitives.ReadUInt32BigEndian(slice) == 0xb7756362U
        && BinaryPrimitives.ReadUInt64BigEndian(slice[16..]) == rawSize
        && slice.Slice(32, 20).SequenceEqual(id);

    private static int FindCompressedBuffer(byte[] buffer, ReadOnlySpan<byte> id, ulong rawSize)
    {
        for (int offset = 0; offset <= buffer.Length - 64; offset++) {
            if (buffer[offset] == 0xb7 && buffer[offset + 1] == 0x75 && buffer[offset + 2] == 0x63 && buffer[offset + 3] == 0x62
                    && MatchesCompressedBuffer(buffer.AsSpan(offset), id, rawSize))
                return offset;
        }
        return -1;
    }
}
