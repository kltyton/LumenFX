using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed class ConversionException(string message) : Exception(message);

public sealed record SourceObject(int Index, string Name, string ClassName, int Outer, JObject Raw,
    IReadOnlyList<string>? NameMap = null)
{
    private JObject? decodedRawProperties;
    internal RawTaggedPropertyDecoder.Format TaggedPropertyFormat { get; init; }

    public JObject Properties
    {
        get
        {
            if (Raw["Data"] is JArray data) return SourceAsset.Properties(data);
            if (decodedRawProperties is not null) return (JObject)decodedRawProperties.DeepClone();
            if (Raw["Data"] is JValue { Type: JTokenType.String } raw
                && RawTaggedPropertyDecoder.TryDecode(raw.Value<string>()!, NameMap, TaggedPropertyFormat, out JObject? decoded))
            {
                decodedRawProperties = decoded;
                return (JObject)decoded!.DeepClone();
            }
            throw new ConversionException($"Native export {Index} {Name} ({ClassName}) was not decoded");
        }
    }
}

public sealed class SourceAsset
{
    public string Name { get; }
    public JObject Raw { get; }
    public IReadOnlyList<SourceObject> Objects { get; }
    internal AssetLibrary? Library { get; }
    private readonly IReadOnlyList<JObject> imports;

    public SourceAsset(string name, JObject raw) : this(name, raw, null) { }

    internal SourceAsset(string name, JObject raw, AssetLibrary? library)
    {
        Name = name;
        Raw = raw;
        Library = library;
        imports = (raw["Imports"] as JArray ?? throw new ConversionException($"Missing imports in {name}"))
            .Select(value => (JObject)value).ToArray();
        var nameMap = (raw["NameMap"] as JArray)?.Values<string>().Select(value => value ?? string.Empty).ToArray();
        var taggedPropertyFormat = RawTaggedPropertyDecoder.PackageFormat(raw);
        var exports = raw["Exports"] as JArray ?? throw new ConversionException($"Missing exports in {name}");
        var result = new List<SourceObject>();
        for (int i = 0; i < exports.Count; i++)
        {
            var value = (JObject)exports[i];
            int classIndex = value.Value<int>("ClassIndex");
            string type = classIndex < 0 ? imports[-classIndex - 1].Value<string>("ObjectName")!
                : ((JObject)exports[classIndex - 1]).Value<string>("ObjectName")!;
            result.Add(new SourceObject(i + 1, value.Value<string>("ObjectName")!, type, value.Value<int>("OuterIndex"), value, nameMap) {
                TaggedPropertyFormat = taggedPropertyFormat
            });
        }
        Objects = result;
    }

    public SourceObject Object(int index) => index > 0 && index <= Objects.Count ? Objects[index - 1]
        : throw new ConversionException($"Invalid export {index} in {Name}");

    public string Reference(int index)
    {
        if (index == 0) return "None";
        if (index > 0) return Object(index).Name;
        if (-index > imports.Count) throw new ConversionException($"Invalid import {index} in {Name}");
        var value = imports[-index - 1];
        string name = value.Value<string>("ObjectName")!;
        int outer = value.Value<int>("OuterIndex");
        return outer == 0 ? name : Reference(outer) + "." + name;
    }
    internal string ReferencedClass(int index)
    {
        if (index > 0) return Object(index).ClassName;
        if (index == 0) return "None";
        if (-index > imports.Count) throw new ConversionException($"Invalid import {index} in {Name}");
        return imports[-index - 1].Value<string>("ClassName") ?? throw new ConversionException($"Import has no class: {Reference(index)}");
    }
    internal string? ReferencedClassPath(int index)
    {
        if (index > 0) return ObjectPath(Object(index).Raw.Value<int>("ClassIndex"));
        if (index == 0) return null;
        if (-index > imports.Count) throw new ConversionException($"Invalid import {index} in {Name}");
        JObject source = imports[-index - 1];
        string? package = source.Value<string>("ClassPackage"), name = source.Value<string>("ClassName");
        return package is null || name is null ? null : package + "." + name;
    }

