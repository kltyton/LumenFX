using LumenFx.Compiler.Unreal.BulkData;

namespace LumenFx.Compiler.Unreal.TextureSource;

internal static class TextureSourcePayload
{
    internal static byte[] Read(SourceAsset asset, SourceObject texture, OodleDataCodec? oodle)
    {
        string encoded = texture.Raw.Value<string>("Extras")
            ?? throw new ConversionException($"Texture source has no native bulk-data header: {asset.Name}");
        byte[] extra = Convert.FromBase64String(encoded);
        // UTexture writes FStripDataFlags before FEditorBulkData.
        if (extra.Length < 50 || (extra[0] & 1) != 0)
            throw new ConversionException($"Texture editor source was stripped or truncated: {asset.Name}");
        return EditorBulkPayload.Read(asset, extra.AsSpan(2), oodle);
    }
}
