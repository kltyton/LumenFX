using System.Buffers.Binary;
using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

/// <summary>
/// Decodes standard Unreal tagged UProperty streams from UAssetAPI RawExport payloads when an
/// export's trailing native serializer caused UAssetAPI to leave the export undecoded (for example
/// UStaticMesh exports whose tagged fields precede UStaticMesh::Serialize).
/// </summary>
internal static class RawTaggedPropertyDecoder
{
    internal enum Format { Legacy, LegacyWithExtensions, CompleteType, Unversioned }

    internal static JObject DecodeRotator3f(ReadOnlySpan<byte> payload)
    {
        if (payload.Length != 3 * sizeof(float))
            throw new ConversionException("Unreal Rotator3f must contain three float32 angles");
        return new JObject {
            ["Pitch"] = BinaryPrimitives.ReadSingleLittleEndian(payload),
            ["Yaw"] = BinaryPrimitives.ReadSingleLittleEndian(payload[sizeof(float)..]),
            ["Roll"] = BinaryPrimitives.ReadSingleLittleEndian(payload[(2 * sizeof(float))..])
        };
    }

    internal static Format PackageFormat(JObject package)
    {
        if (package.Value<bool>("HasUnversionedProperties")) return Format.Unversioned;
        var version = package["ObjectVersionUE5"];
        if (version?.Type == JTokenType.Integer)
            return version.Value<int>() switch { >= 1012 => Format.CompleteType, 1011 => Format.LegacyWithExtensions, _ => Format.Legacy };
        return version?.Value<string>() switch {
            "PROPERTY_TAG_EXTENSION_AND_OVERRIDABLE_SERIALIZATION" => Format.LegacyWithExtensions,
            "PROPERTY_TAG_COMPLETE_TYPE_NAME" or "ASSETREGISTRY_PACKAGEBUILDDEPENDENCIES" or
            "METADATA_SERIALIZATION_OFFSET" or "VERSE_CELLS" or "PACKAGE_SAVED_HASH" or
            "OS_SUB_OBJECT_SHADOW_SERIALIZATION" or "IMPORT_TYPE_HIERARCHIES" or
            "AUTOMATIC_VERSION" or "AUTOMATIC_VERSION_PLUS_ONE" => Format.CompleteType,
            _ => Format.Legacy
        };
    }

    internal static bool TryDecode(string base64Payload, IReadOnlyList<string>? nameMap, Format format, out JObject? properties)
    {
        properties = null;
        if (format == Format.Unversioned || nameMap is null || nameMap.Count == 0 || string.IsNullOrEmpty(base64Payload)) return false;
        byte[] bytes;
        try { bytes = Convert.FromBase64String(base64Payload); }
        catch (FormatException) { return false; }
        if (bytes.Length < 8) return false;
        try {
            var reader = new Reader(bytes, nameMap, format);
            var decoded = reader.ReadObject();
            if (decoded.Count == 0) return false;
            properties = decoded;
            return true;
        } catch (Exception error) when (error is InvalidDataException or EndOfStreamException or OverflowException) {
            return false;
        }
    }

    private sealed record PropertyType(string Name, PropertyType[] Parameters)
    {
        internal PropertyType? Parameter(int index) => index < Parameters.Length ? Parameters[index] : null;
    }

    private sealed class Reader(byte[] data, IReadOnlyList<string> names, Format format)
    {
        private int position;
        private int boundary = data.Length;

        internal JObject ReadObject()
        {
            // The versioned UObject carries serialization control before its property list.
            // Structs and collection elements start directly with their tags.
            if (format != Format.Legacy) {
                byte control = ReadByte();
                if ((control & ~0x02) != 0) throw new InvalidDataException("Unknown object serialization control");
                if ((control & 0x02) != 0) ReadByte(); // EOverriddenPropertyOperation
            }
            return ReadProperties(data.Length);
        }

