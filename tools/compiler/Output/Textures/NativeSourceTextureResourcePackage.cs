using System.Security.Cryptography;
using System.Text;
using LumenFx.Compiler.Unreal;
using LumenFx.Compiler.Unreal.TextureSource;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Output;

public static partial class ResourcePackage
{
    private enum NativeSourceTextureKind { LightProfile, CurveExposure, CurveAtlas, TextureArray, TextureCube, TextureCubeArray, TextureVolume, FontPage, EngineLookup }
    private static string WriteNativeSourceTexture(SourceAsset asset, SourceObject texture, string source, string ns, string effect,
        Dictionary<string, byte[]> files, string? oodleDataLibrary, NativeSourceTextureKind kind = NativeSourceTextureKind.LightProfile,
        string? location = null)
    {
        TextureSettings settings = kind == NativeSourceTextureKind.CurveAtlas
            ? Textures.ReadCurveAtlas(asset, source) : Textures.Read(asset, source);
        using var codec = oodleDataLibrary is null ? null : new OodleDataCodec(oodleDataLibrary);
        TextureSourceReader.Image image = TextureSourceReader.Read(asset, texture, codec);
        if (kind == NativeSourceTextureKind.CurveAtlas
                && (image.Levels.Count != 1 || image.Levels[0].Block != 0 || image.Levels[0].Layer != 0
                    || image.Levels[0].Mip != 0 || image.Levels[0].Surface.Width != settings.Width
                    || image.Levels[0].Surface.Height != settings.Height || image.Levels[0].Surface.Slices != 1
                    || image.Levels[0].Surface.Format != "TSF_RGBA16F"))
            throw new ConversionException($"Curve atlas source must be one RGBA16F image without mips: {source}");
        string key = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source)))[..16];
        string root = location ?? (kind switch {
            NativeSourceTextureKind.LightProfile => $"textures/lumenfx/{effect}/profiles/profile_{key}",
            NativeSourceTextureKind.CurveExposure => $"textures/lumenfx/{effect}/interfaces/curves/curve_{key}",
            NativeSourceTextureKind.CurveAtlas => $"textures/lumenfx/{effect}/interfaces/curve_atlases/atlas_{key}",
            NativeSourceTextureKind.TextureArray => $"textures/lumenfx/{effect}/interfaces/arrays/array_{key}",
            NativeSourceTextureKind.TextureCube => $"textures/lumenfx/{effect}/interfaces/cubes/cube_{key}",
            NativeSourceTextureKind.TextureCubeArray => $"textures/lumenfx/{effect}/interfaces/cube_arrays/array_{key}",
            NativeSourceTextureKind.TextureVolume => $"textures/lumenfx/{effect}/interfaces/volumes/volume_{key}",
            NativeSourceTextureKind.FontPage => $"textures/lumenfx/{effect}/fonts/page_{key}",
            NativeSourceTextureKind.EngineLookup => throw new ArgumentException("An engine lookup requires its renderer resource path"),
            _ => throw new ArgumentOutOfRangeException(nameof(kind))
        });
        string sourceLocation = root + ".lfts", metadataLocation = root + ".lftex.json";
        string sourceDigest = Convert.ToHexStringLower(SHA256.HashData(image.Pixels));
        JObject metadata = settings.ToJson(sourceDigest);
        metadata["format_version"] = 2;
        metadata["source"] = "unreal-texture2d-source";
        metadata.Remove("png_sha256");
        metadata["source_data"] = ns + ":" + sourceLocation;
        metadata["source_sha256"] = sourceDigest;
        metadata["mip_data"] = ns + ":" + root + ".lftx";
        if (kind == NativeSourceTextureKind.TextureArray) {
            var level = image.Levels.Single(value => value.Block == 0 && value.Layer == 0 && value.Mip == 0);
            if (image.Levels.Any(value => value.Block != 0 || value.Surface.Slices != level.Surface.Slices))
                throw new ConversionException($"Array texture source slices differ between levels: {source}");
            metadata["array_layers"] = level.Surface.Slices;
            metadata["source"] = "unreal-texture2d-array-source";
            metadata["build_backend"] = "unreal_native";
        }
        if (kind == NativeSourceTextureKind.TextureCube) {
            metadata["source"] = "unreal-texture-cube-source";
            metadata["build_backend"] = "unreal_native";
        }
        if (kind == NativeSourceTextureKind.TextureCubeArray) {
            var baseLevel = image.Levels.Single(level => level.Block == 0 && level.Layer == 0 && level.Mip == 0);
            int slices = baseLevel.Surface.Slices;
            if (slices < 1 || image.Levels.Any(level => level.Block != 0 || level.Layer != 0 || level.Surface.Slices != slices))
                throw new ConversionException($"Cubemap array source slices differ between levels: {source}");
            // FTextureSource::IsLongLatCubemap uses the serialized flag or the native slice-count rule.
            bool longLat = texture.Properties["Source"]?.Value<bool?>("bLongLatCubemap") == true || slices % 6 != 0;
            metadata["cube_array_layers"] = longLat ? slices : slices / 6;
            metadata["source"] = "unreal-texture-cube-array-source";
        }
        if (kind == NativeSourceTextureKind.TextureVolume) {
            metadata["source"] = "unreal-texture-volume-source";
            metadata["build_backend"] = "unreal_native";
            metadata["address_z"] = Textures.VolumeAddress(texture, source).ToString().ToLowerInvariant();
        }
        metadata["source_levels"] = new JArray(image.Levels.Select(level => new JObject {
            ["block"] = level.Block, ["layer"] = level.Layer, ["mip"] = level.Mip,
            ["offset"] = level.Surface.Offset, ["width"] = level.Surface.Width, ["height"] = level.Surface.Height,
            ["slices"] = level.Surface.Slices, ["format"] = level.Surface.Format
        }));
        byte[] metadataBytes = Encoding.UTF8.GetBytes(metadata.ToString(Formatting.Indented) + "\n");
        if (files.TryGetValue(sourceLocation, out byte[]? previousSource) && !previousSource.AsSpan().SequenceEqual(image.Pixels))
            throw new ConversionException($"Native texture source output collision: {sourceLocation}");
        if (files.TryGetValue(metadataLocation, out byte[]? previousMetadata) && !previousMetadata.AsSpan().SequenceEqual(metadataBytes))
            throw new ConversionException($"Native texture metadata output collision: {metadataLocation}");
        files[sourceLocation] = image.Pixels;
        files[metadataLocation] = metadataBytes;
        return ns + ":" + metadataLocation;
    }
}
