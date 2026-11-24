using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class MaterialCollectionInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceMaterialParameterCollection";
    internal const string AssetClass = "MaterialParameterCollection";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties;
        var asset = source?.Asset ?? owner;
        int collection = properties?.Value<int?>("DefaultCollection") ?? 0;
        return new JObject {
            ["binding"] = properties?["CollectionBinding"]?["ResolvedParameter"]?.Value<string>("name") ?? "",
            ["default"] = collection == 0 ? null : MaterialParameterCollectionSource.Read(asset, collection, library ?? asset.Library).Json()
        };
    }

    internal static JObject CpuFunction(JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        int width = name switch { "SetScalarParameter" => 1, "SetVector4Parameter" => 4, _ => 0 };
        if (width == 0 || inputs != width || outputs != 0)
            throw new ConversionException($"Invalid Niagara Material Parameter Collection signature: {name} ({inputs}, {outputs})");
        string? parameter = (function["FunctionSpecifiers"] as JArray)?.OfType<JObject>().FirstOrDefault()?.Value<string>("Value");
        if (parameter is null)
            throw new ConversionException($"Missing Niagara Material Parameter Collection parameter name: {name}");
        return new JObject { ["parameter"] = parameter };
    }
}
