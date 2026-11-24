using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.TextureEncoding;

public static class TextureBakeCommand
{
    public static void Convert(string metadataPath, string inputPath, string outputPath, string sdkRoot)
    {
        byte[] metadataBytes = File.ReadAllBytes(metadataPath);
        string metadataText;
        using (var reader = new StreamReader(new MemoryStream(metadataBytes), Encoding.UTF8, true))
            metadataText = reader.ReadToEnd();
        JObject metadata = JObject.Parse(metadataText);
        byte[] metadataDigest = SHA256.HashData(metadataBytes);
        string version = metadata.Value<string>("oodle_sdk") ?? throw new ConversionException("Missing Oodle SDK version");
        if (!Regex.IsMatch(version, @"^\d+\.\d+\.\d+$")) throw new ConversionException("Invalid Oodle SDK version");
        string format = metadata.Value<string>("cooked_format") ?? throw new ConversionException("Missing encoded texture format");
        OodleTextureFormat? compressed = format switch {
            "DXT1" or "PF_DXT1" => OodleTextureFormat.Dxt1,
            "DXT5" or "PF_DXT5" => OodleTextureFormat.Dxt5,
            "DXT3" or "PF_DXT3" => OodleTextureFormat.Dxt3,
            "BC4" or "PF_BC4" => OodleTextureFormat.Bc4,
            "BC5" or "PF_BC5" => OodleTextureFormat.Bc5,
            "BC6H" or "PF_BC6H" => OodleTextureFormat.Bc6h,
            "BC7" or "PF_BC7" => OodleTextureFormat.Bc7,
            _ when UncompressedTextureEncoding.Supports(format) => null,
            _ => throw new ConversionException($"Unsupported source texture format {format}") };
        if (compressed == OodleTextureFormat.Bc6h && version == "2.9.12") version = "2.9.13";
        string dll = Path.Combine(Path.GetFullPath(sdkRoot), version, "redist", "Win64", $"oo2tex_win64_{version}.dll");
        using var codec = compressed is null ? null : new OodleTextureCodec(dll);
        using BinaryReader input = new(File.OpenRead(inputPath), Encoding.UTF8);
        if (input.ReadUInt32() != 0x494d464c) throw new ConversionException("Invalid source mip stream");
        int inputVersion = input.ReadInt32();
        if (inputVersion is not (2 or 3 or 4)) throw new ConversionException("Unsupported source mip precision version");
        bool linear = inputVersion == 3;
        byte[] sourceDigest = input.ReadBytes(32);
        if (sourceDigest.Length != 32) throw new ConversionException("Truncated source mip identity");
        if (!CryptographicOperations.FixedTimeEquals(sourceDigest, metadataDigest))
            throw new ConversionException("Source mip metadata hash mismatch");
        int levels = input.ReadInt32();
        bool? hasAlpha = null;
        if (inputVersion == 4) {
            int flags = input.ReadInt32();
            if ((flags & ~3) != 0) throw new ConversionException("Invalid source mip flags");
            linear = (flags & 1) != 0; hasAlpha = (flags & 2) != 0;
        }
        if (linear != (UncompressedTextureEncoding.LinearSource(format) || compressed == OodleTextureFormat.Bc6h
                || (compressed is OodleTextureFormat.Bc4 or OodleTextureFormat.Bc5) && !metadata.Value<bool>("srgb")))
            throw new ConversionException($"Texture {format} has the wrong source mip precision");
        int width = metadata.Value<int>("width"), height = metadata.Value<int>("height");
        int expected = metadata.Value<string>("mip_gen") == "no_mipmaps" ? 1 : 1 + (int)Math.Floor(Math.Log2(Math.Max(width, height)));
        if (levels != expected) throw new ConversionException("Source mip count does not match texture settings");
        string destination = Path.GetFullPath(outputPath);
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        using BinaryWriter output = new(File.Create(destination), Encoding.UTF8);
        output.Write(0x5254464c);
        output.Write(2); output.Write(metadataDigest); output.Write(levels);
        for (int level = 0; level < levels; level++)
        {
            int w = input.ReadInt32(), h = input.ReadInt32(), bytes = input.ReadInt32();
            if (w != Math.Max(1, width >> level) || h != Math.Max(1, height >> level) || bytes != checked(w * h * (linear ? 16 : 4)))
                throw new ConversionException("Source mip dimensions do not match texture settings");
            byte[] pixels = input.ReadBytes(bytes);
            if (pixels.Length != bytes) throw new ConversionException("Truncated source mip pixels");
            if (compressed is { } bcn)
            {
                pixels = linear
                    ? codec!.EncodeLinearFloat(pixels, w, h, bcn, metadata.Value<int>("rdo_lambda"), metadata.Value<bool>("preserve_extremes"),
                        metadata.Value<int>("encode_effort"), metadata.Value<int>("universal_tiling"))
                    : codec!.Encode(pixels, w, h, bcn, metadata.Value<int>("rdo_lambda"), metadata.Value<bool>("preserve_extremes"),
                        metadata.Value<int>("encode_effort"), metadata.Value<int>("universal_tiling"), hasAlpha);
            }
            else pixels = UncompressedTextureEncoding.Encode(pixels, format, linear);
            output.Write(w); output.Write(h); output.Write(pixels.Length); output.Write(pixels);
        }
        if (input.BaseStream.Position != input.BaseStream.Length) throw new ConversionException("Trailing source mip data");
        Console.WriteLine($"Encoded {levels} mip levels: {format}, SDK {version}");
    }
}