        internal JObject ReadProperties(int limit)
        {
            var result = new JObject();
            var staticArrays = new Dictionary<string, JArray>();
            while (position < limit) {
                string name = ReadName();
                if (name == "None") return result;
                PropertyType? completeType = format == Format.CompleteType ? ReadType(0) : null;
                string type = completeType?.Name ?? ReadName();
                int size = ReadInt32();
                if (size < 0) throw new InvalidDataException($"Negative tagged property size: {name}");
                string? structName = null, innerType = null, keyType = null, valueType = null, enumName = null;
                int arrayIndex;
                bool boolValue = false;
                byte flags = 0;
                bool usesContainerDelta = false;
                if (completeType is not null) {
                    flags = ReadByte();
                    if ((flags & ~0x3f) != 0) throw new InvalidDataException($"Unknown property tag flags: {name}");
                    arrayIndex = (flags & 0x01) != 0 ? ReadInt32() : 0;
                    if ((flags & 0x02) != 0) Skip(16);
                    if ((flags & 0x04) != 0) usesContainerDelta = ReadExtensions();
                    boolValue = (flags & 0x10) != 0;
                    if (type == "StructProperty") structName = completeType.Parameter(0)?.Name;
                    if (type is "EnumProperty" or "ByteProperty") enumName = completeType.Parameter(0)?.Name;
                } else {
                    arrayIndex = ReadInt32();
                    switch (type) {
                        case "StructProperty":
                            structName = ReadName();
                            Skip(16); // StructGuid
                            break;
                        case "BoolProperty":
                            boolValue = ReadByte() != 0;
                            break;
                        case "ByteProperty":
                        case "EnumProperty":
                            enumName = ReadName();
                            break;
                        case "ArrayProperty":
                        case "SetProperty":
                            innerType = ReadName();
                            break;
                        case "MapProperty":
                            keyType = ReadName();
                            valueType = ReadName();
                            break;
                    }
                    if (ReadByte() != 0) Skip(16);
                    if (format == Format.LegacyWithExtensions) usesContainerDelta = ReadExtensions();
                }
                if (size > limit - position) throw new InvalidDataException($"Property {name} exceeds buffer bounds");
                if (arrayIndex < 0 || arrayIndex > 0xffff) throw new InvalidDataException($"Invalid static array index: {name}");
                int next = position + size;
                if ((flags & 0x20) != 0) {
                    position = next;
                    continue;
                }
                int previousBoundary = boundary;
                boundary = next;
                JToken value = ReadPropertyValue(type, size, boolValue, structName, innerType, keyType, valueType,
                    enumName, completeType, usesContainerDelta);
                boundary = previousBoundary;
                position = next;
                if (arrayIndex == 0 && result[name] is null) {
                    result[name] = value;
                } else {
                    if (!staticArrays.TryGetValue(name, out var list)) {
                        list = new JArray();
                        if (result[name] is { } existing) list.Add(existing);
                        staticArrays.Add(name, list);
                        result[name] = list;
                    }
                    while (list.Count <= arrayIndex) list.Add(JValue.CreateNull());
                    list[arrayIndex] = value;
                }
            }
            throw new InvalidDataException("Missing tagged property terminator");
        }

        private PropertyType ReadType(int depth)
        {
            if (depth == 64) throw new InvalidDataException("Property type name is nested too deeply");
            string name = ReadName();
            int count = ReadInt32();
            if (count < 0 || count > (boundary - position) / 12)
                throw new InvalidDataException($"Invalid parameter count for property type {name}");
            var parameters = new PropertyType[count];
            for (int i = 0; i < count; i++) parameters[i] = ReadType(depth + 1);
            return new PropertyType(name, parameters);
        }

        private bool ReadExtensions()
        {
            byte extensions = ReadByte();
            if ((extensions & ~0x06) != 0) throw new InvalidDataException("Unknown property tag extension");
            bool overridable = false;
            if ((extensions & 0x02) != 0) {
                ReadByte(); // EOverriddenPropertyOperation
                overridable = ReadArchiveBool();
            }
            bool external = (extensions & 0x04) != 0 && ReadArchiveBool();
            return overridable || external;
        }

        private JToken ReadPropertyValue(string type, int size, bool boolValue, string? structName,
            string? innerType, string? keyType, string? valueType, string? enumName, PropertyType? completeType,
            bool usesContainerDelta)
        {
            int end = position + size;
            if (usesContainerDelta && type is "ArrayProperty" or "MapProperty" or "SetProperty")
                throw new InvalidDataException($"Raw container uses overridable serialization: {type}");
            return type switch {
                "BoolProperty" => new JValue(boolValue),
                "IntProperty" => new JValue(ReadInt32()),
                "UInt32Property" => new JValue(ReadUInt32()),
                "Int64Property" => new JValue(ReadInt64()),
                "UInt64Property" => new JValue(ReadUInt64()),
                "Int16Property" => new JValue(ReadInt16()),
                "UInt16Property" => new JValue(ReadUInt16()),
                "Int8Property" => new JValue((sbyte)ReadByte()),
                "FloatProperty" => new JValue(ReadSingle()),
                "DoubleProperty" => new JValue(ReadDouble()),
                "ObjectProperty" => new JValue(ReadInt32()),
                "NameProperty" => new JValue(ReadName()),
                "StrProperty" => new JValue(ReadFString()),
                "ByteProperty" => enumName is not null && enumName != "None" && size == 8
                    ? new JValue(ReadName()) : new JValue(ReadByte()),
                "EnumProperty" => size == 8 ? new JValue(ReadName()) : new JValue(ReadByte()),
                "StructProperty" => ReadStructValue(structName ?? "", end),
                "ArrayProperty" when completeType is not null => ReadCompleteArray(completeType, end),
                "MapProperty" when completeType is not null => ReadCompleteMap(completeType, end),
                "SetProperty" when completeType is not null => ReadCompleteSet(completeType, end),
                "ArrayProperty" => ReadArrayValue(innerType ?? "", end),
                "MapProperty" => ReadMapValue(keyType ?? "", valueType ?? "", end),
                "SetProperty" => ReadSetValue(innerType ?? "", end),
                _ => new JValue(Convert.ToBase64String(data, position, size))
            };
        }

