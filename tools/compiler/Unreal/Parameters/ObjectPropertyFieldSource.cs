using System.Globalization;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Retains compatible source types and the dotted paths of tagged struct fields. */
internal static class ObjectPropertyFieldSource
{
    internal static JObject? Compile(SourceAsset owner, string path, AssetLibrary? library)
    {
        var resolved = Resolve(owner, path, library);
        return resolved is { } value ? CompileObject(value.Asset, value.Object, library) : null;
    }

    internal static JObject? Compile(SourceAsset owner, int reference, AssetLibrary? library)
        => reference > 0 ? CompileObject(owner, owner.Object(reference), library)
            : reference < 0 ? Compile(owner, owner.ObjectPath(reference), library) : null;

    internal static JObject? CompileActorBinding(SourceAsset owner, int reference, AssetLibrary? library)
    {
        if (ResolveReference(owner, reference, library) is not { } value) return null;
        var asset = value.Asset;
        var source = value.Object;
        int type = source.Raw.Value<int>("ClassIndex");
        if (IsOfClass(asset, type, "/Script/Engine.Actor", library)) {
            var actor = CompileActor(asset, asset.ObjectPath(source.Index), "", library);
            return new JObject { ["role"] = "actor", ["root"] = actor?["root_transform"]?.DeepClone() };
        }
        bool scene = IsOfClass(asset, type, "/Script/Engine.SceneComponent", library);
        if (!scene && !IsOfClass(asset, type, "/Script/Engine.ActorComponent", library)) return null;
        var result = new JObject { ["role"] = "component" };
        if (scene) result["scene"] = CompileScene(asset, source, library, new HashSet<string>(StringComparer.OrdinalIgnoreCase));
        var visited = new HashSet<int>();
        for (int outer = source.Outer; outer > 0;) {
            if (!visited.Add(outer)) throw new ConversionException($"Cyclic Niagara component owner: {source.Name}");
            var candidate = asset.Object(outer);
            if (IsOfClass(asset, candidate.Raw.Value<int>("ClassIndex"), "/Script/Engine.Actor", library)) {
                var actor = CompileActor(asset, asset.ObjectPath(outer), "", library);
                result["owner"] = actor?["root_transform"]?.DeepClone();
                break;
            }
            outer = candidate.Outer;
        }
        return result;
    }

    internal static JObject? CompileActor(SourceAsset owner, string path, string componentClass, AssetLibrary? library)
    {
        if (Resolve(owner, path, library) is not { } actor) return null;
        var result = new JObject { ["fields"] = CompileObject(actor.Asset, actor.Object, library) };
        if (actor.Object.Raw["Data"] is JArray data) {
            var root = data.OfType<JObject>().FirstOrDefault(field => field.Value<string>("Name") == "RootComponent");
            if (root?["Value"] is { Type: JTokenType.Integer } reference
                && ResolveReference(actor.Asset, reference.Value<int>(), library) is { } rootComponent) {
                result["root_fields"] = CompileObject(rootComponent.Asset, rootComponent.Object, library);
                result["root_transform"] = CompileScene(rootComponent.Asset, rootComponent.Object, library,
                    new HashSet<string>(StringComparer.OrdinalIgnoreCase));
            }
        }
        if (componentClass.Length != 0) {
            var matching = actor.Asset.Objects.Where(component => actor.Asset.IsWithin(component, actor.Object)
                    && IsOfClass(actor.Asset, component.Raw.Value<int>("ClassIndex"), componentClass, library))
                .Take(2).ToArray();
            if (matching.Length == 1) {
                var component = matching[0];
                result["component_fields"] = CompileObject(actor.Asset, component, library);
                if (IsOfClass(actor.Asset, component.Raw.Value<int>("ClassIndex"), "/Script/Engine.SceneComponent", library))
                    result["component_transform"] = CompileScene(actor.Asset, component, library,
                        new HashSet<string>(StringComparer.OrdinalIgnoreCase));
            }
        }
        return result;
    }

