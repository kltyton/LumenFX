// Element-container decoding adapted from @threenative/raw-unreal 0.1.9.
// Copyright (c) 2026 ThreeNative contributors. MIT; see licenses/ThreeNative-raw-unreal.txt.
namespace LumenFx.Compiler.Unreal.Geometry.MeshDescription;

internal static class MeshDescriptionReader
{
    internal static MeshDescriptionData Read(byte[] payload, bool packedNames)
    {
        using var input = new MeshSourceArchive(payload);
        int count = input.Count("element types", 32);
        if (count == 0) throw new ConversionException("MeshDescription contains no element types");
        var elements = new Dictionary<string, IReadOnlyList<MeshElement>>(StringComparer.Ordinal);
        for (int i = 0; i < count; i++)
        {
            string name = input.String();
            if (name.Length is 0 or >= 128) throw new ConversionException("Invalid mesh element name");
            int channels = input.Count("element channels", 128);
            if (channels == 0) throw new ConversionException($"Mesh element has no channels: {name}");
            var containers = new MeshElement[channels];
            for (int channel = 0; channel < channels; channel++) containers[channel] = ReadElement(input, packedNames);
            if (!elements.TryAdd(name, containers)) throw new ConversionException($"Duplicate mesh element: {name}");
        }
        if (input.Remaining != 0) throw new ConversionException("Unexpected trailing MeshDescription data");
        return new MeshDescriptionData(elements);
    }

    internal static MeshDescriptionData ReadNativeArchive(byte[] payload)
    {
        // The native bridge returns a bare archive. Its writer uses the installed engine's layout.
        try {
            return Read(payload, false);
        } catch (ConversionException direct) {
            try {
                return Read(payload, true);
            } catch (ConversionException packed) {
                throw new ConversionException($"Invalid native MeshDescription ({direct.Message}; {packed.Message})");
            }
        }
    }

    private static MeshElement ReadElement(MeshSourceArchive input, bool packedNames)
    {
        int slots = input.Count("allocation slots", 10_000_000);
        int words = checked((slots + 31) / 32);
        input.Require((long)words * 4);
        var ids = new List<int>();
        for (int word = 0; word < words; word++)
        {
            uint mask = input.UInt();
            for (int bit = 0; bit < 32 && word * 32 + bit < slots; bit++)
                if ((mask & (1U << bit)) != 0) ids.Add(word * 32 + bit);
        }
        int holes = input.Count("allocation holes", slots);
        if (ids.Count != slots - holes) throw new ConversionException("Mesh allocation and holes disagree");
        if (input.Int() != slots) throw new ConversionException("Mesh allocation and attribute slots disagree");
        int attributeCount = input.Count("attributes", 1024);
        var attributes = new Dictionary<string, MeshAttribute>(StringComparer.Ordinal);
        for (int i = 0; i < attributeCount; i++)
        {
            string name = input.String();
            var attribute = MeshAttributeReader.Read(input, name, slots, packedNames);
            if (!attributes.TryAdd(name, attribute)) throw new ConversionException($"Duplicate mesh attribute: {name}");
        }
        return new MeshElement(slots, ids, attributes);
    }
}