    public string ObjectPath(int index)
    {
        if (index <= 0) return Reference(index);
        List<string> names = new();
        HashSet<int> visited = new();
        while (index > 0) {
            if (!visited.Add(index)) throw new ConversionException($"Cyclic export outer chain in {Name}");
            SourceObject value = Object(index);
            names.Add(value.Name); index = value.Outer;
        }
        names.Reverse();
        return (index == 0 ? Name : Reference(index)) + "." + string.Join('.', names);
    }

    public SourceObject ResolveObject(string path, Func<SourceObject, bool> allowed)
    {
        string canonical = path.Replace(':', '.');
        bool selectsPackage = SourceNameOrder.Equal(canonical, Name);
        var matches = Objects.Where(allowed)
            .Where(value => selectsPackage || SourceNameOrder.Equal(ObjectPath(value.Index), canonical))
            .Take(2).ToArray();
        if (matches.Length != 1) throw new ConversionException($"Expected one target export for {path}; found {matches.Length}");
        return matches[0];
    }

    public bool IsWithin(SourceObject value, SourceObject owner)
    {
        int outer = value.Outer;
        HashSet<int> visited = new();
        while (outer > 0) {
            if (outer == owner.Index) return true;
            if (!visited.Add(outer)) throw new ConversionException($"Cyclic export outer chain in {Name}");
            outer = Object(outer).Outer;
        }
        return false;
    }

    internal JObject NativeProperties(SourceObject source, out IReadOnlyList<string> objectPaths, params string[] names)
    {
        if (source.Raw["Data"] is not JArray data) throw new ConversionException($"Native export {source.Name} was not decoded");
        var selected = names.ToHashSet(StringComparer.Ordinal);
        JArray fields = new(data.OfType<JObject>().Where(value => selected.Contains(value.Value<string>("Name") ?? ""))
            .Select(value => value.DeepClone()));
        HashSet<string> paths = new(StringComparer.Ordinal);
        void Visit(JToken token) {
            if (token is JObject property) {
                string type = property.Value<string>("$type") ?? "";
                if (type.Contains("ObjectPropertyData", StringComparison.Ordinal) && property["Value"] is { Type: JTokenType.Integer } reference) {
                    string path = ObjectPath(reference.Value<int>());
                    property["Value"] = path;
                    if (path != "None") paths.Add(path);
                }
                foreach (var child in property.Properties().ToArray()) Visit(child.Value);
            } else if (token is JArray array) foreach (JToken child in array) Visit(child);
        }
        Visit(fields);
        objectPaths = paths.OrderBy(value => value, StringComparer.Ordinal).ToArray();
        return Properties(fields);
    }

    public static JObject Properties(JArray data)
    {
        JObject result = new();
        foreach (var group in data.Cast<JObject>().GroupBy(property => property.Value<string>("Name")!))
        {
            var values = group.OrderBy(property => property.Value<int>("ArrayIndex")).ToArray();
            result[group.Key] = values.Length == 1 ? Value(values[0]) : new JArray(values.Select(Value));
        }
        return result;
    }

