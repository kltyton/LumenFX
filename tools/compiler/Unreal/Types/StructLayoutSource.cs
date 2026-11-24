using System.Runtime.CompilerServices;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Types;

/** Links non-native exported fields from UAssetAPI's StructExport.LoadedProperties. */
internal static class StructLayoutSource
{
    private static readonly ConditionalWeakTable<SourceAsset, Dictionary<int, StructLayout>> Cache = new();

    internal static StructLayout? Find(SourceAsset owner, int reference)
        => Find(owner, reference, new HashSet<string>(SourceNameOrder.Instance));

    internal static bool? IsLwc(SourceAsset owner, int reference)
    {
        if (reference == 0) return false;
        string path = owner.ObjectPath(reference);
        if (BuiltinStructLayouts.TryGet(path, out var builtin)) return HasLwc(builtin!.Fields);
        if (reference < 0) {
            if (path.StartsWith("/Script/", StringComparison.Ordinal) || owner.Library is null) return null;
            SourceAsset external = owner.Library.Load(path);
            SourceObject structure = external.ResolveObject(path, value => value.ClassName == "UserDefinedStruct");
            return IsLwc(external, structure.Index);
        }
        SourceObject source = owner.Object(reference);
        if (source.ClassName != "UserDefinedStruct") return null;
        var fields = StructFieldSource.Read(owner, source);
        if (fields is null) return null;
        foreach (JObject field in fields) {
            string kind = field.Value<string>("SerializedType") ?? "";
            if (SourceNameOrder.Equal(kind, "DoubleProperty")) return true;
            if (SourceNameOrder.Equal(kind, "StructProperty")) {
                string nested = owner.Reference(field.Value<int>("Struct"));
                if (LwcVector(nested[(nested.LastIndexOf('.') + 1)..])) return true;
            }
        }
        return false;
    }

    internal static bool HasLwc(IReadOnlyList<StructField> fields)
        => fields.Any(field => field.Kind == StructFieldKind.Double
            || field.Kind == StructFieldKind.Struct && field.Structure is { } structure && LwcVector(structure.Name));

    private static bool LwcVector(string name)
        => new[] { "Vector2d", "Vector2D", "Vector3d", "Vector", "Vector4d", "Vector4", "Quat4d", "Quat" }
            .Any(value => SourceNameOrder.Equal(value, name));

    private static StructLayout? Find(SourceAsset owner, int reference, HashSet<string> visiting)
    {
        if (reference == 0) return null;
        string path = owner.ObjectPath(reference);
        if (BuiltinStructLayouts.TryGet(path, out var builtIn)) return builtIn;
        if (reference < 0) {
            if (path.StartsWith("/Script/", StringComparison.Ordinal) || owner.Library is null) return null;
            SourceAsset external = owner.Library.Load(path);
            SourceObject structure = external.ResolveObject(path, value => value.ClassName == "UserDefinedStruct");
            return Find(external, structure.Index, visiting);
        }
        var cache = Cache.GetValue(owner, _ => new());
        if (cache.TryGetValue(reference, out var existing)) return existing;
        SourceObject source = owner.Object(reference);
        if (source.ClassName != "UserDefinedStruct") return null;
        var properties = StructFieldSource.Read(owner, source);
        if (properties is null) return null;
        if (!visiting.Add(path)) throw new ConversionException($"Recursive value struct layout: {path}");
        try {
            int super = source.Raw.Value<int?>("SuperStruct") ?? 0;
            StructLayout? parent = super == 0 ? null : Find(owner, super, visiting)
                ?? throw new ConversionException($"Missing superclass layout for {path}");
            int offset = parent?.PropertiesSize ?? 0, alignment = parent?.Alignment ?? 1;
            List<StructField> fields = new();
            foreach (JObject property in properties) {
                StructField field = Field(owner, property, visiting);
                offset = StructLayout.Align(offset, field.Alignment);
                field = field with { Offset = offset };
                offset = checked(offset + field.Size);
                alignment = Math.Max(alignment, field.Alignment);
                fields.Add(field);
            }
            StructLayout result = new(path, source.Name, offset, alignment, Array.AsReadOnly(fields.ToArray()));
            cache.Add(reference, result);
            return result;
        } finally { visiting.Remove(path); }
    }

    private static StructField Field(SourceAsset owner, JObject property, HashSet<string> visiting)
    {
        string name = property.Value<string>("Name") ?? throw new ConversionException("Reflected field has no name");
        string kind = SourceNameOrder.Canonical(property.Value<string>("SerializedType")
            ?? throw new ConversionException($"Reflected field has no serialized type: {name}"));
        int dimension = Dimension(property["ArrayDim"]);
        if (dimension < 0) throw new ConversionException($"Negative array dimension for {name}");
        StructFieldKind fieldKind;
        int size, alignment;
        StructLayout? structure = null;
        switch (kind) {
            case "doubleproperty": fieldKind = StructFieldKind.Double; size = alignment = 8; break;
            case "floatproperty": fieldKind = StructFieldKind.Float; size = alignment = 4; break;
            case "intproperty": fieldKind = StructFieldKind.Int32; size = alignment = 4; break;
            case "uint32property": fieldKind = StructFieldKind.UInt32; size = alignment = 4; break;
            case "uint16property": fieldKind = StructFieldKind.UInt16; size = alignment = 2; break;
            case "byteproperty": fieldKind = StructFieldKind.Byte; size = alignment = 1; break;
            case "boolproperty":
                fieldKind = StructFieldKind.Bool;
                size = alignment = property.Value<int?>("FieldSize") ?? throw new ConversionException($"Bool field has no FieldSize: {name}");
                if (size is not (1 or 2 or 4 or 8)) throw new ConversionException($"Unsupported native bool field size: {name} ({size})");
                break;
            case "enumproperty":
                fieldKind = StructFieldKind.Enum;
                JObject underlying = property["UnderlyingProp"] as JObject ?? throw new ConversionException($"Enum has no underlying property: {name}");
                string numeric = SourceNameOrder.Canonical(underlying.Value<string>("SerializedType") ?? "");
                size = alignment = numeric switch {
                    "byteproperty" or "int8property" => 1, "int16property" or "uint16property" => 2,
                    "intproperty" or "uint32property" => 4, "int64property" or "uint64property" => 8,
                    _ => throw new ConversionException($"Unsupported enum underlying property: {name} ({numeric})")
                };
                break;
            case "structproperty":
                fieldKind = StructFieldKind.Struct;
                int reference = property.Value<int?>("Struct") ?? throw new ConversionException($"Struct field has no source reference: {name}");
                structure = Find(owner, reference, visiting) ?? throw new ConversionException($"Missing nested struct layout: {owner.ObjectPath(reference)}");
                size = structure.Size; alignment = structure.Alignment;
                break;
            default: throw new ConversionException($"Unsupported property '{name}' in custom struct: {kind}");
        }
        return new(name, fieldKind, 0, size, dimension, alignment, structure);
    }

    private static int Dimension(JToken? value)
    {
        if (value is null) throw new ConversionException("Reflected field has no ArrayDim");
        if (value.Type != JTokenType.String) return value.Value<int>();
        string text = value.Value<string>()!;
        return int.TryParse(text, out int number) ? number : text switch {
            "NotAnArray" => 0, "TArray" => 1, "CArray" => 2,
            _ => throw new ConversionException($"Unknown serialized array dimension {text}")
        };
    }
}
