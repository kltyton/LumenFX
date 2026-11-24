using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Types;

/** Converts legacy PropertyExport references before appending the already serialized FProperty fields. */
internal static class StructFieldSource
{
    internal static IReadOnlyList<JObject>? Read(SourceAsset asset, SourceObject structure)
    {
        JArray? modern = structure.Raw["LoadedProperties"] as JArray;
        JArray? children = structure.Raw["Children"] as JArray;
        if (modern is null && children is null) return null;
        List<JObject> result = new();
        HashSet<int> visited = new();
        foreach (int first in (children ?? new()).Values<int>()) {
            int index = first;
            HashSet<int> chain = new();
            while (index != 0) {
                if (!chain.Add(index)) throw new ConversionException($"Cyclic legacy field chain in {asset.ObjectPath(structure.Index)}");
                if (!visited.Add(index)) break;
                SourceObject child = asset.Object(index);
                JObject? property = child.Raw["Property"] as JObject;
                if (property is not null) result.Add(Normalize(asset, child, new HashSet<int>()));
                index = property?.Value<int?>("Next") ?? child.Raw["Field"]?.Value<int?>("Next") ?? 0;
            }
        }
        if (modern is not null) result.AddRange(modern.OfType<JObject>());
        return result.AsReadOnly();
    }

    private static JObject Normalize(SourceAsset asset, SourceObject source, HashSet<int> visiting)
    {
        if (!visiting.Add(source.Index)) throw new ConversionException($"Cyclic legacy property definition: {asset.ObjectPath(source.Index)}");
        try {
            JObject property = source.Raw["Property"] as JObject
                ?? throw new ConversionException($"Legacy field has no property payload: {asset.ObjectPath(source.Index)}");
            JObject result = (JObject)property.DeepClone();
            result["Name"] = source.Name;
            result["SerializedType"] = source.ClassName;
            if (source.ClassName == "BoolProperty") {
                result["FieldSize"] = property.Value<int?>("ElementSize")
                    ?? throw new ConversionException($"Legacy bool field has no element size: {source.Name}");
            }
            if (source.ClassName == "EnumProperty") {
                int underlying = property.Value<int?>("UnderlyingProp") ?? 0;
                if (underlying == 0) throw new ConversionException($"Legacy enum field has no underlying property: {source.Name}");
                result["UnderlyingProp"] = Normalize(asset, asset.Object(underlying), visiting);
            }
            return result;
        } finally { visiting.Remove(source.Index); }
    }
}