    internal static bool IsOfClass(SourceAsset asset, int reference, string expected, AssetLibrary? library)
    {
        var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        while (reference != 0) {
            string path = asset.ObjectPath(reference);
            if (string.Equals(path, expected, StringComparison.OrdinalIgnoreCase)) return true;
            if (!visited.Add(path)) throw new ConversionException($"Cyclic Niagara property component class: {path}");
            var type = reference > 0 ? (Asset: asset, Object: asset.Object(reference)) : Resolve(asset, path, library);
            if (type is not { } resolved) return false;
            asset = resolved.Asset;
            reference = resolved.Object.Raw.Value<int>("SuperIndex");
        }
        return false;
    }

    private static JObject CompileObject(SourceAsset asset, SourceObject source, AssetLibrary? library)
    {
        var result = new JObject();
        AddObject(asset, source, library, new HashSet<string>(StringComparer.OrdinalIgnoreCase), result);
        return result;
    }

    private static readonly HashSet<string> SceneFields = new(StringComparer.OrdinalIgnoreCase) {
        "RelativeLocation", "RelativeRotation", "RelativeScale3D", "bAbsoluteLocation", "bAbsoluteRotation",
        "bAbsoluteScale", "AttachParent", "AttachSocketName"
    };

    internal static JObject? CompileScene(SourceAsset asset, SourceObject source, AssetLibrary? library)
        => CompileScene(asset, source, library, new HashSet<string>(StringComparer.OrdinalIgnoreCase));

    internal static (SourceAsset Asset, JToken Value)? ComponentProperty(SourceAsset asset, SourceObject source,
                                                                         AssetLibrary library, string name)
    {
        var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        while (visited.Add(asset.ObjectPath(source.Index))) {
            var fields = source.Raw["Data"] as JArray;
            var field = fields?.OfType<JObject>().FirstOrDefault(value => value.Value<string>("Name") == name);
            if (field is not null) {
                JToken value = SourceAsset.Value(field);
                if (value.Type == JTokenType.Null && field.Value<bool?>("IsZero") == true)
                    value = field.Value<string>("$type")?.Contains("BoolPropertyData", StringComparison.Ordinal) == true
                        ? new JValue(false) : new JValue(0);
                return (asset, value);
            }
            if (Archetype(asset, source, library) is not { } parent) return null;
            (asset, source) = parent;
        }
        throw new ConversionException($"Cyclic source component archetype: {asset.ObjectPath(source.Index)}");
    }

