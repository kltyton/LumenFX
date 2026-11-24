using System.Buffers.Binary;
using LumenFx.Compiler.Unreal.TextureSource;

namespace LumenFx.Compiler.Unreal.Geometry;

internal sealed class SpriteCutoutPixels(TextureSourceReader.Image image, float threshold, string opacityMode)
{
    private readonly UnrealTextureDelta.Surface surface = image.Levels.Single(level => level.Block == 0 && level.Layer == 0 && level.Mip == 0).Surface;
    private readonly int threshold8 = Math.Clamp((int)(threshold * 255f), 0, 255);
    private readonly int threshold16 = Math.Clamp((int)(threshold * 65535f), 0, 65535);

    internal int Width => surface.Width;
    internal int Height => surface.Height;
    internal bool Supported => surface.Slices == 1 && (surface.Format is "TSF_G8" or "TSF_R32F"
            or "TSF_BGRA8" or "TSF_RGBA16" or "TSF_RGBA16F" or "TSF_RGBA32F");

    internal bool Opaque(int x, int y)
    {
        if ((uint)x >= (uint)Width || (uint)y >= (uint)Height)
            throw new ConversionException("Sprite cutout source coordinates exceed the original mip");
        int offset = checked(surface.Offset + (y * Width + x) * TextureSourceReader.BytesPerPixel(surface.Format));
        ReadOnlySpan<byte> pixel = image.Pixels.AsSpan(offset);
        if (surface.Format == "TSF_G8") return pixel[0] > threshold8;
        if (surface.Format == "TSF_R32F") return BinaryPrimitives.ReadSingleLittleEndian(pixel) > threshold;
        int component = opacityMode switch {
            "OSM_Alpha" => 3, "OSM_RedChannel" => 0, "OSM_GreenChannel" => 1,
            "OSM_BlueChannel" => 2, "OSM_ColorBrightness" => -1,
            _ => throw new ConversionException($"Unknown cutout opacity source {opacityMode}") };
        if (surface.Format == "TSF_BGRA8")
        {
            int channel = component < 0 ? -1 : component == 3 ? 3 : 2 - component;
            return (channel < 0 ? (pixel[0] + pixel[1] + pixel[2]) / 3 : pixel[channel]) > threshold8;
        }
        if (surface.Format == "TSF_RGBA16")
        {
            int value = component < 0
                    ? (BinaryPrimitives.ReadUInt16LittleEndian(pixel)
                        + BinaryPrimitives.ReadUInt16LittleEndian(pixel[2..])
                        + BinaryPrimitives.ReadUInt16LittleEndian(pixel[4..])) / 3
                    : BinaryPrimitives.ReadUInt16LittleEndian(pixel[(component * 2)..]);
            return value > threshold16;
        }
        float r = Floating(pixel, 0), g = Floating(pixel, 1), b = Floating(pixel, 2);
        return (component < 0 ? (r + g + b) / 3f : Floating(pixel, component)) > threshold;
    }

    private float Floating(ReadOnlySpan<byte> pixel, int component) => surface.Format switch {
        "TSF_RGBA16F" => (float)BitConverter.UInt16BitsToHalf(BinaryPrimitives.ReadUInt16LittleEndian(pixel[(component * 2)..])),
        "TSF_RGBA32F" => BinaryPrimitives.ReadSingleLittleEndian(pixel[(component * 4)..]),
        _ => throw new ConversionException($"Unsupported cutout opacity format {surface.Format}") };
}
