using System.Text;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.GeometryCollection;

internal sealed class GeometryCollectionAssetSource(NativeAssetTools tools)
{
    private readonly Dictionary<string, JObject> assets = new(SourceNameOrder.Instance);

    internal JObject Read(string source)
    {
        if (!assets.TryGetValue(source, out var asset)) {
            asset = NativeAssetExchange.ExportAsync(source, tools, "geometry_collection", ".lfgc",
                bytes => Decode(bytes, source), operation: "geometry-collection").GetAwaiter().GetResult();
            assets.Add(source, asset);
        }
        return (JObject)asset.DeepClone();
    }

    private static JObject Decode(byte[] data, string source)
    {
        if (data.Length < 16 || !data.AsSpan(0, 4).SequenceEqual("LFGC"u8))
            throw new ConversionException($"Invalid native geometry collection archive: {source}");
        using var reader = new BinaryReader(new MemoryStream(data, false), Encoding.UTF8);
        reader.ReadUInt32();
        uint version = reader.ReadUInt32();
        int transforms = reader.ReadInt32(), pieces = reader.ReadInt32();
        if (version != 1 || transforms < 0 || pieces < 0 || 16L + transforms * 80L + pieces * 56L != data.Length)
            throw new ConversionException($"Invalid native geometry collection layout: {source}");
        var rest = new JArray();
        for (int index = 0; index < transforms; index++) rest.Add(Vector(reader, 10));
        var geometry = new JArray();
        for (int index = 0; index < pieces; index++) {
            int transform = reader.ReadInt32();
            uint leaf = reader.ReadUInt32();
            if ((uint)transform >= transforms || leaf > 1)
                throw new ConversionException($"Invalid native geometry collection piece {index}: {source}");
            geometry.Add(new JObject {
                ["transform"] = transform, ["leaf"] = leaf == 1, ["minimum"] = Vector(reader, 3), ["maximum"] = Vector(reader, 3)
            });
        }
        return new JObject { ["rest"] = rest, ["pieces"] = geometry };
    }

    private static JArray Vector(BinaryReader reader, int count)
    {
        var values = new JArray();
        for (int i = 0; i < count; i++) values.Add(reader.ReadDouble());
        return values;
    }
}