    private static JObject? CompileScene(SourceAsset asset, SourceObject source, AssetLibrary? library, HashSet<string> attachments)
    {
        string path = asset.ObjectPath(source.Index);
        if (!attachments.Add(path)) throw new ConversionException($"Cyclic Niagara scene attachment: {path}");
        JObject data = new();
        SourceAsset parentOwner = asset;
        bool inheritedParent = false;
        void Read(SourceAsset current, SourceObject instance, HashSet<string> templates)
        {
            string identity = current.ObjectPath(instance.Index);
            if (!templates.Add(identity)) throw new ConversionException($"Cyclic Niagara scene archetype: {identity}");
            if (Archetype(current, instance, library) is { } template) Read(template.Asset, template.Object, templates);
            if (instance.Raw["Data"] is not JArray fields) return;
            foreach (var field in fields.OfType<JObject>()) {
                if (field.Value<string>("Name") is not { } name || !SceneFields.Contains(name)
                    || (field.Value<int?>("ArrayIndex") ?? 0) != 0) continue;
                if (name == "AttachParent") {
                    parentOwner = current;
                    inheritedParent = current != asset || instance.Index != source.Index;
                }
                bool zero = field.Value<bool?>("IsZero") == true;
                JToken value = zero ? name switch {
                    "RelativeRotation" => new JObject { ["Pitch"] = 0, ["Yaw"] = 0, ["Roll"] = 0 },
                    "RelativeLocation" or "RelativeScale3D" => new JObject { ["X"] = 0, ["Y"] = 0, ["Z"] = 0 },
                    _ => new JValue(0)
                } : SourceAsset.Value(field);
                if (value is JObject channels && data[name] is JObject prior)
                    prior.Merge(channels);
                else data[name] = value;
            }
        }
        Read(asset, source, new HashSet<string>(StringComparer.OrdinalIgnoreCase));
        var result = new JObject {
            ["location"] = SceneVector(data["RelativeLocation"], 0, "X", "Y", "Z"),
            ["rotation"] = SceneVector(data["RelativeRotation"], 0, "Pitch", "Yaw", "Roll"),
            ["scale"] = SceneVector(data["RelativeScale3D"], 1, "X", "Y", "Z"),
            ["absolute_location"] = SceneFlag(data["bAbsoluteLocation"]),
            ["absolute_rotation"] = SceneFlag(data["bAbsoluteRotation"]),
            ["absolute_scale"] = SceneFlag(data["bAbsoluteScale"])
        };
        int parentRef = data.Value<int?>("AttachParent") ?? 0;
        if (parentRef == 0 || result.Value<bool>("absolute_location") && result.Value<bool>("absolute_rotation")
                && result.Value<bool>("absolute_scale")) return result;
        string? socket = data.Value<string>("AttachSocketName");
        if (inheritedParent || !string.IsNullOrEmpty(socket) && socket != "None"
                || ResolveReference(parentOwner, parentRef, library) is not { } parent) return null;
        if (CompileScene(parent.Asset, parent.Object, library, attachments) is not { } transform) return null;
        result["parent"] = transform;
        return result;
    }

    private static JArray SceneVector(JToken? value, double fallback, string x, string y, string z)
    {
        if (value is JObject vector)
            return new JArray(SourceAsset.Number(vector[x], fallback), SourceAsset.Number(vector[y], fallback),
                SourceAsset.Number(vector[z], fallback));
        return value?.Type == JTokenType.Integer && value.Value<int>() == 0
            ? new JArray(0, 0, 0) : new JArray(fallback, fallback, fallback);
    }

    private static bool SceneFlag(JToken? value)
        => value?.Type == JTokenType.Boolean ? value.Value<bool>() : value?.Type == JTokenType.Integer && value.Value<int>() != 0;

    internal static (SourceAsset Asset, SourceObject Object)? ResolveReference(SourceAsset owner, int reference, AssetLibrary? library)
        => reference > 0 ? (owner, owner.Object(reference))
            : reference < 0 ? Resolve(owner, owner.ObjectPath(reference), library) : null;

    internal static (SourceAsset Asset, SourceObject Object)? Resolve(SourceAsset owner, string path, AssetLibrary? library)
    {
        if (path.Length == 0 || path.StartsWith("/Script/", StringComparison.OrdinalIgnoreCase)) return null;
        string package = path.Split('.')[0];
        bool external = package.StartsWith("/Game/", StringComparison.Ordinal)
            || package.StartsWith("/Niagara/", StringComparison.Ordinal)
            || package.StartsWith("/Engine/", StringComparison.Ordinal);
        var asset = string.Equals(package, owner.Name, StringComparison.OrdinalIgnoreCase) ? owner
            : external ? (library ?? owner.Library)?.TryLoad(path) : null;
        if (asset is null) return null;
        string canonical = path.Replace(':', '.');
        var matches = asset.Objects.Where(value => string.Equals(asset.ObjectPath(value.Index), canonical, StringComparison.OrdinalIgnoreCase))
            .Take(2).ToArray();
        if (matches.Length > 1) throw new ConversionException($"Ambiguous Niagara property source: {path}");
        return matches.Length == 0 ? null : (asset, matches[0]);
    }

