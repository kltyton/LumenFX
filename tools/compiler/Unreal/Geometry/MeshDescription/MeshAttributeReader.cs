// MeshDescription attribute decoding adapted from @threenative/raw-unreal 0.1.9.
// Copyright (c) 2026 ThreeNative contributors. MIT; see licenses/ThreeNative-raw-unreal.txt.
namespace LumenFx.Compiler.Unreal.Geometry.MeshDescription;

internal static class MeshAttributeReader
{
    internal static MeshAttribute Read(MeshSourceArchive input, string name, int slots, bool packedNames)
    {
        uint typeIndex = input.UInt();
        if (typeIndex > (uint)MeshAttributeType.Name)
            throw new ConversionException($"Unknown mesh attribute type {typeIndex}: {name}");
        var type = (MeshAttributeType)typeIndex;
        int components = type switch { MeshAttributeType.Vector4 => 4,
            MeshAttributeType.Vector3 => 3, MeshAttributeType.Vector2 => 2, _ => 1 };
        uint rawExtent = input.UInt();
        if (rawExtent is 0 or > 64) throw new ConversionException($"Invalid mesh attribute extent: {name}");
        int extent = (int)rawExtent;
        int elements = input.Count("attribute elements", 10_000_000);
        if (elements != slots) throw new ConversionException($"Mesh attribute slots disagree: {name}");
        int channelCount = input.Count("attribute channels", 128);
        var channels = new Array[channelCount];
        for (int channel = 0; channel < channelCount; channel++)
        {
            if (input.UInt() != extent) throw new ConversionException($"Mesh channel extent disagrees: {name}");
            int size = type == MeshAttributeType.Name ? 0 : input.Int();
            int expectedSize = type == MeshAttributeType.Bool ? 1 : components * 4;
            if (type != MeshAttributeType.Name && size != expectedSize)
                throw new ConversionException($"Mesh attribute element size disagrees: {name}");
            int count = input.Count("attribute values", 100_000_000);
            if (count != checked(elements * extent))
                throw new ConversionException($"Mesh attribute value count disagrees: {name}");
            if (type != MeshAttributeType.Name) input.Require((long)count * size);
            channels[channel] = packedNames && type == MeshAttributeType.Name
                ? PackedNames(input, count) : Values(input, type, components, count, false);
        }
        Array defaultValue = Values(input, type, components, 1, true);
        return new MeshAttribute(type, components, extent, elements, channels, defaultValue, input.UInt());
    }

    private static string[] PackedNames(MeshSourceArchive input, int count)
    {
        if (count == 0) return Array.Empty<string>();
        int uniqueCount = input.Count("unique attribute names", count);
        if (uniqueCount == 0) throw new ConversionException("Nonempty mesh name channel has no names");
        input.Require((long)uniqueCount * 4);
        var unique = new string[uniqueCount];
        for (int index = 0; index < uniqueCount; index++) unique[index] = input.String();
        if (uniqueCount == count) return unique;

        var values = new string[count];
        if (uniqueCount == 1)
        {
            Array.Fill(values, unique[0]);
            return values;
        }

        int width = input.Byte() switch {
            0 => 1, 1 => 2, 2 => 4,
            _ => throw new ConversionException("Invalid packed mesh name index width")
        };
        input.Require((long)count * width);
        for (int index = 0; index < count; index++)
        {
            uint name = width switch { 1 => input.Byte(), 2 => input.UShort(), _ => input.UInt() };
            if (name >= uniqueCount) throw new ConversionException("Packed mesh name index is outside its name table");
            values[index] = unique[name];
        }
        return values;
    }

    private static Array Values(MeshSourceArchive input, MeshAttributeType type, int components,
        int count, bool defaultValue)
    {
        switch (type)
        {
            case MeshAttributeType.Name:
                var names = new string[count];
                for (int i = 0; i < count; i++) names[i] = input.String();
                return names;
            case MeshAttributeType.Bool:
                var booleans = new byte[count];
                for (int i = 0; i < count; i++) booleans[i] = defaultValue
                    ? input.UInt() == 0 ? (byte)0 : (byte)1 : input.Byte();
                return booleans;
            case MeshAttributeType.Int:
                var integers = new int[count];
                for (int i = 0; i < count; i++) integers[i] = input.Int();
                return integers;
            case MeshAttributeType.Vector4:
            case MeshAttributeType.Vector3:
            case MeshAttributeType.Vector2:
            case MeshAttributeType.Float:
                var values = new float[checked(count * components)];
                for (int i = 0; i < values.Length; i++) values[i] = input.Float();
                return values;
            default: throw new ConversionException($"Unknown mesh attribute type: {type}");
        }
    }
}
