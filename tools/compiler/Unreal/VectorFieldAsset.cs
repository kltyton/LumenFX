using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public static class VectorFieldAsset
{
    public static byte[] Convert(SourceAsset asset)
        => Convert(asset, asset.Objects.Single(value => value.ClassName == "VectorFieldStatic"));

    internal static byte[] Convert(SourceAsset asset, SourceObject field)
    {
        if (field.ClassName != "VectorFieldStatic")
            throw new ConversionException($"Expected a static vector field: {asset.ObjectPath(field.Index)}");
        var properties = field.Properties;
        int x = properties.Value<int>("SizeX"), y = properties.Value<int>("SizeY"), z = properties.Value<int>("SizeZ");
        int expected = checked(x * y * z * 8);
        if (x <= 0 || y <= 0 || z <= 0) throw new ConversionException($"Invalid vector field dimensions: {asset.Name}");
        byte[] extra = System.Convert.FromBase64String(field.Raw.Value<string>("Extras") ?? "");
        using BinaryReader source = new(new MemoryStream(extra), Encoding.UTF8);
        uint flags = source.ReadUInt32();
        if ((flags & ~1u) != 0) throw new ConversionException($"Unsupported vector field bulk flags 0x{flags:x}: {asset.Name}");
        int elements = source.ReadInt32(), length = source.ReadInt32();
        long offset = source.ReadInt64();
        if (elements != expected || length != expected || source.BaseStream.Position != extra.Length)
            throw new ConversionException($"Vector field bulk layout differs: {asset.Name}");
        byte[] bulk = System.Convert.FromBase64String(asset.Raw.Value<string>("AdditionalFiles") ?? "");
        if (offset < 0 || offset > bulk.Length - length) throw new ConversionException($"Vector field payload outside source bulk data: {asset.Name}");
        var bounds = properties["Bounds"] as JObject ?? throw new ConversionException($"Missing vector field bounds: {asset.Name}");
        using MemoryStream output = new();
        using BinaryWriter writer = new(output, Encoding.UTF8, true);
        writer.Write(0x4656464c); // LFVF, little endian.
        writer.Write(3);
        writer.Write(x); writer.Write(y); writer.Write(z);
        foreach (string point in new[] { "Min", "Max" }) foreach (string axis in new[] { "X", "Y", "Z" })
            writer.Write(SourceAsset.Number(bounds[point]?[axis]));
        writer.Write(8);
        writer.Write(bulk, checked((int)offset), length);
        return output.ToArray();
    }
}
