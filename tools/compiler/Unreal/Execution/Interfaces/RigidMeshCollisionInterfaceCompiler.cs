using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class RigidMeshCollisionInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceRigidMeshCollisionQuery";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var asset = source?.Asset ?? owner;
        var properties = source?.Object?.Properties ?? new JObject();
        JArray actorTags = new((properties["ActorTags"] as JArray ?? new()).Values<string>());
        string? oldTag = properties.Value<string>("Tag_DEPRECATED") ?? properties.Value<string>("Tag");
        if (actorTags.Count == 0 && !string.IsNullOrEmpty(oldTag)) actorTags.Add(oldTag);
        return new JObject {
            ["actor_tags"] = actorTags,
            ["component_tags"] = new JArray((properties["ComponentTags"] as JArray ?? new()).Values<string>()),
            ["actors"] = new JArray((properties["SourceActors"] as JArray ?? new())
                .Select(value => ObjectPropertyReaderCompiler.ObjectPath(asset, value)).Where(value => value.Length != 0)),
            ["movable_only"] = properties.Value<bool?>("OnlyUseMoveable") ?? true,
            ["complex"] = properties.Value<bool>("UseComplexCollisions"),
            ["object_type"] = properties.Value<bool>("bFilterByObjectType"),
            ["global_search"] = properties.Value<bool>("GlobalSearchAllowed"),
            ["force_global_search"] = properties.Value<bool>("GlobalSearchForced"),
            ["global_if_unscripted"] = properties.Value<bool>("GlobalSearchFallback_Unscripted"),
            ["max_primitives"] = properties.Value<int?>("MaxNumPrimitives") ?? 100
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var expected = name switch {
            "FindActors" => (12, 1),
            "GetNumElements" => (0, 1),
            "IsWorldPositionInsideCombinedBounds" => (3, 1),
            _ => throw new ConversionException($"Niagara Rigid Mesh Collision function is GPU-only or unknown: {name}")
        };
        if (expected != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Rigid Mesh Collision signature: {name} ({inputs}, {outputs})");
    }
}
