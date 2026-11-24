using SkiaSharp;

namespace LumenFx.Compiler.Unreal.TextureSource;

internal static class TextureSourceImageCodec
{
    internal static byte[] Decode(byte[] payload, UnrealTextureDelta.Surface surface, string compression)
    {
        if (surface.Slices != 1) throw new ConversionException("Compressed source images require a single slice");
        using var data = SKData.CreateCopy(payload);
        using var codec = SKCodec.Create(data);
        if (codec is null)
        {
            // UTexture::TryDecompressData preserves this legacy mislabeled-PNG case.
            int rawBytes = checked(surface.Width * surface.Height * TextureSourceReader.BytesPerPixel(surface.Format));
            if (compression == "TSCF_PNG" && payload.Length == rawBytes) return payload;
            throw new ConversionException($"Cannot decode source image marked {compression}");
        }
        if (codec.Info.Width != surface.Width || codec.Info.Height != surface.Height)
            throw new ConversionException("Encoded source dimensions differ from TextureSource; scaling is not permitted");
        SKColorType type = surface.Format switch {
            "TSF_G8" => SKColorType.Gray8,
            // PNG's stored channels retain UE's historical BGRA/RGBA interchange.
            "TSF_BGRA8" => compression == "TSCF_PNG" ? SKColorType.Rgba8888 : SKColorType.Bgra8888,
            "TSF_RGBA16" or "TSF_G16" => SKColorType.Rgba16161616,
            _ => throw new ConversionException($"Image-compressed source requires conversion to {surface.Format}") };
        using var colorSpace = codec.Info.ColorSpace;
        var info = new SKImageInfo(surface.Width, surface.Height, type,
                type == SKColorType.Gray8 ? SKAlphaType.Opaque : SKAlphaType.Unpremul, colorSpace);
        byte[] decoded = new byte[checked(info.RowBytes * info.Height)];
        SKCodecResult result = codec.GetPixels(info, decoded);
        if (result != SKCodecResult.Success) throw new ConversionException($"Source image decode failed: {result}");
        if (surface.Format != "TSF_G16") return decoded;
        byte[] gray = new byte[checked(surface.Width * surface.Height * 2)];
        for (int source = 0, target = 0; target < gray.Length; source += 8, target += 2)
        {
            gray[target] = decoded[source]; gray[target + 1] = decoded[source + 1];
        }
        return gray;
    }
}