    private static (SourceAsset Asset, SourceObject Object)? Archetype(SourceAsset asset, SourceObject source, AssetLibrary? library)
    {
        int template = source.Raw.Value<int>("TemplateIndex");
        int classIndex = source.Raw.Value<int>("ClassIndex");
        if (template == 0 && classIndex > 0) {
            int defaultObject = asset.Object(classIndex).Raw.Value<int>("ClassDefaultObject");
            if (defaultObject != source.Index) template = defaultObject;
        }
        return ResolveReference(asset, template, library);
    }

    private static void AddObject(SourceAsset asset, SourceObject source, AssetLibrary? library, HashSet<string> visited, JObject result)
    {
        string path = asset.ObjectPath(source.Index);
        if (!visited.Add(path)) throw new ConversionException($"Cyclic Niagara property source archetype: {path}");
        if (Archetype(asset, source, library) is { } parent)
            AddObject(parent.Asset, parent.Object, library, visited, result);
        if (source.Raw["Data"] is JArray fields) AddFields(asset, library, fields, "", result);
    }

    private static void AddFields(SourceAsset asset, AssetLibrary? library, JArray fields, string prefix, JObject result)
    {
        foreach (var field in fields.OfType<JObject>()) {
            if ((field.Value<int?>("ArrayIndex") ?? 0) != 0 || field.Value<string>("Name") is not string name) continue;
            string path = prefix + name;
            string type = (field.Value<string>("$type") ?? "").Split(',')[0].Split('.').Last();
            if (type == "StructPropertyData") {
                AddStruct(asset, library, field, path, result);
                continue;
            }
            if (type == "EnumPropertyData" || type == "BytePropertyData" && field.Value<string>("ByteType") == "FName") {
                if (ReadEnum(asset, library, field, type == "BytePropertyData") is int integer)
                    Add(result, path, "INT", integer);
                continue;
            }
            var value = field["Value"];
            if ((value is null || value.Type == JTokenType.Null) && field.Value<bool?>("IsZero") == true)
                value = new JValue(0);
            if (value is null || value.Type == JTokenType.Null) continue;
            switch (type) {
                case "FloatPropertyData":
                case "DoublePropertyData": Add(result, path, "FLOAT", Number(value)); break;
                case "BoolPropertyData": Add(result, path, "BOOL", value.Value<bool>() ? 1 : 0); break;
                case "Int16PropertyData":
                case "IntPropertyData":
                case "UInt16PropertyData": Add(result, path, "INT", value.Value<int>()); break;
                case "UInt32PropertyData": Add(result, path, "INT", unchecked((int)value.Value<uint>())); break;
                case "BytePropertyData" when value.Type == JTokenType.Integer:
                    Add(result, path, "INT", value.Value<byte>()); break;
            }
        }
    }

