using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private static LayerStackSource NormalizeLayerSource(SourceAsset asset, JObject runtimeSource, JObject editorSource)
    {
        JObject runtime = (JObject)runtimeSource.DeepClone();
        JObject editor = (JObject)editorSource.DeepClone();
        foreach (string field in new[] { "Layers", "Blends" }) {
            if (runtime[field] is not { } value || value.Type == JTokenType.Null) { runtime[field] = new JArray(); continue; }
            if (value is not JArray references) throw new ConversionException($"Material layer {field} are not an array: {asset.Name}");
            runtime[field] = new JArray(references.Select(reference => reference.Type switch {
                JTokenType.Integer => reference.Value<int>() == 0 ? JValue.CreateNull() : new JValue(asset.ObjectPath(reference.Value<int>())),
                JTokenType.Null or JTokenType.String => reference.DeepClone(),
                _ => throw new ConversionException($"Invalid material layer function reference: {asset.Name}:{field}")
            }));
        }
        foreach (string field in new[] { "LayerStates", "LayerNames", "RestrictToLayerRelatives",
                "RestrictToBlendRelatives", "LayerGuids", "LayerLinkStates", "DeletedParentLayerGuids" }) {
            // PostSerialize moves the nonempty legacy arrays into EditorOnly before resolving the parent.
            JToken? legacy = runtime[field + "_DEPRECATED"] ?? runtime[field];
            if (legacy is not null && legacy.Type != JTokenType.Null && legacy is not JArray)
                throw new ConversionException($"Legacy material layer {field} are not an array: {asset.Name}");
            if (legacy is JArray { Count: > 0 } values) editor[field] = values.DeepClone();
        }
        int count = (runtime["Layers"] as JArray)?.Count ?? 0;
        if ((editor["LayerGuids"] as JArray)?.Count != count || (editor["LayerLinkStates"] as JArray)?.Count != count) {
            // UAssetAPI serializes FGuid as Unreal's four words, including the final word in big-endian text order.
            editor["LayerGuids"] = new JArray(Enumerable.Range(0, count).Select(index => index == 0
                ? "00000002-0000-0000-0000-000000000000" : $"00000003-0000-0000-0000-0000{index:x8}"));
            editor["LayerLinkStates"] = new JArray(Enumerable.Repeat("Uninitialized", count));
        }
        return new LayerStackSource(asset, runtime, editor);
    }
}
