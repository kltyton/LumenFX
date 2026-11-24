using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Serialization;

internal sealed class UnrealPackageBinary(BinaryReader reader, SourceAsset source)
{
    internal BinaryReader Reader => reader;
    internal bool FiltersEditorOnly => source.Raw["PackageFlags"] is { } flags
        && (flags.Type == JTokenType.Integer ? (flags.Value<uint>() & 0x80000000u) != 0
            : (flags.Value<string>() ?? "").Split(',').Any(flag => flag.Trim() == "PKG_FilterEditorOnly"));

    internal int CustomVersion(string id) => (source.Raw["CustomVersionContainer"] as JArray)?.OfType<JObject>()
        .SingleOrDefault(value => string.Equals(value.Value<string>("Key"), id, StringComparison.OrdinalIgnoreCase))
        ?.Value<int>("Version") ?? -1;

    internal int Count(int maximum)
    {
        int count = reader.ReadInt32();
        return count >= 0 && count <= maximum ? count : throw new ConversionException($"Invalid archive count {count} in {source.Name}");
    }

    internal bool Boolean() => reader.ReadInt32() switch {
        0 => false,
        1 => true,
        var value => throw new ConversionException($"Invalid archive boolean {value} in {source.Name}")
    };

    internal string Name()
    {
        int index = reader.ReadInt32(), number = reader.ReadInt32();
        var names = (JArray)source.Raw["NameMap"]!;
        if ((uint)index >= names.Count || number < 0)
            throw new ConversionException($"Invalid archive name reference in {source.Name}");
        string name = names[index].Value<string>()!;
        return number == 0 ? name : name + "_" + (number - 1);
    }

    internal string String()
    {
        int count = reader.ReadInt32();
        if (count == 0) return "";
        if (count is < -1048576 or > 1048576)
            throw new ConversionException($"Invalid archive string length in {source.Name}: {count}");
        int length = count < 0 ? -count * 2 : count;
        byte[] bytes = reader.ReadBytes(length);
        if (bytes.Length != length) throw new EndOfStreamException();
        return (count < 0 ? Encoding.Unicode.GetString(bytes) : Encoding.UTF8.GetString(bytes)).TrimEnd('\0');
    }

    internal void Skip(int bytes)
    {
        Stream stream = reader.BaseStream;
        if (stream.Length - stream.Position < bytes) throw new EndOfStreamException();
        stream.Position += bytes;
    }
}
