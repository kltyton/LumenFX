using System.Buffers.Binary;

namespace LumenFx.Compiler.Unreal.TextureSource;

internal static class UnrealTextureDelta
{
    internal readonly record struct Surface(int Offset, int Width, int Height, int Slices, string Format);

    internal static byte[] Decode(byte[] encoded, IReadOnlyList<Surface> surfaces)
    {
        byte[] decoded = (byte[])encoded.Clone();
        foreach (Surface surface in surfaces)
        {
            (int channels, int wordBytes) = surface.Format switch {
                "TSF_G8" => (1, 1),
                "TSF_BGRA8" or "TSF_BGRE8" => (4, 1),
                "TSF_RGBA16" or "TSF_RGBA16F" => (4, 2),
                "TSF_RGBA32F" => (4, 4),
                "TSF_G16" or "TSF_R16F" => (1, 2),
                "TSF_R32F" => (1, 4),
                _ => throw new ConversionException($"Unsupported UE delta source format {surface.Format}") };
            if (surface.Width <= 0 || surface.Height <= 0 || surface.Slices <= 0 || surface.Offset < 0)
                throw new ConversionException("Invalid UE delta surface dimensions");
            int pixelBytes = channels * wordBytes;
            int stride = checked(surface.Width * pixelBytes);
            int sliceBytes = checked(stride * surface.Height);
            if (checked((long)surface.Offset + (long)sliceBytes * surface.Slices) > encoded.Length)
                throw new ConversionException("UE delta surface exceeds its source payload");
            for (int slice = 0; slice < surface.Slices; slice++)
            {
                int offset = checked(surface.Offset + slice * sliceBytes);
                if ((long)surface.Width * surface.Height <= 136 * 136)
                {
                    DecodePortion(encoded, decoded, offset, stride, stride, surface.Height, wordBytes);
                    continue;
                }
                int stripPixels = surface.Width;
                if (stride > 4096)
                {
                    int parts = checked((int)(((long)stride + 4095) / 4096));
                    int stripBytes = checked((int)(((long)stride + parts / 2) / parts));
                    stripBytes = checked((stripBytes + 63) & ~63);
                    stripPixels = stripBytes / pixelBytes;
                }
                for (int x = 0; x < surface.Width; x += stripPixels)
                {
                    int width = Math.Min(stripPixels, surface.Width - x);
                    long pixels = (long)width * surface.Height;
                    long cuts = pixels <= 32768 ? 1 : pixels / 32768;
                    while (cuts > 512) cuts >>= 1;
                    int rows = checked((int)(((long)surface.Height + cuts - 1) / cuts));
                    for (int y = 0; y < surface.Height; y += rows)
                        DecodePortion(encoded, decoded, checked(offset + y * stride + x * pixelBytes),
                                stride, checked(width * pixelBytes), Math.Min(rows, surface.Height - y), wordBytes);
                }
            }
        }
        return decoded;
    }

    private static void DecodePortion(byte[] encoded, byte[] decoded, int offset, int stride,
                                     int rowBytes, int height, int wordBytes)
    {
        for (int y = 1; y < height; y++)
        {
            int row = checked(offset + y * stride);
            for (int x = 0; x < rowBytes; x += wordBytes)
            {
                int index = row + x;
                switch (wordBytes)
                {
                    case 1:
                        decoded[index] = unchecked((byte)(encoded[index] + decoded[index - stride]));
                        break;
                    case 2:
                        ushort word = BinaryPrimitives.ReadUInt16LittleEndian(encoded.AsSpan(index));
                        ushort previous = BinaryPrimitives.ReadUInt16LittleEndian(decoded.AsSpan(index - stride));
                        BinaryPrimitives.WriteUInt16LittleEndian(decoded.AsSpan(index), unchecked((ushort)(word + previous - 0x8080)));
                        break;
                    case 4:
                        uint wide = BinaryPrimitives.ReadUInt32LittleEndian(encoded.AsSpan(index));
                        uint previousWide = BinaryPrimitives.ReadUInt32LittleEndian(decoded.AsSpan(index - stride));
                        BinaryPrimitives.WriteUInt32LittleEndian(decoded.AsSpan(index), unchecked(wide + previousWide - 0x80808080U));
                        break;
                }
            }
        }
    }
}
