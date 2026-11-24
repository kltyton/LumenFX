using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class ObjectPropertyReaderCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceUObjectPropertyReader";

    internal static JObject Definition(SourceAsset asset, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(asset, reference, ClassName, library);
        JObject properties = source?.Object?.Properties ?? new();
        SourceAsset owner = source?.Asset ?? asset;
        string mode = properties["SourceMode"] switch {
            null => "Binding",
            { Type: JTokenType.Integer } value => value.Value<int>() switch {
                0 => "Binding", 1 => "AttachParentActor", 2 => "BindingThenAttachParentActor",
                3 => "AttachParent", 4 => "BindingThenAttachParent",
                _ => throw new ConversionException("Unknown Niagara object reader source mode") },
            var value => value.Value<string>()?.Split("::").Last()
                ?? throw new ConversionException("Invalid Niagara object reader source mode") };
        if (mode is not ("Binding" or "AttachParentActor" or "BindingThenAttachParentActor" or "AttachParent" or "BindingThenAttachParent"))
            throw new ConversionException($"Unknown Niagara object reader source mode: {mode}");
        var remaps = new JArray();
        foreach (var item in (properties["PropertyRemap"] as JArray ?? new()).OfType<JObject>())
            remaps.Add(new JObject { ["graph"] = item.Value<string>("GraphName") ?? "None",
                ["property"] = item.Value<string>("RemapName") ?? "None" });
        var result = new JObject {
            ["mode"] = mode,
            ["binding"] = properties["UObjectParameterBinding"]?["Parameter"]?.Value<string>("name") ?? "",
            ["source_actor"] = ObjectPath(owner, properties["SourceActor"]),
            ["component_class"] = ObjectPath(owner, properties["SourceActorComponentClass"]),
            ["remaps"] = remaps
        };
        if (ObjectPropertyFieldSource.CompileActor(owner, result.Value<string>("source_actor")!,
                result.Value<string>("component_class")!, library) is { } actor)
            result["source_actor_data"] = actor;
        return result;
    }

    internal static string ObjectPath(SourceAsset asset, JToken? value)
    {
        if (value is null || value.Type == JTokenType.Null) return "";
        if (value.Type == JTokenType.Integer) {
            int reference = value.Value<int>();
            return reference == 0 ? "" : asset.ObjectPath(reference);
        }
        string? path = value.Type == JTokenType.String ? value.Value<string>()
            : (value as JObject)?.Value<string>("AssetPathName");
        if (path is null && value is JObject wrapper && wrapper["AssetPath"] is JObject assetPath) {
            string? package = assetPath.Value<string>("PackageName"), name = assetPath.Value<string>("AssetName");
            if (package is not null && name is not null) path = package == "None" ? "" : package + "." + name;
        }
        if (path is null) throw new ConversionException("Invalid Niagara object reader source reference");
        if (path is "" or "None") return "";
        string? subPath = value is JObject soft ? soft.Value<string>("SubPathString") : null;
        return string.IsNullOrEmpty(subPath) ? path : path + ":" + subPath;
    }

    internal static JObject Function(JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        int width = Width(name);
        if (inputs != 0 || outputs != width || width < 0) throw new ConversionException($"Invalid Niagara object reader function: {name} ({inputs}, {outputs})");
        return FunctionData(name, function["FunctionSpecifiers"], width);
    }

    internal static JArray GpuFunctions(JObject shaderInterface)
    {
        var result = new JArray();
        foreach (var item in shaderInterface["GeneratedFunctions"] as JArray ?? new JArray()) {
            if (item is not JObject function || function.Value<string>("DefinitionName") is not string name || Width(name) < 0)
                throw new ConversionException("Invalid generated Niagara object reader function");
            var entry = FunctionData(name, function["Specifiers"], Width(name));
            entry["name"] = name;
            result.Add(entry);
        }
        return result;
    }

    private static int Width(string name) => name switch {
            "GetFloatProperty" or "GetIntProperty" or "GetBoolProperty" => 2,
            "GetVec2Property" => 3, "GetVec3Property" or "GetPositionProperty" => 4,
            "GetVec4Property" or "GetColorProperty" => 5,
            "GetComponentTransform" or "GetComponentInverseTransform" => 11, _ => -1 };

    private static JObject FunctionData(string name, JToken? specifiers, int width)
    {
        if (width == 11) return new JObject();
        if (specifiers is not JArray specs || specs.Count != 1 || specs[0]?.Value<string>("Value") is not string property)
            throw new ConversionException($"Niagara object reader function has no property name: {name}");
        return new JObject { ["property"] = property };
    }
}