    private static int? ReadEnum(SourceAsset asset, AssetLibrary? library, JObject field, bool byteProperty)
    {
        var nodes = field["PropertyTypeName"] as JArray;
        string? enumName = field.Value<string>("EnumType");
        string? package = null;
        string? underlying = byteProperty ? "ByteProperty" : field.Value<string>("InnerType");
        if (nodes is { Count: > 1 } && nodes[1] is JObject enumNode) {
            enumName ??= enumNode.Value<string>("Name");
            int children = enumNode.Value<int>("InnerCount");
            if (children > 0 && nodes.Count > 2) package = nodes[2]?.Value<string>("Name");
            int underlyingIndex = 2 + children;
            if (!byteProperty && underlyingIndex < nodes.Count)
                underlying ??= nodes[underlyingIndex]?.Value<string>("Name");
        }
        if (underlying is not ("ByteProperty" or "UInt16Property" or "UInt32Property" or "Int16Property" or "IntProperty"))
            return null;
        var value = field[byteProperty ? "EnumValue" : "Value"];
        long raw;
        if (field.Value<bool?>("IsZero") == true) raw = 0;
        else if (value is { Type: JTokenType.Integer }) raw = value.Value<long>();
        else if (value is { Type: JTokenType.String } && enumName is not null) {
            string symbol = value.Value<string>()!;
            const string invalidIndex = "UASSETAPI_INVALID_ENUM_IDX_";
            if (!symbol.StartsWith(invalidIndex, StringComparison.Ordinal)
                || !long.TryParse(symbol.AsSpan(invalidIndex.Length), NumberStyles.Integer, CultureInfo.InvariantCulture, out raw)) {
                JArray? names = package is null
                    ? asset.Objects.SingleOrDefault(candidate => candidate.Raw["Enum"] is JObject
                        && string.Equals(candidate.Name, enumName, StringComparison.OrdinalIgnoreCase))?.Raw["Enum"]?["Names"] as JArray
                    : Resolve(asset, package + "." + enumName, library)?.Object.Raw["Enum"]?["Names"] as JArray;
                var entry = names?.OfType<JObject>().FirstOrDefault(candidate =>
                    string.Equals(candidate.Value<string>("Item1"), symbol, StringComparison.OrdinalIgnoreCase));
                if (entry?["Item2"] is not { Type: JTokenType.Integer } number) return null;
                raw = number.Value<long>();
            }
        } else return null;
        return underlying switch {
            "ByteProperty" when raw is >= byte.MinValue and <= byte.MaxValue => (int)raw,
            "UInt16Property" when raw is >= ushort.MinValue and <= ushort.MaxValue => (int)raw,
            "UInt32Property" when raw is >= uint.MinValue and <= uint.MaxValue => unchecked((int)raw),
            "Int16Property" when raw is >= short.MinValue and <= short.MaxValue => (int)raw,
            "IntProperty" when raw is >= int.MinValue and <= int.MaxValue => (int)raw,
            _ => null
        };
    }

    private static void AddStruct(SourceAsset asset, AssetLibrary? library, JObject field, string path, JObject result)
    {
        var layout = field.Value<string>("StructType") switch {
            "Vector2D" => ("VEC2", "XY"),
            "Vector" => ("POSITION", "XYZ"),
            "Vector4" => ("VEC4", "XYZW"),
            "LinearColor" => ("COLOR", "RGBA"),
            "Color" => ("COLOR_SRGB8", "RGBA"),
            _ => ("", "") };
        var packed = SourceAsset.Value(field) as JObject;
        bool zero = field.Value<bool?>("IsZero") == true;
        if (layout.Item2.Length != 0 && (zero || packed is not null && layout.Item2.All(channel => packed[channel.ToString()] is not null))) {
            var components = new List<JToken>(layout.Item2.Length);
            foreach (char channel in layout.Item2) {
                string component = channel.ToString();
                JToken value = zero ? new JValue(0) : Number(packed![component]!);
                components.Add(value);
                Add(result, path + "." + component, layout.Item1 == "COLOR_SRGB8" ? "INT" : "FLOAT", value.DeepClone());
            }
            Add(result, path, layout.Item1, components.ToArray());
        } else if (field["Value"] is JArray children) AddFields(asset, library, children, path + ".", result);
    }

    private static JToken Number(JToken value)
    {
        if (value.Type is JTokenType.Integer or JTokenType.Float) return value.DeepClone();
        if (value.Type == JTokenType.String && double.TryParse(value.Value<string>(), NumberStyles.Float,
                CultureInfo.InvariantCulture, out _)) return value.DeepClone();
        throw new ConversionException("Invalid saved numeric UObject field");
    }

    private static void Add(JObject result, string name, string type, params JToken[] components)
    {
        var value = new JObject { ["type"] = type, ["components"] = new JArray(components) };
        var existing = result.Property(name, StringComparison.OrdinalIgnoreCase);
        if (existing is null) result.Add(name, value);
        else existing.Value = value;
    }
}
