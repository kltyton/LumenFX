using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record StaticParameterSetSource(JObject Runtime, JObject Editor);

    private static StaticParameterSetSource InstanceStaticParameters(SourceAsset asset, SourceObject instance)
    {
        JObject properties = instance.Properties;
        JObject runtime = ReadObject(properties["StaticParametersRuntime"], "runtime static parameters");
        int editorReference = properties.Value<int?>("EditorOnlyData") ?? 0;
        JObject editorProperties = editorReference > 0 ? asset.Object(editorReference).Properties : properties;
        JObject editor = ReadObject(editorProperties["StaticParameters"], "editor static parameters");
        JToken? saved = properties["StaticParameters_DEPRECATED"] ?? properties["StaticParameters"];
        if (saved is { Type: not JTokenType.Null }) {
            JObject legacy = ReadObject(saved, "legacy static parameters");
            JObject legacyEditor = ReadObject(legacy["EditorOnly"], "legacy editor static parameters");
            MoveNonempty(legacyEditor, "StaticSwitchParameters", legacy, "StaticSwitchParameters");
            if (legacy["StaticSwitchParameters_DEPRECATED"] is JArray { Count: > 0 } switches)
                legacyEditor["StaticSwitchParameters_DEPRECATED"] = switches.DeepClone();
            MoveNonempty(legacyEditor, "StaticSwitchParameters_DEPRECATED", legacy, "StaticSwitchParameters");
            MoveNonempty(legacy, "StaticComponentMaskParameters", legacyEditor, "StaticComponentMaskParameters");
            MoveNonempty(legacy, "StaticComponentMaskParameters_DEPRECATED", legacyEditor, "StaticComponentMaskParameters");
            MoveNonempty(legacy, "TerrainLayerWeightParameters", legacyEditor, "TerrainLayerWeightParameters");
            MoveNonempty(legacy, "TerrainLayerWeightParameters_DEPRECATED", legacyEditor, "TerrainLayerWeightParameters");
            if (legacy["MaterialLayers"] is JObject embedded && embedded["EditorOnly"] is JObject embeddedEditor)
                legacyEditor["MaterialLayers"] = embeddedEditor.DeepClone();

            const string releaseVersion = "{D89B5E42-24BD-4D46-8412-ACA8DF641779}";
            int version = (asset.Raw["CustomVersionContainer"] as JArray)?.OfType<JObject>()
                .SingleOrDefault(value => string.Equals(value.Value<string>("Key"), releaseVersion, StringComparison.OrdinalIgnoreCase))
                ?.Value<int>("Version") ?? -1;
            // MaterialLayerStacksAreNotParameters = 13. The native migration takes the first entry regardless of bOverride.
            if (version < 13 && (legacy["MaterialLayersParameters_DEPRECATED"] ?? legacy["MaterialLayersParameters"])
                    is JArray { Count: > 0 } layers) {
                JObject stack = layers[0]["Value"] as JObject
                    ?? throw new ConversionException($"Legacy material layer parameter has no stack: {instance.Name}");
                legacy["bHasMaterialLayers"] = true;
                legacy["MaterialLayers"] = stack.DeepClone();
                legacyEditor["MaterialLayers"] = stack["EditorOnly"]?.DeepClone() ?? new JObject();
            }
            bool empty = ((legacy["StaticSwitchParameters"] as JArray)?.Count ?? 0) == 0
                && legacy.Value<bool?>("bHasMaterialLayers") != true
                && ((legacyEditor["StaticComponentMaskParameters"] as JArray)?.Count ?? 0) == 0
                && ((legacyEditor["TerrainLayerWeightParameters"] as JArray)?.Count ?? 0) == 0;
            if (!empty) { runtime = legacy; editor = legacyEditor; }
        }
        if (runtime["MaterialLayers"] is JObject materialLayers && materialLayers["EditorOnly"] is JObject materialLayerEditor)
            editor["MaterialLayers"] = materialLayerEditor.DeepClone();
        // MaterialInstance::PostLoad moves the remaining editor-only switches even after tagged serialization.
        MoveNonempty(editor, "StaticSwitchParameters", runtime, "StaticSwitchParameters");
        MoveNonempty(editor, "StaticSwitchParameters_DEPRECATED", runtime, "StaticSwitchParameters");
        return new StaticParameterSetSource(runtime, editor);

        JObject ReadObject(JToken? value, string field)
        {
            if (value is null || value.Type == JTokenType.Null) return new JObject();
            return value is JObject data ? (JObject)data.DeepClone()
                : throw new ConversionException($"Material instance {field} were not decoded: {instance.Name}");
        }

        void MoveNonempty(JObject source, string field, JObject target, string destination)
        {
            if (source[field] is not { } value || value.Type == JTokenType.Null) return;
            if (value is not JArray values)
                throw new ConversionException($"Static material parameter field is not an array: {instance.Name}:{field}");
            if (values.Count > 0) target[destination] = values.DeepClone();
        }
    }
}
