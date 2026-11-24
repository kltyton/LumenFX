using System.Buffers.Binary;
using LumenFx.Compiler.Unreal.BulkData;
using LumenFx.Compiler.Unreal.TextureSource;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshDescription;

internal static class MeshDescriptionSource
{
    private const string FortniteMainBranch = "{601D1886-AC64-4F84-AA16-D3DE0DEAC7D6}";
    private const int DynamicMeshAttributesTriangleLabels = 257;

    internal static MeshDescriptionData? Read(SourceAsset asset, SourceObject bulk, OodleDataCodec? oodle)
    {
        if (bulk.ClassName != "StaticMeshDescriptionBulkData")
            throw new ConversionException($"Expected StaticMeshDescriptionBulkData: {asset.Name}.{bulk.Name}");
        string encoded = bulk.Raw.Value<string>("Extras")
            ?? throw new ConversionException($"Mesh description has no native payload header: {asset.Name}.{bulk.Name}");
        byte[] extra = Convert.FromBase64String(encoded);
        if (extra.Length < 68) throw new ConversionException($"Truncated mesh bulk-data header: {asset.Name}.{bulk.Name}");
        long rawSize = BinaryPrimitives.ReadInt64LittleEndian(extra.AsSpan(40));
        if (rawSize == 0) return null;
        byte[] payload = EditorBulkPayload.Read(asset, extra, oodle);
        int version = (asset.Raw["CustomVersionContainer"] as JArray)?.OfType<JObject>()
            .SingleOrDefault(entry => string.Equals(entry.Value<string>("Key"), FortniteMainBranch, StringComparison.OrdinalIgnoreCase))
            ?.Value<int>("Version") ?? -1;
        return MeshDescriptionReader.Read(payload, version >= DynamicMeshAttributesTriangleLabels) with { SourceArchive = payload };
    }
}