        private JToken ReadStructValue(string structName, int end)
        {
            int remaining = end - position;
            switch (structName) {
                case "Vector" or "Vector3d" when remaining == 24:
                    return new JObject { ["X"] = ReadDouble(), ["Y"] = ReadDouble(), ["Z"] = ReadDouble() };
                case "Vector3f" when remaining == 12:
                    return new JObject { ["X"] = ReadSingle(), ["Y"] = ReadSingle(), ["Z"] = ReadSingle() };
                case "Vector2D" when remaining == 16:
                    return new JObject { ["X"] = ReadDouble(), ["Y"] = ReadDouble() };
                case "Vector2f" when remaining == 8:
                    return new JObject { ["X"] = ReadSingle(), ["Y"] = ReadSingle() };
                case "IntPoint" or "Int32Point" when remaining == 8:
                    return new JObject { ["X"] = ReadInt32(), ["Y"] = ReadInt32() };
                case "Rotator" or "Rotator3d" when remaining == 24:
                    return new JObject { ["Pitch"] = ReadDouble(), ["Yaw"] = ReadDouble(), ["Roll"] = ReadDouble() };
                case "Rotator3f" when remaining == 12: {
                    Ensure(remaining);
                    JObject rotator = DecodeRotator3f(data.AsSpan(position, remaining));
                    position += remaining;
                    return rotator;
                }
                case "Quat" or "Quat4d" when remaining == 32:
                    return new JObject { ["X"] = ReadDouble(), ["Y"] = ReadDouble(), ["Z"] = ReadDouble(), ["W"] = ReadDouble() };
                case "Quat4f" when remaining == 16:
                    return new JObject { ["X"] = ReadSingle(), ["Y"] = ReadSingle(), ["Z"] = ReadSingle(), ["W"] = ReadSingle() };
                case "LinearColor" when remaining == 16:
                    return new JObject { ["R"] = ReadSingle(), ["G"] = ReadSingle(), ["B"] = ReadSingle(), ["A"] = ReadSingle() };
                case "Guid" when remaining == 16: {
                    var guid = new Guid(data.AsSpan(position, 16));
                    position += 16;
                    return new JValue(guid.ToString("B").ToUpperInvariant());
                }
                case "PerPlatformFloat" or "PerPlatformInt" or "PerPlatformBool" when remaining >= 8:
                    return ReadPerPlatform(structName);
                case "PerQualityLevelInt" or "PerQualityLevelFloat" when remaining >= 8:
                    return ReadPerQualityLevel(structName);
                default:
                    return ReadProperties(end);
            }
        }

        private JObject ReadPerPlatform(string structName)
        {
            bool cooked = ReadArchiveBool();
            JToken defaultVal = structName switch {
                "PerPlatformFloat" => new JValue(ReadSingle()),
                "PerPlatformInt" => new JValue(ReadInt32()),
                _ => new JValue(ReadArchiveBool())
            };
            var overrides = new JObject();
            if (!cooked) {
                int count = ReadCount(12);
                for (int i = 0; i < count; i++) {
                    string platform = ReadName();
                    overrides[platform] = structName switch {
                        "PerPlatformFloat" => new JValue(ReadSingle()),
                        "PerPlatformInt" => new JValue(ReadInt32()),
                        _ => new JValue(ReadArchiveBool())
                    };
                }
            }
            return new JObject { ["default"] = defaultVal, ["overrides"] = overrides, ["cooked"] = cooked };
        }

