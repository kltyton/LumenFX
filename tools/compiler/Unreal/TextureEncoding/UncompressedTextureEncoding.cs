using System.Buffers.Binary;

namespace LumenFx.Compiler.Unreal.TextureEncoding;

internal static class UncompressedTextureEncoding
{
    internal static bool LinearSource(string format) => format is "G16" or "PF_G16" or "R16F" or "PF_R16F"
        or "R32F" or "R32_FLOAT" or "PF_R32_FLOAT" or "RGBA16F" or "FloatRGBA" or "PF_FloatRGBA"
        or "RGBA32F" or "A32B32G32R32F" or "PF_A32B32G32R32F";

    internal static bool Supports(string format) => LinearSource(format) || format is "G8" or "PF_G8"
        or "B8G8R8A8" or "BGRA8" or "PF_B8G8R8A8" or "R8G8B8A8" or "RGBA8" or "PF_R8G8B8A8";

    internal static byte[] Encode(byte[] source, string format, bool linear)
    {
        if (linear != LinearSource(format)) throw new ConversionException($"Source mip precision does not match output {format}");
        if (!linear) {
            if (format is "G8" or "PF_G8") {
                byte[] gray = new byte[source.Length / 4];
                for (int pixel = 0; pixel < gray.Length; pixel++) gray[pixel] = source[pixel * 4 + 2];
                return gray;
            }
            if (format is "R8G8B8A8" or "RGBA8" or "PF_R8G8B8A8")
                for (int pixel = 0; pixel < source.Length; pixel += 4) (source[pixel], source[pixel + 2]) = (source[pixel + 2], source[pixel]);
            return source;
        }
        int stride = format switch {
            "G16" or "PF_G16" or "R16F" or "PF_R16F" => 2,
            "R32F" or "R32_FLOAT" or "PF_R32_FLOAT" => 4,
            "RGBA16F" or "FloatRGBA" or "PF_FloatRGBA" => 8,
            _ => 16 };
        byte[] output = new byte[checked(source.Length / 16 * stride)];
        for (int input = 0, destination = 0; input < source.Length; input += 16, destination += stride) {
            if (format is "G16" or "PF_G16") {
                float value = BinaryPrimitives.ReadSingleLittleEndian(source.AsSpan(input));
                float clamped = float.IsNaN(value) ? 0 : Math.Clamp(value, 0, 1);
                BinaryPrimitives.WriteUInt16LittleEndian(output.AsSpan(destination), (ushort)(.5f + clamped * 65535f));
            } else if (stride == 2 || stride == 8) {
                for (int channel = 0; channel < stride / 2; channel++) {
                    float value = BinaryPrimitives.ReadSingleLittleEndian(source.AsSpan(input + channel * 4));
                    BinaryPrimitives.WriteInt16LittleEndian(output.AsSpan(destination + channel * 2), BitConverter.HalfToInt16Bits((Half)value));
                }
            } else source.AsSpan(input, stride).CopyTo(output.AsSpan(destination));
        }
        return output;
    }
}
