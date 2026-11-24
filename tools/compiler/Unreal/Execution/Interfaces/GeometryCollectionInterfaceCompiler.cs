using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class GeometryCollectionInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceGeometryCollection";
    private static readonly string[] Modes = ["Default", "Source", "AttachParent", "DefaultCollectionOnly", "ParameterBinding"];

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var data = source?.Object?.Properties ?? new JObject();
        var asset = source?.Asset ?? owner;
        string mode = (data.Value<string>("SourceMode") ?? Modes[0]).Split("::").Last();
        if (int.TryParse(mode, out int index) && index >= 0 && index < Modes.Length) mode = Modes[index];
        if (!Modes.Contains(mode, StringComparer.Ordinal)) throw new ConversionException($"Unknown Geometry Collection source mode {mode}");
        string collection = ObjectPropertyReaderCompiler.ObjectPath(asset, data["DefaultGeometryCollection"]);
        return new JObject {
            ["mode"] = mode,
            ["source_component"] = ObjectPropertyReaderCompiler.ObjectPath(asset, data["SourceComponent"]),
            ["source_actor"] = ObjectPropertyReaderCompiler.ObjectPath(asset, data["GeometryCollectionActor"]),
            ["parameter"] = data["GeometryCollectionUserParameter"]?["Parameter"]?.Value<string>("name") ?? "",
            ["default_collection"] = collection,
            ["saved_collection"] = (mode is "Default" or "DefaultCollectionOnly") && collection.Length != 0
                ? (library ?? asset.Library)?.GeometryCollections?.Read(collection) : null,
            ["include_intermediate"] = data.Value<bool>("bIncludeIntermediateBones")
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "GetNumElements" => (0, 1),
            "GetElementBounds" => (1, 6),
            "GetElementTransform" => (1, 10),
            "SetElementTransform" or "SetElementTransformWS" => (11, 0),
            "GetGeometryComponentTransform" => (0, 10),
            _ => (-1, -1)
        };
        if (shape != (inputs, outputs)) throw new ConversionException($"Invalid Geometry Collection CPU function {name} ({inputs}, {outputs})");
    }
}