        private JObject ReadPerQualityLevel(string structName)
        {
            bool cooked = ReadArchiveBool();
            JToken defaultVal = structName == "PerQualityLevelFloat" ? new JValue(ReadSingle()) : new JValue(ReadInt32());
            var overrides = new JObject();
            int count = ReadCount(8);
            for (int i = 0; i < count; i++) {
                int quality = ReadInt32();
                overrides[quality.ToString()] = structName == "PerQualityLevelFloat"
                    ? new JValue(ReadSingle()) : new JValue(ReadInt32());
            }
            return new JObject { ["default"] = defaultVal, ["overrides"] = overrides, ["cooked"] = cooked };
        }

        private JArray ReadCompleteArray(PropertyType type, int end)
        {
            PropertyType element = type.Parameter(0) ?? throw new InvalidDataException("Array element type is missing");
            int count = ReadCount(1);
            var result = new JArray();
            for (int i = 0; i < count; i++) result.Add(ReadCompleteElement(element, end));
            return result;
        }

        private JArray ReadCompleteMap(PropertyType type, int end)
        {
            PropertyType key = type.Parameter(0) ?? throw new InvalidDataException("Map key type is missing");
            PropertyType value = type.Parameter(1) ?? throw new InvalidDataException("Map value type is missing");
            int removed = ReadCount(1);
            for (int i = 0; i < removed; i++) ReadCompleteElement(key, end);
            int count = ReadCount(2);
            var result = new JArray();
            for (int i = 0; i < count; i++) result.Add(new JArray(ReadCompleteElement(key, end), ReadCompleteElement(value, end)));
            return result;
        }

        private JArray ReadCompleteSet(PropertyType type, int end)
        {
            PropertyType element = type.Parameter(0) ?? throw new InvalidDataException("Set element type is missing");
            int removed = ReadCount(1);
            for (int i = 0; i < removed; i++) ReadCompleteElement(element, end);
            int count = ReadCount(1);
            var result = new JArray();
            for (int i = 0; i < count; i++) result.Add(ReadCompleteElement(element, end));
            return result;
        }

        private JToken ReadCompleteElement(PropertyType type, int end)
        {
            if (type.Name != "StructProperty") return ReadScalarOrTaggedElement(type.Name, end);
            string name = type.Parameter(0)?.Name ?? throw new InvalidDataException("Struct element type is missing");
            int size = name switch {
                "Vector" or "Vector3d" or "Rotator" or "Rotator3d" => 24,
                "Vector3f" or "Rotator3f" => 12,
                "Vector2D" or "Quat4f" or "LinearColor" or "Guid" => 16,
                "Vector2f" => 8,
                "Quat" or "Quat4d" => 32,
                _ => 0
            };
            return size == 0 ? ReadProperties(end) : ReadStructValue(name, checked(position + size));
        }

        private int ReadCount(int minimumSize)
        {
            int count = ReadInt32();
            if (count < 0 || count > (boundary - position) / minimumSize)
                throw new InvalidDataException("Invalid tagged property collection count");
            return count;
        }

        private JArray ReadArrayValue(string innerType, int end)
        {
            int count = ReadInt32();
            if (count < 0) throw new InvalidDataException("Negative array count");
            var items = new JArray();
            if (count == 0) return items;
            if (innerType == "StructProperty") {
                ReadName(); // inner tag name
                string tagType = ReadName();
                if (tagType != "StructProperty") throw new InvalidDataException($"Expected inner StructProperty tag, got {tagType}");
                int innerSize = ReadInt32();
                ReadInt32(); // array index
                string structName = ReadName();
                Skip(16); // struct guid
                byte hasGuid = ReadByte();
                if (hasGuid != 0) Skip(16);
                int structEnd = Math.Min(end, position + innerSize);
                int elemStride = count > 0 && innerSize % count == 0 ? innerSize / count : -1;
                for (int i = 0; i < count; i++) {
                    bool fixedNative = structName is "Vector" or "Vector3d" or "Vector3f" or "Vector2D" or "Vector2f"
                        or "Rotator" or "Rotator3d" or "Rotator3f" or "Quat" or "Quat4d" or "Quat4f" or "LinearColor" or "Guid";
                    items.Add(fixedNative && elemStride > 0
                        ? ReadStructValue(structName, position + elemStride)
                        : ReadProperties(structEnd));
                }
                return items;
            }
            for (int i = 0; i < count; i++) items.Add(ReadScalarOrTaggedElement(innerType, end));
            return items;
        }