    public static JToken Value(JToken token)
    {
        if (token is JArray array) return new JArray(array.Select(Value));
        if (token is not JObject value) return token.DeepClone();
        string type = value.Value<string>("$type") ?? "";
        if (!type.Contains("PropertyTypes", StringComparison.Ordinal)
                || !type.Contains("PropertyData", StringComparison.Ordinal))
            return new JObject(value.Properties().Where(property => property.Name != "$type")
                .Select(property => new JProperty(property.Name, Value(property.Value))));
        if (type.Contains("TextPropertyData", StringComparison.Ordinal))
            return new JObject { ["unreal_text"] = new JObject(value.Properties()
                .Where(property => property.Name != "$type")
                .Select(property => new JProperty(property.Name, property.Value.DeepClone()))) };
        if (type.Contains("InstancedStructPropertyData", StringComparison.Ordinal))
            return new JObject { ["unreal_struct"] = new JObject {
                ["type"] = value["Struct"]?.DeepClone()
                    ?? throw new ConversionException("Instanced struct has no script struct reference"),
                ["value"] = value["Value"] is JArray instFields
                    ? Properties(instFields)
                    : Value(value["Value"] ?? JValue.CreateNull())
            } };
        if (type.Contains(".RawStructPropertyData,", StringComparison.Ordinal)
                && value.Value<string>("StructType") == "Rotator3f"
                && value["Value"] is JValue { Type: JTokenType.String } nativeRotator)
            return RawTaggedPropertyDecoder.DecodeRotator3f(Convert.FromBase64String(nativeRotator.Value<string>()!));
        if (type.Contains(".IntPointPropertyData,", StringComparison.Ordinal) && value["Value"] is JArray point)
        {
            if (point.Count != 2 || point.Any(component => component.Type != JTokenType.Integer))
                throw new ConversionException("Unreal IntPoint must contain two integer coordinates");
            return new JObject { ["X"] = point[0].DeepClone(), ["Y"] = point[1].DeepClone() };
        }
        if (type.Contains("PerQualityLevel", StringComparison.Ordinal) && value["Value"] is JObject quality)
            return new JObject { ["default"] = Value(quality["Default"] ?? throw new ConversionException("Per-quality value has no default")),
                ["overrides"] = Value(quality["PerQuality"] ?? new JObject()), ["cooked"] = quality.Value<bool>("bCooked") };
        if (type.Contains("PerPlatform", StringComparison.Ordinal) && value["Platforms"] is JArray platforms
                && value["Value"] is JArray platformValues)
        {
            if (platformValues.Count != platforms.Count + 1)
                throw new ConversionException("Per-platform default and named overrides disagree");
            JObject overrides = new();
            for (int i = 0; i < platforms.Count; i++)
                overrides.Add(platforms[i].Value<string>()
                    ?? throw new ConversionException("Missing platform override name"), Value(platformValues[i + 1]));
            return new JObject { ["default"] = Value(platformValues[0]), ["overrides"] = overrides,
                ["cooked"] = value.Value<bool>("IsCooked") };
        }
        if (value["EnumValue"] is { Type: not JTokenType.Null } enumValue) return enumValue.DeepClone();
        if (value["VariableName"] is { } variableName)
            return new JObject { ["name"] = variableName.DeepClone(), ["offset"] = value["VariableOffset"]?.DeepClone(),
                ["type"] = value["TypeDef"] is { } definition ? Value(definition) : JValue.CreateNull(),
                ["data"] = value["VarData"]?.DeepClone() };
        if (value["Expression"] is not null)
            return new JObject { ["expression"] = value["Expression"]!.DeepClone(), ["output"] = value["OutputIndex"]!.DeepClone(),
                ["mask"] = value["Mask"]!.DeepClone(), ["r"] = value["MaskR"]!.DeepClone(), ["g"] = value["MaskG"]!.DeepClone(),
                ["b"] = value["MaskB"]!.DeepClone(), ["a"] = value["MaskA"]!.DeepClone(),
                ["constant"] = value["UseConstantValue"]?.DeepClone(),
                ["value"] = Value(value["ValueLinear"] is { Type: not JTokenType.Null } linear ? linear : value["Value"] ?? JValue.CreateNull()) };
        if (type.Contains("StructPropertyData", StringComparison.Ordinal) && value["Value"] is JArray fields)
        {
            if (fields.Count == 1 && fields[0] is JObject only && only.Value<string>("Name") == value.Value<string>("Name")) return Value(only);
            return Properties(fields);
        }
        return Value(value["Value"] ?? JValue.CreateNull());
    }

    public static double Number(JToken? value, double defaultValue = 0)
    {
        if (value is null || value.Type == JTokenType.Null) return defaultValue;
        double number = value.Type == JTokenType.String
            ? double.Parse(value.Value<string>()!, System.Globalization.CultureInfo.InvariantCulture)
            : value.Value<double>();
        if (!double.IsFinite(number)) throw new ConversionException("Non-finite source value");
        return number;
    }
}
