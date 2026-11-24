using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.TextureSource;

internal static class TextureSourceReader
{
    internal sealed record Level(int Block, int Layer, int Mip, UnrealTextureDelta.Surface Surface);
    internal sealed record Image(byte[] Pixels, IReadOnlyList<Level> Levels);

    internal static Image Read(SourceAsset asset, OodleDataCodec? oodle)
    {
        SourceObject texture = asset.Objects.SingleOrDefault(value => value.ClassName is "Texture2D" or "TextureLightProfile" or "CurveLinearColorAtlas")
            ?? throw new ConversionException($"No Texture2D source object: {asset.Name}");
        return Read(asset, texture, oodle);
    }

    internal static Image Read(SourceAsset asset, SourceObject texture, OodleDataCodec? oodle)
    {
        if (texture.ClassName is not ("Texture2D" or "TextureLightProfile" or "Texture2DArray" or "TextureCube" or "TextureCubeArray" or "VolumeTexture" or "CurveLinearColorAtlas"))
            throw new ConversionException($"Unsupported texture source class: {asset.Name}:{texture.Name}");
        JObject properties = texture.Properties;
        JObject source = properties["Source"] as JObject
            ?? throw new ConversionException($"Missing Texture2D source description: {asset.Name}");
        byte[] payload = TextureSourcePayload.Read(asset, texture, oodle);
        List<JObject> blocks = [source];
        if (source["Blocks"] is JArray extraBlocks) blocks.AddRange(extraBlocks.OfType<JObject>());
        string firstFormat = source.Value<string>("Format")
            ?? throw new ConversionException($"Missing source pixel format: {asset.Name}");
        string[] formats = source["LayerFormat"] is JArray layers && layers.Count != 0
            ? layers.Values<string>().Select(value => value ?? throw new ConversionException("Null texture layer format")).ToArray()
            : [firstFormat];
        formats[0] = firstFormat;
        if (source.Value<int?>("NumLayers") is int layerCount && layerCount != formats.Length)
            throw new ConversionException($"Source layer count differs from its formats: {asset.Name}");
        long[] offsets = source["BlockDataOffsets"] is JArray values
            ? values.Values<long>().ToArray() : blocks.Count == 1 ? [0] : [];
        if (offsets.Length != blocks.Count)
            throw new ConversionException($"Missing texture source block offsets: {asset.Name}");
        List<Level> levels = [];
        foreach ((JObject block, int index) in blocks.Select((block, index) => (block, index)))
        {
            int width = RequiredDimension(block, "SizeX", asset.Name);
            int height = RequiredDimension(block, "SizeY", asset.Name);
            int slices = RequiredDimension(block, "NumSlices", asset.Name);
            int mips = RequiredDimension(block, "NumMips", asset.Name);
            if (mips > 32) throw new ConversionException($"Invalid source mip count: {asset.Name}");
            long offset = offsets[index];
            foreach ((string format, int layer) in formats.Select((format, layer) => (format, layer)))
                for (int mip = 0; mip < mips; mip++)
                {
                    int mipWidth = Math.Max(1, width >> mip), mipHeight = Math.Max(1, height >> mip);
                    int mipSlices = texture.ClassName == "VolumeTexture" ? Math.Max(1, slices >> mip) : slices;
                    var surface = new UnrealTextureDelta.Surface(checked((int)offset), mipWidth, mipHeight, mipSlices, format);
                    levels.Add(new Level(index, layer, mip, surface));
                    offset = checked(offset + (long)mipWidth * mipHeight * mipSlices * BytesPerPixel(format));
                }
        }
        long end = 0;
        foreach (Level level in levels.OrderBy(value => value.Surface.Offset))
        {
            var surface = level.Surface;
            if (surface.Offset != end) throw new ConversionException($"Texture source mip regions overlap or have gaps: {asset.Name}");
            end = checked(end + (long)surface.Width * surface.Height * surface.Slices * BytesPerPixel(surface.Format));
        }
        string compression = source.Value<string>("CompressionFormat") ?? "TSCF_None";
        if (compression is "TSCF_PNG" or "TSCF_JPEG")
        {
            if (levels.Count != 1 || blocks.Count != 1 || formats.Length != 1)
                throw new ConversionException($"Compressed source image has multiple blocks, layers or mips: {asset.Name}");
            payload = TextureSourceImageCodec.Decode(payload, levels[0].Surface, compression);
        }
        if (end != payload.LongLength) throw new ConversionException($"Texture source mip sizes differ from its payload: {asset.Name}");
        byte[] pixels = compression switch {
            "TSCF_None" or "TSCF_PNG" or "TSCF_JPEG" => payload,
            "TSCF_UEDELTA" => UnrealTextureDelta.Decode(payload, levels.Select(value => value.Surface).ToArray()),
            _ => throw new ConversionException($"Texture source image compression requires its image codec: {compression} ({asset.Name})") };
        return new Image(pixels, levels.AsReadOnly());
    }

    private static int RequiredDimension(JObject block, string field, string asset)
    {
        int value = block.Value<int?>(field) ?? throw new ConversionException($"Missing source {field}: {asset}");
        if (value <= 0) throw new ConversionException($"Invalid source {field}: {asset}");
        return value;
    }

    internal static int BytesPerPixel(string format) => format switch {
        "TSF_G8" => 1,
        "TSF_G16" or "TSF_R16F" => 2,
        "TSF_BGRA8" or "TSF_BGRE8" or "TSF_RGBA8_DEPRECATED" or "TSF_RGBE8_DEPRECATED" or "TSF_R32F" => 4,
        "TSF_RGBA16" or "TSF_RGBA16F" => 8,
        "TSF_RGBA32F" => 16,
        _ => throw new ConversionException($"Unsupported texture source pixel format {format}") };
}
