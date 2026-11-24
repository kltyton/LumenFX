using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class SocketReaderInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceSocketReader";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties ?? new JObject();
        var asset = source?.Asset ?? owner;
        string[] modes = ["Default", "ParameterBindingOnly", "AttachedParentOnly", "SourceOnly"];
        var mode = (properties.Value<string>("SourceMode") ?? "Default").Split("::").Last();
        if (int.TryParse(mode, out int index) && index >= 0 && index < modes.Length) mode = modes[index];
        if (!modes.Contains(mode, StringComparer.Ordinal))
            throw new ConversionException($"Unknown Niagara Socket Reader source mode {mode}");
        string actor = NiagaraActorReference.Read(asset, properties);
        var data = new JObject {
            ["mode"] = mode,
            ["parameter"] = properties["ObjectParameterBinding"]?["Parameter"]?.Value<string>("name") ?? "",
            ["source_actor"] = actor,
            ["attachment_class"] = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["AttachComponentClass"]),
            ["attachment_tag"] = properties.Value<string>("AttachComponentTag") ?? "",
            ["filtered"] = properties["FilteredSockets"]?.DeepClone() ?? new JArray(),
            ["update_sockets"] = properties.Value<bool?>("bUpdateSocketsPerFrame") ?? true,
            ["require_current"] = properties.Value<bool?>("bRequireCurrentFrameData") ?? true
        };
        int mesh = properties.Value<int?>("SourceAsset") ?? 0;
        if (mesh != 0) {
            string type = asset.ReferencedClass(mesh);
            string key = type switch {
                "StaticMesh" => "static_mesh_parameter",
                "SkeletalMesh" => "skeletal_mesh_parameter",
                _ => throw new ConversionException($"Unsupported Niagara Socket Reader source {asset.ObjectPath(mesh)} ({type})")
            };
            data[key] = new JObject { ["asset_source"] = asset.ObjectPath(mesh) };
        }
        return data;
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "IsValid" or "GetSocketCount" or "GetFilteredSocketCount" or "GetUnfilteredSocketCount" => (0, 1),
            "GetComponentToWorld" => (0, 10),
            "GetSocketTransform" or "GetFilteredSocketTransform" or "GetUnfilteredSocketTransform" => (1, 13),
            "GetSocketTransformInterpolated" or "GetFilteredSocketTransformInterpolated"
                or "GetUnfilteredSocketTransformInterpolated" => (2, 13),
            _ => (-1, -1)
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Socket Reader function {name} ({inputs}, {outputs})");
    }
}
