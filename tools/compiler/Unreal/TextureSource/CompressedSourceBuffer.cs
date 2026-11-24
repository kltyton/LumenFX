using System.Buffers.Binary;
using K4os.Compression.LZ4;

namespace LumenFx.Compiler.Unreal.TextureSource;

internal static class CompressedSourceBuffer
{
    internal static byte[] Decode(ReadOnlySpan<byte> source, OodleDataCodec? oodle)
    {
        if (source.Length < 64 || BinaryPrimitives.ReadUInt32BigEndian(source) != 0xb7756362)
            throw new ConversionException("Invalid Unreal compressed buffer header");
        byte method = source[8], exponent = source[11];
        uint blocks = BinaryPrimitives.ReadUInt32BigEndian(source[12..]);
        ulong rawSize = BinaryPrimitives.ReadUInt64BigEndian(source[16..]);
        ulong compressedSize = BinaryPrimitives.ReadUInt64BigEndian(source[24..]);
        if (rawSize > int.MaxValue || compressedSize > (ulong)source.Length || compressedSize < 64)
            throw new ConversionException("Unreal source payload exceeds its container or process buffer limit");
        source = source[..checked((int)compressedSize)];
        if (method is not (0 or 3 or 4)) throw new ConversionException($"Unknown Unreal source compression method {method}");
        int headerSize = method == 0 ? 64 : checked(64 + checked((int)blocks) * sizeof(uint));
        if (headerSize > source.Length || Crc32(source[8..headerSize]) != BinaryPrimitives.ReadUInt32BigEndian(source[4..]))
            throw new ConversionException("Unreal compressed source header CRC or block table is invalid");
        if (method == 0)
        {
            if ((ulong)source.Length != rawSize + 64 || blocks != 1)
                throw new ConversionException("Invalid uncompressed Unreal source buffer size");
            return source[64..].ToArray();
        }
        if (exponent > 30 || blocks != ((rawSize + (1UL << exponent) - 1) >> exponent))
            throw new ConversionException("Unreal source block count does not match its raw size");
        byte[] output = new byte[checked((int)rawSize)];
        int inputOffset = headerSize, outputOffset = 0;
        for (uint block = 0; block < blocks; block++)
        {
            int storedBytes = checked((int)BinaryPrimitives.ReadUInt32BigEndian(source[(64 + checked((int)block) * 4)..]));
            int rawBytes = Math.Min(1 << exponent, output.Length - outputOffset);
            if (storedBytes <= 0 || storedBytes > rawBytes || (long)inputOffset + storedBytes > source.Length)
                throw new ConversionException("Invalid compressed Unreal source block size");
            if (storedBytes == rawBytes)
                source.Slice(inputOffset, storedBytes).CopyTo(output.AsSpan(outputOffset, rawBytes));
            else if (method == 4)
            {
                int decoded = LZ4Codec.Decode(source.Slice(inputOffset, storedBytes), output.AsSpan(outputOffset, rawBytes));
                if (decoded != rawBytes) throw new ConversionException("LZ4 source block decompression failed");
            }
            else
            {
                if (oodle is null) throw new ConversionException("This source payload requires an explicit Oodle Data library");
                oodle.Decode(source.Slice(inputOffset, storedBytes).ToArray(), rawBytes).CopyTo(output, outputOffset);
            }
            inputOffset = checked(inputOffset + storedBytes);
            outputOffset = checked(outputOffset + rawBytes);
        }
        if (inputOffset != source.Length || outputOffset != output.Length)
            throw new ConversionException("Unreal source payload contains inconsistent block lengths");
        return output;
    }

    private static uint Crc32(ReadOnlySpan<byte> data)
    {
        uint crc = uint.MaxValue;
        foreach (byte value in data)
        {
            crc ^= value;
            for (int bit = 0; bit < 8; bit++) crc = (crc >> 1) ^ ((crc & 1) != 0 ? 0xedb88320U : 0);
        }
        return ~crc;
    }
}