        private JArray ReadMapValue(string keyType, string valueType, int end)
        {
            int keysToRemove = ReadInt32();
            for (int i = 0; i < keysToRemove; i++) ReadScalarOrTaggedElement(keyType, end);
            int count = ReadInt32();
            if (count < 0) throw new InvalidDataException("Negative map count");
            var entries = new JArray();
            for (int i = 0; i < count; i++) {
                JToken key = ReadScalarOrTaggedElement(keyType, end);
                JToken val = valueType == "StructProperty" ? ReadProperties(end) : ReadScalarOrTaggedElement(valueType, end);
                entries.Add(new JArray(key, val));
            }
            return entries;
        }

        private JArray ReadSetValue(string innerType, int end)
        {
            int removeCount = ReadInt32();
            for (int i = 0; i < removeCount; i++) ReadScalarOrTaggedElement(innerType, end);
            int count = ReadInt32();
            if (count < 0) throw new InvalidDataException("Negative set count");
            var items = new JArray();
            for (int i = 0; i < count; i++)
                items.Add(innerType == "StructProperty" ? ReadProperties(end) : ReadScalarOrTaggedElement(innerType, end));
            return items;
        }

        private JToken ReadScalarOrTaggedElement(string type, int end) => type switch {
            "BoolProperty" => new JValue(ReadByte() != 0),
            "ByteProperty" => new JValue(ReadByte()),
            "Int8Property" => new JValue((sbyte)ReadByte()),
            "Int16Property" => new JValue(ReadInt16()),
            "UInt16Property" => new JValue(ReadUInt16()),
            "IntProperty" or "ObjectProperty" => new JValue(ReadInt32()),
            "UInt32Property" => new JValue(ReadUInt32()),
            "Int64Property" => new JValue(ReadInt64()),
            "UInt64Property" => new JValue(ReadUInt64()),
            "FloatProperty" => new JValue(ReadSingle()),
            "DoubleProperty" => new JValue(ReadDouble()),
            "NameProperty" or "EnumProperty" => new JValue(ReadName()),
            "StrProperty" => new JValue(ReadFString()),
            "StructProperty" => ReadProperties(end),
            _ => throw new InvalidDataException($"Unsupported raw element type: {type}")
        };

        private string ReadName()
        {
            int index = ReadInt32();
            int number = ReadInt32();
            if ((uint)index >= (uint)names.Count || number < 0)
                throw new InvalidDataException($"Invalid FName ({index}, {number}) at {position - 8}");
            string baseName = names[index];
            return number == 0 ? baseName : $"{baseName}_{number - 1}";
        }

        private string ReadFString()
        {
            int length = ReadInt32();
            if (length == 0) return string.Empty;
            if (length > 0) {
                Ensure(length);
                string value = Encoding.UTF8.GetString(data, position, Math.Max(0, length - 1));
                position += length;
                return value;
            }
            int byteCount = checked(-length * 2);
            Ensure(byteCount);
            string wide = Encoding.Unicode.GetString(data, position, Math.Max(0, byteCount - 2));
            position += byteCount;
            return wide;
        }

        private byte ReadByte() { Ensure(1); return data[position++]; }
        private short ReadInt16() { Ensure(2); short v = BinaryPrimitives.ReadInt16LittleEndian(data.AsSpan(position, 2)); position += 2; return v; }
        private ushort ReadUInt16() { Ensure(2); ushort v = BinaryPrimitives.ReadUInt16LittleEndian(data.AsSpan(position, 2)); position += 2; return v; }
        private int ReadInt32() { Ensure(4); int v = BinaryPrimitives.ReadInt32LittleEndian(data.AsSpan(position, 4)); position += 4; return v; }
        private uint ReadUInt32() { Ensure(4); uint v = BinaryPrimitives.ReadUInt32LittleEndian(data.AsSpan(position, 4)); position += 4; return v; }
        private long ReadInt64() { Ensure(8); long v = BinaryPrimitives.ReadInt64LittleEndian(data.AsSpan(position, 8)); position += 8; return v; }
        private ulong ReadUInt64() { Ensure(8); ulong v = BinaryPrimitives.ReadUInt64LittleEndian(data.AsSpan(position, 8)); position += 8; return v; }
        private float ReadSingle() { Ensure(4); float v = BinaryPrimitives.ReadSingleLittleEndian(data.AsSpan(position, 4)); position += 4; return v; }
        private double ReadDouble() { Ensure(8); double v = BinaryPrimitives.ReadDoubleLittleEndian(data.AsSpan(position, 8)); position += 8; return v; }
        private void Skip(int count) { Ensure(count); position += count; }
        private bool ReadArchiveBool() => ReadInt32() switch {
            0 => false,
            1 => true,
            _ => throw new InvalidDataException("Invalid serialized bool")
        };
        private void Ensure(int count) { if (count < 0 || count > boundary - position) throw new EndOfStreamException(); }
    }
}
