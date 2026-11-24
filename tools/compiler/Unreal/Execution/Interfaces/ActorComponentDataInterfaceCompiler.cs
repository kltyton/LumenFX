using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class ActorComponentDataInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceActorComponent";

    internal static JObject Definition(SourceAsset asset, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(asset, reference, ClassName, library);
        JObject properties = source?.Object?.Properties ?? new();
        SourceAsset owner = source?.Asset ?? asset;
        string mode = properties["SourceMode"] switch {
            null => "Default",
            { Type: JTokenType.Integer } value => value.Value<int>() switch {
                0 => "Default", 1 => "AttachParent", 2 => "LocalPlayer",
                _ => throw new ConversionException("Unknown Niagara Actor Component source mode") },
            var value => value.Value<string>()?.Split("::").Last()
                ?? throw new ConversionException("Invalid Niagara Actor Component source mode")
        };
        if (mode is not ("Default" or "AttachParent" or "LocalPlayer"))
            throw new ConversionException($"Unknown Niagara Actor Component source mode: {mode}");
        string actor = NiagaraActorReference.Read(owner, properties);
        var result = new JObject {
            ["mode"] = mode,
            ["local_player_index"] = properties.Value<int?>("LocalPlayerIndex") ?? 0,
            ["require_current"] = properties.Value<bool?>("bRequireCurrentFrameData") ?? true,
            ["binding"] = properties["ActorOrComponentParameter"]?["Parameter"]?.Value<string>("name") ?? "",
            ["source_actor"] = actor
        };
        if (ObjectPropertyFieldSource.CompileActor(owner, actor, "", library)?["root_transform"] is JObject transform)
            result["source_root_transform"] = transform.DeepClone();
        return result;
    }

    internal static void Validate(string function, int inputs, int outputs)
    {
        int width = function switch {
            "GetMatrix" => 17, "GetTransform" => 11, "GetVelocity" => 4, _ => -1
        };
        if (inputs != 0 || outputs != width || width < 0)
            throw new ConversionException($"Invalid Niagara Actor Component function: {function} ({inputs}, {outputs})");
    }
}
