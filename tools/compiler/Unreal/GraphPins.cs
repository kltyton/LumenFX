using System.Text;
using LumenFx.Compiler.Unreal.Serialization;

namespace LumenFx.Compiler.Unreal;

public sealed record PinLink(int Node, Guid Pin);
public enum GraphPinContainer : byte { None, Array, Set, Map }
public sealed record GraphPinTerminal(string Category, string SubCategory, int TypeObject,
    bool IsConst, bool IsWeak, bool IsObjectWrapper);
public sealed record GraphPin(Guid Id, string Name, byte Direction, string DefaultValue, int DefaultObject,
    IReadOnlyList<PinLink> Links, uint Flags)
{
    public GraphPinContainer Container { get; init; }
    public GraphPinTerminal? MapValue { get; init; }
    internal int TypeObject { get; init; }
    internal Guid PersistentId { get; init; }
    internal bool IsInput => Direction == 0;
    internal bool IsOrphaned => (Flags & (1u << 5)) != 0;
    internal bool IgnoresDefault => (Flags & (1u << 3)) != 0;
}

public static class GraphPins
{
    public static IReadOnlyList<GraphPin> Read(SourceAsset asset, SourceObject node)
    {
        byte[] bytes = Convert.FromBase64String(node.Raw.Value<string>("Extras") ?? "");
        if (bytes.Length == 0) return Array.Empty<GraphPin>();
        using BinaryReader reader = new(new MemoryStream(bytes), Encoding.UTF8);
        var archive = new UnrealPackageBinary(reader, asset);
        var text = new UnrealTextHistoryReader(archive);
        bool pinNameIsFName = archive.CustomVersion("{CFFC743F-43B0-4480-9391-14DF171D2073}") >= 31;
        bool hasObjectWrapper = archive.CustomVersion("{9C54D522-A826-4FBE-9421-074661B482D0}") >= 32;
        bool hasSinglePrecisionFlag = archive.CustomVersion("{D89B5E42-24BD-4D46-8412-ACA8DF641779}") >= 36;
        bool hasSourceIndex = archive.CustomVersion("{697DD581-E64F-41AB-AA4A-51ECBEB7B628}") >= 50;
        int count = archive.Count(4096);
        List<GraphPin> pins = new(count);
        for (int i = 0; i < count; i++)
        {
            if (archive.Boolean()) continue;
            Link(reader);
            PinLink identity = Link(reader);
            string name = pinNameIsFName ? archive.Name() : archive.String();
            if (!archive.FiltersEditorOnly) text.Skip();
            if (hasSourceIndex) reader.ReadInt32();
            archive.String();
            byte direction = reader.ReadByte();
            if (pinNameIsFName) { archive.Name(); archive.Name(); }
            else { archive.String(); archive.String(); }
            int typeObject = reader.ReadInt32();
            var container = (GraphPinContainer)reader.ReadByte();
            if (container > GraphPinContainer.Map)
                throw new ConversionException($"Unknown container type {(byte)container} for pin {node.Name}:{name}");
            GraphPinTerminal? mapValue = container == GraphPinContainer.Map
                ? ReadTerminal(archive, pinNameIsFName, hasObjectWrapper) : null;
            archive.Boolean();
            archive.Boolean();
            reader.ReadInt32(); archive.Name(); archive.Skip(16);
            archive.Boolean(); // const
            if (hasObjectWrapper) archive.Boolean();
            if (hasSinglePrecisionFlag) archive.Boolean();
            string defaultValue = archive.String();
            archive.String();
            int defaultObject = reader.ReadInt32();
            text.Skip();
            var links = Links(archive);
            Links(archive);
            OptionalLink(archive); OptionalLink(archive);
            Guid persistentId = archive.FiltersEditorOnly ? Guid.Empty : new Guid(reader.ReadBytes(16));
            uint flags = archive.FiltersEditorOnly ? 0 : reader.ReadUInt32();
            pins.Add(new GraphPin(identity.Pin, name, direction, defaultValue, defaultObject, links, flags) {
                TypeObject = typeObject, PersistentId = persistentId, Container = container, MapValue = mapValue
            });
        }
        if (reader.BaseStream.Position != bytes.Length)
            throw new ConversionException($"Unconsumed graph data in {asset.Name}:{node.Name}: {bytes.Length - reader.BaseStream.Position} bytes");
        return pins;
    }

    private static GraphPinTerminal ReadTerminal(UnrealPackageBinary archive, bool usesNames, bool hasObjectWrapper)
    {
        string category = usesNames ? archive.Name() : archive.String();
        string subCategory = usesNames ? archive.Name() : archive.String();
        int type = archive.Reader.ReadInt32();
        bool isConst = archive.Boolean();
        bool isWeak = archive.Boolean();
        return new GraphPinTerminal(category, subCategory, type, isConst, isWeak,
            hasObjectWrapper && archive.Boolean());
    }

    private static IReadOnlyList<PinLink> Links(UnrealPackageBinary archive)
    {
        int count = archive.Count(16384);
        List<PinLink> result = new(count);
        for (int i = 0; i < count; i++) if (OptionalLink(archive) is { } link) result.Add(link);
        return result;
    }
    private static PinLink? OptionalLink(UnrealPackageBinary archive) => archive.Boolean() ? null : Link(archive.Reader);
    private static PinLink Link(BinaryReader reader) => new(reader.ReadInt32(), new Guid(reader.ReadBytes(16)));
}
